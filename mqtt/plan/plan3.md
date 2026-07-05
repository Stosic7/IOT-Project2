# Projekat 3 — Plan implementacije (eKuiper + MaaS)

## 1. Analiza postojećeg stanja (Projekat 2 — MQTT varijanta)

Postojeći projekat je jedna Spring Boot (Java 17, Spring Boot 3.5) aplikacija koja se pokreće
kao **tri odvojena Docker kontejnera** preko Spring profila (`ingestion`, `storage`, `analytics`),
plus Mosquitto broker, PostgreSQL i React (Vite) frontend.

Tok podataka danas:

```
IngestionService ──MQTT──► iot/sensors/{deviceId} ──┬──► StorageService ──► PostgreSQL
                                                    └──► AnalyticsService (tumbling window 10s, avg temp, alert > 50°C, latency p50/p95/p99)
```

Ključne komponente:

| Komponenta | Fajl | Šta radi |
|---|---|---|
| Ingestion | `src/.../ingestion/IngestionService.java` | Čita `real_time_data.csv`, simulira N uređaja, publikuje JSON na `iot/sensors/{device_id}` svakih 10ms |
| Storage | `src/.../storage/StorageService.java` | Pretplaćen na `iot/sensors/#`, batch upis u PostgreSQL (500/2s) |
| Analytics | `src/.../analytics/AnalyticsService.java` | Pretplaćen na `iot/sensors/#`, tumbling window 10s, prosečna temperatura, alert, latencija |
| Stats REST | `*StatsController.java` (portovi 8081/8082/8083) | Frontend ih polluje |
| Frontend | `frontend/` (React + Vite) | 3 kartice: Ingestion / Storage / Analytics |
| Infra | `../docker-compose-mqtt.yml` | mosquitto, postgres, 3 servisa |

JSON payload poruke (`SensorReadingDto`): `timestamp, device_id, temperature, humidity,
pressure, light, sound, motion, battery, location, sent_at`.

**Zaključak analize:** arhitektura je već idealna podloga za Projekat 3 — treba samo
*dodati* dva nova kontejnera (eKuiper i MaaS) i *proširiti* Analytics servis, bez ikakvog
refaktorisanja postojećeg koda.

---

## 2. Ciljna arhitektura (Projekat 3)

```
                          ┌────────────────────┐
Ingestion ──► iot/sensors/# ──► Storage ──► PostgreSQL
                 │
                 ├────────────► eKuiper (CEP pravila)
                 │                  │
                 │                  ▼  detektovani događaji
                 │             iot/events  (novi MQTT topic)
                 │                  │
                 └──────────────────┼──► Analytics ──REST──► MaaS (Flask/FastAPI + scikit-learn)
                                    │        │
                                    │        ▼
                                    │   REST /api/analytics/stats ◄── Frontend
```

Analytics ostaje pretplaćen na `iot/sensors/#` (za window statistiku i podatke za MaaS),
a dodatno se pretplaćuje na `iot/events` gde eKuiper šalje detektovane događaje.

**Šta se NE menja:** Ingestion, Storage, PostgreSQL, Mosquitto, postojeći REST endpointi.

---

## 3. Nove komponente

### 3.1. eKuiper (CEP servis) — samo konfiguracija, nula koda

Docker image: `lfedge/ekuiper:2.0-slim` (REST API na portu 9081).

Konfiguracija MQTT source-a preko environment varijable u compose-u:
```
MQTT_SOURCE_DEFAULT_SERVER: "tcp://mosquitto:1883"
```

**Stream** (isti topic kao Analytics — zahtev tačke 2 projekta):
```sql
CREATE STREAM sensors () WITH (DATASOURCE="iot/sensors/#", FORMAT="JSON", TYPE="mqtt")
```

**Dva jednostavna pravila** (dovoljno za demonstraciju CEP-a):

1. `rule_high_temp` — detekcija kritične temperature po poruci:
```sql
SELECT device_id, temperature, location, "HIGH_TEMPERATURE" AS event_type
FROM sensors WHERE temperature > 29.5
```
2. `rule_low_temp` — detekcija kritično niske temperature:
```sql
SELECT device_id, temperature, location, "LOW_TEMPERATURE" AS event_type
FROM sensors WHERE temperature < 8.5
```

*(Pragovi kalibrisani prema `real_time_data.csv`: temp avg=20.46, min=3.83, max=35.18;
>29.5°C i <8.5°C su svaki ~0.1% poruka. Baterija je uvek 80–100 pa LOW_BATTERY pravilo
ne bi nikad okinulo — zato drugo pravilo koristi nisku temperaturu.)*

**Sink** oba pravila: MQTT, topic `iot/events`, broker `tcp://mosquitto:1883`.

**Provisioning:** jedan shell skript `scripts/init_ekuiper.sh` sa 3–4 `curl` poziva na
eKuiper REST API (`POST /streams`, `POST /rules`). Pokreće se jednom nakon `docker compose up`.
Ovo je najjednostavnije — bez custom image-a, bez init kontejnera.

### 3.2. MaaS mikroservis (novi folder `maas/`)

Python + **FastAPI** + **scikit-learn**. Namerno minimalan — 3 fajla:

```
maas/
├── train.py          # offline trening — pokreće se JEDNOM, lokalno
├── app.py            # FastAPI servis, učitava model.joblib
├── model.joblib      # istrenirani model (commit-uje se u repo)
├── requirements.txt  # fastapi, uvicorn, scikit-learn, joblib, pandas
└── Dockerfile
```

**Model (regresija):** predikcija temperature na osnovu ostalih senzorskih veličina
(humidity, pressure, light, sound, motion, location) — "virtuelni senzor temperature".

*Napomena iz analize podataka:* prvobitna ideja (predikcija sledeće temperature iz
prethodnih 10 očitavanja) daje negativan R² — temperatura po uređaju je praktično šum
(susedna očitavanja skaču 25→18°C), pa lag-featuri ne nose informaciju. Signal postoji
u drugim featurima (location: Outside 15°C vs sobe 22°C; humidity r=-0.27; light r=-0.21),
pa konačni model radi feature-based regresiju: **MAE ≈ 2.0°C, R² ≈ 0.50** na test skupu.

`train.py`:
1. Učita `real_time_data.csv` (150k redova), one-hot enkoduje `location`.
2. Hronološki train/validation/test split 70/15/15 — pokriva zahtev "trening, validacija i testiranje".
3. `LinearRegression` (baseline) + `RandomForestRegressor` — ispisuje MAE/R² za oba.
4. `joblib.dump(forest, "model.joblib", compress=3)` (~600KB, commit-uje se u repo).

`app.py` — dva endpointa:
```
GET  /health   → {"status": "ok", "model": "RandomForestRegressor"}
POST /predict  body: {"humidity":.., "pressure":.., "light":.., "sound":.., "motion":.., "location":"Room A"}
               → {"predicted_temperature": 22.03}
```

### 3.3. Izmene Analytics servisa (jedina izmena Java koda)

1. **Novi inbound adapter** `AnalyticsEventsInboundConfig.java` — pretplata na `iot/events`,
   channel `eventsInChannel` (kopija postojećeg `AnalyticsMqttInboundConfig`, ~30 linija).
2. **`AnalyticsService`** — dodati:
   - `@ServiceActivator(inputChannel = "eventsInChannel")` handler: parsira događaj,
     inkrementira brojače po `event_type`, čuva poslednjih ~20 događaja u listi.
   - U postojećem `processTumblingWindow()`: izračunati proseke humidity/pressure/light/
     sound/motion iz window-a + najčešću lokaciju i pozvati MaaS `POST /predict`
     (Spring `RestClient`, url iz propertija `maas.url=${MAAS_URL:http://localhost:8000}`).
     Sačuvati `predictedTemperature` i porediti sa stvarnim prosekom (predicted vs actual
     na dashboardu). Poziv obmotati try/catch — ako MaaS nije dostupan, analytics nastavlja da radi.
3. **`AnalyticsStatsController`** — proširiti postojeći JSON odgovor sa:
   `eventCount`, `eventsByType`, `lastEvents`, `predictedTemperature`.

Nema novih Java servisa, nema novih profila, nema novih dependency-ja
(RestClient je već u `spring-boot-starter-web`).

### 3.4. Frontend (minimalno)

- `AnalyticsCard.jsx` proširiti (ili dodati jednu novu karticu `EventsCard.jsx`):
  - broj CEP događaja + lista poslednjih događaja (tip, device, vrednost),
  - MaaS predikcija sledeće temperature pored postojeće prosečne.
- Koristi već postojeći `usePolling` hook — nula nove infrastrukture.

### 3.5. Docker Compose

U `docker-compose-mqtt.yml` (ili kopiju `docker-compose-p3.yml`) dodati dva servisa:

```yaml
  ekuiper:
    image: lfedge/ekuiper:2.0-slim
    ports: ["9081:9081"]
    environment:
      MQTT_SOURCE_DEFAULT_SERVER: "tcp://mosquitto:1883"
    depends_on: [mosquitto]
    networks: [mqtt-net]

  maas:
    build: ./mqtt/maas
    ports: ["8000:8000"]
    networks: [mqtt-net]
```

i u `mqtt-analytics` dodati `MAAS_URL: http://maas:8000`.

`maas/Dockerfile` — standardni python slim image, `pip install -r requirements.txt`,
`CMD uvicorn app:app --host 0.0.0.0 --port 8000`.

---

## 4. Redosled implementacije

| # | Korak | Obim |
|---|---|---|
| 1 | `maas/train.py` — trening modela, ispis metrika, snimi `model.joblib` | ~60 linija Python |
| 2 | `maas/app.py` + Dockerfile + requirements | ~50 linija |
| 3 | Docker compose: dodati `ekuiper` i `maas` servise | ~25 linija YAML |
| 4 | `scripts/init_ekuiper.sh` — stream + 2 pravila preko REST API | ~30 linija shell |
| 5 | `AnalyticsEventsInboundConfig` + izmene `AnalyticsService` i kontrolera | ~80 linija Java |
| 6 | Frontend: događaji + predikcija na Analytics kartici | ~50 linija JSX |
| 7 | End-to-end test + README sekcija (za GitHub, tačka 5 zadatka) | — |

## 5. Verifikacija (demo scenario)

1. `docker compose -f docker-compose-mqtt.yml up -d --build`
2. `./scripts/init_ekuiper.sh` → proveriti pravila: `curl localhost:9081/rules`
3. `mosquitto_sub -t iot/events` → vide se HIGH_TEMPERATURE / LOW_BATTERY događaji
4. `curl -X POST localhost:8000/predict -d '{"temperatures":[...]}'` → predikcija radi
5. `curl localhost:8083/api/analytics/stats` → sadrži eventCount i predictedTemperature
6. Frontend prikazuje događaje i predikciju uživo

## 6. Šta svesno NE radimo (anti-overengineering)

- **Ne** razdvajamo Javu na više aplikacija — profili već daju "mikroservise po kontejneru".
- **Ne** koristimo Kafka/Redis/message queue za MaaS — običan sinhroni REST poziv je poenta zadatka.
- **Ne** radimo online retraining, model registry, MLflow — jedan offline `train.py` + joblib fajl.
- **Ne** pišemo custom eKuiper plugin — deklarativna SQL pravila kroz REST API.
- **Ne** uvodimo autentifikaciju, HTTPS, health-check orkestraciju — lokalni studentski demo.
- **Ne** menjamo Storage/Ingestion — rade nepromenjeni.
