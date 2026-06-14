# Tehnički izveštaj — Event-driven IoT mikroservisi: MQTT vs Kafka

**Tema:** Komparativna evaluacija MQTT i Apache Kafka kao message broker-a za event-driven IoT mikroservisnu arhitekturu
**Tech stack:** Spring Boot (MQTT strana) + .NET Core / Confluent.Kafka (Kafka strana) + PostgreSQL

---

## 1. Opis implementiranog sistema

Implementirane su dve paralelne, funkcionalno identične mikroservisne arhitekture koje obrađuju isti dataset (`real_time_data.csv` — `timestamp, device_id, temperature, humidity, pressure, light, sound, motion, battery, location`), razlikuju se samo po brokeru poruka:

```
MQTT strana (Spring Boot)
  Ingestion Service  →  Mosquitto Broker  →  Storage Service  →  PostgreSQL (iot_mqtt)
                                          →  Analytics Service (tumbling window 10s)

Kafka strana (.NET Core)
  Ingestion Service  →  Kafka (KRaft mod)  →  Storage Service  →  PostgreSQL (iot_kafka)
                                            →  Analytics Service
```

**Ingestion Service** simulira `DEVICE_COUNT` (100/1000/10000) uređaja koji čitaju dataset i šalju očitavanja na broker u realnom vremenu, sa konfigurabilnim nivoom garancije isporuke (MQTT `QoS` 0/1/2, Kafka `acks` 0/1/all) i timestamp-om slanja (`sent_at`) za merenje end-to-end latencije.

**Storage Service** konzumuje poruke i radi batch upis (500 poruka po batch-u) u PostgreSQL preko bulk-insert mehanizama (`COPY` / `BeginBinaryImportAsync`), uz logovanje consumer lag-a (kod Kafke).

**Analytics Service** radi tumbling window agregaciju (10s): izračunava prosečnu temperaturu u prozoru i podiže `[ALERT]` ukoliko prosek prelazi prag od 50°C. Takođe loguje end-to-end latenciju (`sent_at` → vreme obrade) za percentilna merenja.

Kafka strana koristi topic `iot-sensors` sa 3 particije (ključ = `device_id`), KRaft mod (bez Zookeeper-a), `confluentinc/cp-kafka:7.6.0`. MQTT strana koristi `eclipse-mosquitto:2` sa `cleanSession=false` i retained porukama.

Sistem je orkestriran preko dva Docker Compose fajla (`docker-compose-mqtt.yml`, `docker-compose-kafka.yml`), a oba seta servisa pišu u zajedničku PostgreSQL bazu (razdvojeno po šemama `iot_mqtt` / `iot_kafka`).

---

## 2. Eksperimentalni scenariji — rezime rezultata

### Scenario A — Massive Sensor Ingestion (100 / 1000 / 10000 uređaja)

- **MQTT** je testiran na konfigurisanoj stopi (1 msg/s po uređaju, dakle ispod kapaciteta brokera) — CPU Mosquitto-a ostaje nisko (2.6–12.6% za QoS 0/1, do 102.8% za QoS 2 @ 10000 uređaja), RAM ~840–895 MB, gubici poruka zanemarljivi.
- **Kafka** je testirana u tight-loop max-throughput režimu (64 producer thread-a) — throughput 191k–268k msg/s zavisno od `acks`, ali sa 30–37% gubitaka na producer strani (`KafkaException: queue-full`, `QueueBufferingMaxMessages=1_000_000` se popuni jer producer generiše brže nego što broker+mreža mogu primiti). CPU brokera 13–104%, RAM 0.96–1.18 GB.
- **Zaključak:** gubici na Kafka strani su producer-side (queue overflow), ne broker-side — broker sve primljene poruke trajno upisuje u commit log.

### Scenario B — Edge Connectivity Failure (30s `docker network disconnect`)

- **MQTT:** zahvaljujući `cleanSession=false` i retained porukama, broker čuva subscription/poruke dok je klijent offline; nakon reconnect-a nastavak je bez gubitaka (recovery 10s/20s nakon reconnect-a beleži kontinuirani rast `sentCount`-a i upis novih zapisa u bazu, +1100 zapisa u 20s).
- **Kafka:** `kafka-storage` consumer (grupa `storage-group`) nezavisno čita commit log po poslednjem committed offset-u — prekid producer-a ne utiče na consumer recovery. Consumer lag se nastavio da opada i tokom prekida (npr. partition 1: lag 9 315 403 → 9 297 174 u ~70s), bez greške ili gubitka.
- **Zaključak:** oba sistema se ispravno oporavljaju od prekida na edge-u, ali iz različitih razloga — MQTT preko session state-a na brokeru, Kafka preko commit log + offset-a koji je nezavisan od trenutnog stanja producer-a.

### Scenario C — Burst Event Load (50 → 5000 → 50 msg/s)

- **MQTT:** burst na 5000 msg/s podiže CPU Mosquitto-a sa ~1.7–2% (normal) na ~9.4% (QoS 0) / ~18.1% (QoS 1), zatim pad na ~2% u recovery fazi — broker apsorbuje burst bez backloga jer je 5000 msg/s i dalje ispod njegovog kapaciteta (vidi benchmark, Scenario A).
- **Kafka:** burst od 5000 msg/s u trajanju od 5s formira consumer lag (peak ~81–93 poruka po particiji na t=12s), koji se potpuno otklanja (lag=0) u roku od ~8s nakon povratka na baznu stopu — recovery time ≈ 8s.
- **Zaključak:** oba sistema apsorbuju kratkotrajni burst load bez gubitaka; Kafka eksplicitno materijalizuje backlog kao merljiv consumer lag, dok se kod MQTT burst manifestuje samo kao privremeni skok CPU-a brokera.

### Scenario D — Real-Time Alerting (end-to-end latencija + alert demo)

- **MQTT:** p95 end-to-end latencija 13.2 ms (QoS 0) / 28.4 ms (QoS 1) / 31.4 ms (QoS 2) — latencija raste sa QoS-om zbog dodatnog handshake-a (PUBACK za QoS 1, PUBREC/PUBREL/PUBCOMP za QoS 2).
- **Kafka:** p50/p95/p99 = 7.5 / 10.0 / 11.2 ms (acks=1, 29 384 uzorka) — niža i stabilnija latencija nego MQTT QoS≥1, jer Kafka broker upisuje poruku u particioni log čim je primi sa mreže, bez dodatnog handshake protokola po poruci.
- **Alert demo** (`ALERT_PROBABILITY=1.0`): `[WINDOW] count=740 avgTemp=57.58C` → `[ALERT] Prosecna temperatura u prozoru: 57.58C (n=740) - KRITICNO! prag=50C` — tumbling window i alerting logika rade ispravno na oba sistema.

---

## 3. Uporedna tabela performansi

| | Throughput (max) | p95 latencija | CPU (max load) | RAM footprint |
|---|---|---|---|---|
| MQTT QoS 0 | 623k msg/s | 13 ms | 99% | ~870 MB |
| MQTT QoS 1 | 80k msg/s | 28 ms | 106% | ~880 MB |
| MQTT QoS 2 | 43k msg/s | 31 ms | 106% | ~870 MB |
| Kafka (acks=0) | 264k msg/s | ~10 ms* | 104% | ~1.1 GB |
| Kafka (acks=all) | 246k msg/s | ~10 ms* | 17% | ~1.2 GB |

\* p95 latencija je merena u Scenariju D (acks=1, RF=1) — `acks` nivo ne menja trenutak upisa poruke u broker log (commit log je append-only čim poruka stigne sa mreže), već samo producer-side confirmation, što utiče na throughput/loss (vidi Scenario A), ne na end-to-end latenciju.

**Detaljne tabele po scenariju** (throughput/CPU/RAM za sve kombinacije QoS×uređaji / acks×uređaji) nalaze se u `Plan.md` (sekcija "Tabela performansi"), sirovi podaci u `mqtt/results/*.csv` i `results/*.csv`.

---

## 4. Odgovori na kritička pitanja

### 4.1. Zašto je MQTT idealan za edge, ali neadekvatan za istorijsku analitiku?

**Prednosti na edge-u:**
- Izuzetno lagan protokol — fiksni header od samo 2 bajta, minimalan overhead po poruci.
- Radi preko jednog dugotrajnog TCP konekcije sa malim RAM footprint-om (~870 MB za broker pod opterećenjem od 10k uređaja, što je manje od Kafka brokera u istim uslovima).
- QoS nivoi (1/2) garantuju isporuku i na nestabilnim mrežama putem at-least-once / exactly-once handshake-a (PUBACK / PUBREC-PUBREL-PUBCOMP), što je potvrđeno u Scenariju B — `cleanSession=false` čuva pretplatu i poruke dok je klijent offline, a reconnect je automatski bez gubitka subscription-a.
- Najveći sirovi throughput u celom eksperimentu (623k msg/s na QoS 0), pogodan za veliki broj jeftinih, resursno ograničenih edge uređaja koji generišu kratke, učestale poruke.

**Ograničenja za istorijsku analitiku:**
- Broker ne čuva istoriju poruka — jednom isporučena (i potvrđena) poruka se briše; retained message čuva samo *poslednju* vrednost po topiku, ne istoriju.
- Nema replay mehanizma — ako analitički servis padne ili se dodaje novi consumer, ne postoji način da se "premota" stream i ponovo obradi prošli saobraćaj (osim ako se eksplicitno arhivira na drugom mestu, npr. u storage servisu).
- Horizontalno skaliranje brokera je kompleksno — Mosquitto cluster zahteva eksterne alate (npr. bridge konfiguraciju ili komercijalne distribucije), bez nativnog partition/replication modela.
- Nema built-in stream processing ni concept consumer grupa — svaki dodatni analitički servis se mora implementirati kao zaseban MQTT subscriber koji vidi samo *buduće* poruke, ne i prošlost.

**Zaključak:** MQTT je optimalan za "last mile" komunikaciju sa velikim brojem edge uređaja (low overhead, QoS garancije, mali footprint), ali kao broker nije dizajniran da bude *system of record* — za istorijsku analitiku, reprocessing i audit, podaci moraju biti perzistirani odmah (npr. u PostgreSQL preko Storage servisa), jer broker sam to ne radi.

### 4.2. Zašto Kafka dominira u cloud-u i kolika je cena te skalabilnosti?

**Prednosti Kafke:**
- Distribuirani, append-only commit log čuva *sve* poruke u konfigurisanom retention periodu (ne samo poslednju, kao MQTT retained message) — omogućava replay i reprocessing istog stream-a od strane više nezavisnih consumer grupa.
- Particionisanje (3 particije za topic `iot-sensors`, ključ = `device_id`) omogućava horizontalno skaliranje i paralelnu obradu — dodavanjem brokera i particija raste i throughput i kapacitet za skladištenje.
- Consumer grupe omogućavaju nezavisno čitanje istog stream-a (storage i analytics servisi čitaju isti topic sa različitim offset-ima/group-id-jevima), a offset-based recovery (Scenario B) je potpuno nezavisan od stanja producer-a.
- `acks` nivo (0/1/all) daje fine-grained kontrolu nad trade-off-om throughput vs. durabilnost, bez promene latencije isporuke (vidi napomenu u tabeli iz Sekcije 3).

**Cena skalabilnosti:**
- Significantno veći resursni footprint i kompleksnost u odnosu na MQTT: čak i minimalna single-node KRaft instanca (bez Zookeeper-a) koristi ~1.0–1.2 GB RAM samo za JVM broker proces, naspram ~870 MB za Mosquitto pod sličnim opterećenjem — za resursno ograničene ARM edge uređaje (npr. 256 MB RAM) ovo je nepraktično.
- Veća operativna kompleksnost — particije, replication factor, consumer group rebalancing, retention politika i monitoring consumer lag-a su koncepti koji ne postoje na MQTT strani i zahtevaju dodatnu operativnu disciplinu.
- Producer-side backpressure (queue-full gubici u Scenariju A, 30–37% pri tight-loop opterećenju) pokazuje da maksimalni throughput Kafke (~191k–268k msg/s u ovom setupu) zahteva pažljivo podešavanje producer buffer-a (`QueueBufferingMaxMessages`) i broja producer thread-ova, dok je MQTT na QoS 0 dostigao veći sirovi throughput (623k msg/s) sa jednostavnijom konfiguracijom.
- I pored veće cene, end-to-end latencija Kafke (p95 ≈ 10 ms) je niža i stabilnija od MQTT QoS≥1 (p95 28–31 ms), jer Kafka broker ne implementira per-message handshake protokol — ovo je deo "vrednosti" za koju se plaća veći footprint.

**Zaključak:** Kafka dominira u cloud-u jer je sistem *projektovan* da bude system of record sa replay-om i horizontalnim skaliranjem, što je preduslov za kompleksne analitičke pipeline-e sa više nezavisnih consumer-a — ali ta arhitektura nosi fiksnu resursnu cenu (JVM, particije, replikacija) koja je neopravdana na pojedinačnom edge uređaju.

### 4.3. Koju arhitekturu preporučiti — MQTT, Kafka, ili hibrid?

Na osnovu rezultata iz Sekcije 3, dva sistema su komplementarna, a ne konkurentna, za ovaj use-case:

- **MQTT je optimalan na "edge" sloju** — gde veliki broj jeftinih senzora/uređaja sa ograničenim resursima šalje kratke, učestale poruke. Mali footprint (~870 MB), QoS garancije i automatski reconnect (Scenario B) čine ga idealnim za nepouzdane, resursno ograničene mreže.
- **Kafka je optimalna na "cloud/backend" sloju** — gde je potrebno: (1) više nezavisnih consumer-a nad istim stream-om (storage + analytics + buduće ML pipeline-e), (2) replay/reprocessing istorijskih podataka, (3) horizontalno skaliranje sa rastom broja uređaja (particije po `device_id` već demonstriraju ovaj model na 3 particije).
- **Preporučena hibridna arhitektura:** edge uređaji → MQTT broker (Mosquitto) na rubu mreže → "bridge"/gateway servis koji prevodi MQTT poruke u Kafka topic na backend-u → Kafka kao centralni commit log za sve downstream consumer-e (storage, analytics, alerting, buduća proširenja). Ovim se kombinuje nizak footprint i QoS garancije MQTT-a na edge-u sa replay-om, particioniranjem i skalabilnošću Kafke u cloud-u.
- Kritičan inženjerski trade-off koji ovo opravdava: Kafka donosi ~30% veći RAM footprint i operativnu kompleksnost (particije, consumer groups, retention) u odnosu na MQTT, ali ta cena se isplati tačno u onom delu arhitekture (cloud/analitika) gde su replay i horizontalno skaliranje neophodni — dok bi nametanje Kafke na edge sloj (gde MQTT već radi sa 623k msg/s i ~870 MB) bilo resursno neopravdano.

---

## 5. Zaključak

Oba sistema su implementirana kao funkcionalno ekvivalentne event-driven mikroservisne arhitekture (ingestion → broker → storage + tumbling-window analytics → PostgreSQL) i testirana po istim scenarijima (A–D). Eksperimenti potvrđuju teorijske pretpostavke: MQTT je lakši, brži na niskom nivou i otporniji na edge-u uz manji footprint, dok Kafka nudi durability (commit log + replay), horizontalno skaliranje i nižu/stabilniju end-to-end latenciju po poruci — po ceni većeg resursnog footprint-a i operativne kompleksnosti. Detaljni sirovi rezultati i skripte za reprodukciju scenarija nalaze se u `mqtt/results/` i `results/`.
