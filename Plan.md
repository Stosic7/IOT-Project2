# IoT Projekat 2 — Plan implementacije
**Tema:** IoT mikroservisi zasnovani na događajima – MQTT vs Kafka  
**Tim:** 2 osobe | **Rok:** danas (2026-06-12)  
**Tech stack:** Spring Boot (MQTT strana) + .NET Core (Kafka strana)

---

## Dataset

Fajl: `real_time_data.csv`  
Atributi: `timestamp, device_id, temperature, humidity, pressure, light, sound, motion, battery, location`  
**Prag za alarm:** temperatura > 50°C (Analytics tumbling window od 10s)

---

## Arhitektura sistema

```
┌─────────────────────────────────────────────────────────────┐
│                     MQTT STRANA (Spring Boot)               │
│                                                             │
│  [Ingestion Service] → [Mosquitto Broker] → [Storage Svc]  │
│                                          → [Analytics Svc] │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     KAFKA STRANA (.NET Core)                │
│                                                             │
│  [Ingestion Service] → [Kafka KRaft] → [Storage Service]   │
│                                      → [Analytics Service] │
└─────────────────────────────────────────────────────────────┘

             Zajednička baza: PostgreSQL (2 schema-e)
```

---

## Podela posla

### Osoba A — MQTT strana (Spring Boot)
### Osoba B — Kafka strana (.NET Core)

Oboje paralelno rade Docker Compose konfiguraciju svojih servisa.

---

## Faza 1 — Infrastruktura (Docker Compose)

### A: `docker-compose-mqtt.yml`
```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    ports: ["1883:1883", "9001:9001"]
    volumes: [./mosquitto/config:/mosquitto/config]

  mqtt-ingestion:    # Spring Boot
  mqtt-storage:      # Spring Boot  
  mqtt-analytics:    # Spring Boot
  
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: iot_mqtt
```

### B: `docker-compose-kafka.yml`
```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      # KRaft mod — bez Zookeeper-a

  kafka-ingestion:   # .NET Core
  kafka-storage:     # .NET Core
  kafka-analytics:   # .NET Core
  
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: iot_kafka
```

---

## Faza 2 — Servisi

### 2.1 Data Ingestion Service

**Zadatak:** Čita `real_time_data.csv` i šalje poruke na broker u realnom vremenu. Podržava konfigurabilan broj uređaja (100/1000/10000) za stress testove.

**A (Spring Boot — MQTT):**
- Dependency: `org.springframework.integration:spring-integration-mqtt`
- Konfigurabilno: QoS nivo (0/1/2) putem env varijable `MQTT_QOS`
- Topic: `iot/sensors/{device_id}`
- Za stress test: generisati sintetičke poruke paralelno (ThreadPoolTaskExecutor)

**B (.NET Core — Kafka):**
- NuGet: `Confluent.Kafka`
- Konfigurabilno: `acks` (0/1/all) putem env varijable `KAFKA_ACKS`
- Topic: `iot-sensors` sa 3 particije (device_id kao partition key)
- Za stress test: `Parallel.ForEach` sa konfigurisanim brojem uređaja

**Zajednički model poruke (JSON):**
```json
{
  "timestamp": "2025-01-01T00:23:25Z",
  "device_id": "Device_24",
  "temperature": 25.59,
  "humidity": 58.74,
  "pressure": 1008.85,
  "light": 205,
  "sound": 48,
  "motion": 0,
  "battery": 81.48,
  "location": "Room B"
}
```

---

### 2.2 Data Storage Service

**Zadatak:** Subscribe na broker, čuva poruke u PostgreSQL. Batching na svakih 500 poruka (Scenario A i C).

**PostgreSQL tabela:**
```sql
CREATE TABLE sensor_readings (
  id BIGSERIAL PRIMARY KEY,
  timestamp TIMESTAMPTZ NOT NULL,
  device_id VARCHAR(50),
  temperature FLOAT,
  humidity FLOAT,
  pressure FLOAT,
  light INT,
  sound INT,
  motion SMALLINT,
  battery FLOAT,
  location VARCHAR(100),
  broker VARCHAR(10),  -- 'mqtt' ili 'kafka'
  received_at TIMESTAMPTZ DEFAULT NOW()
);
```

**A (Spring Boot — MQTT):**
- `MqttPahoMessageDrivenChannelAdapter` za subscribe
- Batch upis: akumulirati u `List<SensorReading>`, flush na 500 ili svakih 2s
- `@Transactional` batch insert via Spring Data JPA `saveAll()`

**B (.NET Core — Kafka):**
- `IConsumer<string, string>` sa `ConsumerConfig`
- `EnableAutoCommit = false`, ručni commit posle batch upisa
- Batch: `ConsumeResult` akumulirati, bulk insert via Dapper ili EF Core `AddRange()`
- Pratiti **Consumer Lag** — logirati razliku između latest offset i committed offset

---

### 2.3 Analytics Service (Stream Processing)

**Zadatak:** Tumbling Window od 10 sekundi — računa prosečnu temperaturu. Alarm ako avg > 50°C.

**A (Spring Boot — MQTT):**
```java
// Pseudo-logika
@Scheduled(fixedRate = 10000)
public void processTumblingWindow() {
    double avg = buffer.stream()
        .mapToDouble(r -> r.getTemperature())
        .average().orElse(0);
    buffer.clear();
    
    if (avg > 50.0) {
        log.error("[ALERT] Prosecna temperatura u prozoru: {}°C — KRITICNO!", avg);
    }
}
```

**B (.NET Core — Kafka):**
```csharp
// Pseudo-logika
var windowEnd = DateTime.UtcNow.AddSeconds(10);
while (DateTime.UtcNow < windowEnd) {
    var result = consumer.Consume(cancellationToken);
    buffer.Add(JsonSerializer.Deserialize<SensorReading>(result.Message.Value));
}
var avg = buffer.Average(r => r.Temperature);
if (avg > 50.0) 
    logger.LogCritical("[ALERT] Avg temp: {avg}°C — KRITICNO!", avg);
buffer.Clear();
```

**Scenario D — End-to-end latencija:**  
U poruci dodati `sent_at` timestamp iz Ingestion servisa. Analytics servis beleži `received_at` i računa razliku → loguje latenciju u ms.

---

## Faza 3 — Konfiguracija brokera

### MQTT (Mosquitto) — `mosquitto/config/mosquitto.conf`
```
listener 1883
allow_anonymous true
persistence true
persistence_location /mosquitto/data/
log_dest stdout
```
QoS se konfiguriše na nivou klijenta (env varijabla), ne brokera.

### Kafka (KRaft) — env varijable u docker-compose
```
KAFKA_NUM_PARTITIONS: 3
KAFKA_DEFAULT_REPLICATION_FACTOR: 1
KAFKA_LOG_RETENTION_HOURS: 24
KAFKA_MESSAGE_MAX_BYTES: 1048576
```
Topic `iot-sensors` kreirati sa 3 particije — device_id kao ključ za konzistentno particionisanje.

---

## Faza 4 — Eksperimentalni scenariji

### Scenario A — Massive Sensor Ingestion
```bash
# Pokrenuti sa env varijablom za broj uređaja
DEVICE_COUNT=100 docker-compose up mqtt-ingestion
DEVICE_COUNT=1000 docker-compose up mqtt-ingestion
DEVICE_COUNT=10000 docker-compose up mqtt-ingestion

# Isti princip za Kafka stranu
DEVICE_COUNT=100 docker-compose up kafka-ingestion
```
**Meriti:** poruke/s, % izgubljenih poruka (QoS 0 vs QoS 2 / acks=0 vs acks=all)

### Scenario B — Edge Connectivity Failures
```bash
# 1. Sistem radi normalno
# 2. Simulirati prekid od 30s
docker network disconnect projekat2_default mqtt-ingestion
sleep 30
docker network connect projekat2_default mqtt-ingestion
# 3. Posmatrati recovery u logovima
docker logs mqtt-storage -f
docker logs kafka-storage -f
```
**MQTT:** persistentne sesije (cleanSession=false) i retained poruke  
**Kafka:** consumer automatski nastavlja od poslednjeg committed offset-a

### Scenario C — Burst Event Load
```bash
# Pokrenuti skriptu koja menja rate tokom vremena
./scripts/burst_test.sh
# Interno: 50 msg/s → naglo 5000 msg/s → pratiti backlog
```
**Meriti:** vreme da se sistem vrati na normalan lag/queue depth

### Scenario D — Real-Time Alerting
- Ingestion servis ubacuje poruke sa temperaturom > 50°C
- Analytics servis loguje: `[LATENCY] Device_X: 47ms end-to-end`
- Sakupiti 100 uzoraka, izračunati p50/p95/p99

---

## Faza 5 — Benchmark alati

### MQTT — emqtt-bench
```bash
docker run --network projekat2_default emqx/emqtt-bench:latest \
  pub -h mosquitto -p 1883 -t "iot/sensors/bench" \
  -c 1000 -I 1 -s 256 --qos 1
```

### Kafka — kafka-producer-perf-test
```bash
docker exec kafka kafka-producer-perf-test \
  --topic iot-sensors \
  --num-records 100000 \
  --record-size 256 \
  --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092 acks=1
```

### Praćenje resursa
```bash
docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}"
```

---

## Faza 6 — Struktura Git repozitorijuma

```
projekat2/
├── docker-compose-mqtt.yml
├── docker-compose-kafka.yml
├── docker-compose.yml              # merge oba + postgres
│
├── mqtt/
│   ├── mosquitto/config/mosquitto.conf
│   ├── ingestion-service/          # Spring Boot
│   ├── storage-service/            # Spring Boot
│   └── analytics-service/          # Spring Boot
│
├── kafka/
│   ├── ingestion-service/          # .NET Core
│   ├── storage-service/            # .NET Core
│   └── analytics-service/          # .NET Core
│
├── scripts/
│   ├── run_scenario_a.sh
│   ├── run_scenario_b.sh
│   ├── run_scenario_c.sh
│   └── burst_test.sh
│
├── results/                        # CSV/JSON sa rezultatima
│   ├── mqtt_qos0_100devices.csv
│   ├── kafka_acks0_100devices.csv
│   └── ...
│
└── report/
    └── izvestaj.md
```

---

## Redosled implementacije (danas)

| Prioritet | Zadatak | Ko | Vreme |
|-----------|---------|-----|-------|
| 1 | Docker Compose infrastruktura + PostgreSQL | Oboje paralelno | 1h |
| 2 | Ingestion servisi (oba) | A: Spring Boot MQTT, B: .NET Kafka | 1.5h |
| 3 | Storage servisi sa batchingom | A i B | 1.5h |
| 4 | Analytics servisi (tumbling window + alert) | A i B | 1h |
| 5 | Scenario A benchmark (100/1000/10000) | Oboje | 1h |
| 6 | Scenario B (network disconnect) | Oboje | 30min |
| 7 | Scenario C (burst load) | Oboje | 30min |
| 8 | Scenario D (latencija) | Oboje | 30min |
| 9 | Popunjavanje tabele rezultata + izveštaj | Oboje | 1h |

---

## Tabela performansi (popuniti posle eksperimenata)

### MQTT

| QoS | Uređaji | Throughput (msg/s) | p95 latencija | Izgubljene poruke | CPU | RAM |
|-----|---------|-------------------|---------------|-------------------|-----|-----|
| 0   | 100     | —                 | —             | —                 | —   | —   |
| 0   | 1000    | —                 | —             | —                 | —   | —   |
| 0   | 10000   | —                 | —             | —                 | —   | —   |
| 1   | 100     | —                 | —             | —                 | —   | —   |
| 1   | 1000    | —                 | —             | —                 | —   | —   |
| 2   | 100     | —                 | —             | —                 | —   | —   |
| 2   | 1000    | —                 | —             | —                 | —   | —   |

### Kafka

| acks | Uređaji | Throughput (msg/s) | p95 latencija | Consumer Lag | CPU | RAM |
|------|---------|-------------------|---------------|--------------|-----|-----|
| 0    | 100     | —                 | —             | —            | —   | —   |
| 0    | 1000    | —                 | —             | —            | —   | —   |
| 1    | 100     | —                 | —             | —            | —   | —   |
| 1    | 1000    | —                 | —             | —            | —   | —   |
| all  | 100     | —                 | —             | —            | —   | —   |
| all  | 1000    | —                 | —             | —            | —   | —   |

---

## Odgovori na kritička pitanja (za izveštaj)

### 1. Zašto je MQTT idealan za edge, ali neadekvatan za istorijsku analitiku?
- **MQTT prednosti na edge:** lightweight protokol (~2 byte header), radi na TCP sa malim footprintom RAM-a, QoS garantuje isporuku čak i na nestabilnim mrežama, persistentne sesije pamte subscribe dok je klijent offline
- **MQTT ograničenja za analitiku:** broker ne čuva istoriju poruka (samo retained message za poslednju vrednost), nema replay mehanizma, nema horizontalnog skaliranja brokera bez eksternih alata (cluster Mosquitto je kompleksan), nema built-in stream processing

### 2. Zašto Kafka dominira u cloud, kolika je cena skalabilnosti?
- **Kafka prednosti:** distribuirani commit log čuva sve poruke (konfigurisano vreme retention), consumer grupe nezavisno čitaju isti stream, horizontalno skaliranje dodavanjem brokera/particija, replay omogućava reprocessing
- **Cena:** minimalni footprint ~512MB RAM samo za JVM, KRaft mod smanjuje overhead (nema Zookeeper), ali i dalje nepraktično za ARM edge uređaje sa 256MB RAM; latencija viša nego MQTT zbog batch commit logike

---

## Napomene

- **Batching u Storage servisu:** obavezno za Scenario A i C — bez toga I/O PostgreSQL-a postaje usko grlo, ne broker
- **QoS 2 i acks=all:** najsporiji, ali nula izgubljenih poruka — dokumentovati trade-off
- **Consumer Lag monitoring:** `kafka-consumer-groups.sh --describe` ili logirati u Analytics servisu
- **Scenario B:** MQTT `cleanSession=false` + Kafka offset resume su ključne razlike za izveštaj
