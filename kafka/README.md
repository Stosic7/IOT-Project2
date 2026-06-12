# Kafka strana (.NET Core) — IoT Projekat 2

Implementacija event-driven mikroservisne arhitekture preko Apache Kafke (KRaft mod, bez Zookeeper-a).

## Komponente

- **kafka** — `confluentinc/cp-kafka:7.6.0`, KRaft (broker+controller), topic `iot-sensors` sa 3 particije
- **kafka-ingestion** — čita `real_time_data.csv`, simulira `DEVICE_COUNT` uređaja, šalje na Kafka (`acks` konfigurabilno)
- **kafka-storage** — konzumer (`storage-group`), batch upis u Postgres (`sensor_readings`), loguje Consumer Lag
- **kafka-analytics** — konzumer (`analytics-group`), Tumbling Window od 10s, alert ako je avg temperatura > prag, loguje end-to-end latenciju
- **postgres-kafka** — `postgres:16`, baza `iot_kafka`, host port `5433`

## Pokretanje

```bash
docker compose -f docker-compose-kafka.yml up --build
```

Kafka je dostupna sa host-a na `localhost:29092`, Postgres na `localhost:5434` (user/pass: `iot`/`iot`).

Provera podataka u bazi:
```bash
docker exec postgres-kafka psql -U iot -d iot_kafka -c "SELECT count(*) FROM sensor_readings;"
```

## Najvažnije env varijable (kafka-ingestion)

| Varijabla | Default | Opis |
|---|---|---|
| `KAFKA_ACKS` | `1` | `0` \| `1` \| `all` |
| `DEVICE_COUNT` | `100` | broj simuliranih uređaja (Scenario A: 100/1000/10000) |
| `RUN_DURATION_SEC` | `60` | trajanje izvršavanja (0 = bez vremenskog limita, koristi se za burst) |
| `RATE_PER_DEVICE_MS` | `0` | pauza po uređaju između poruka (0 = max throughput) |
| `RUN_MODE` | `normal` | `normal` \| `burst` (Scenario C: 50→5000→50 msg/s) |
| `INJECT_ALERTS` | `false` | povremeno ubacuje temperature > 50°C (Scenario D) |
| `ALERT_PROBABILITY` | `0.05` | verovatnoća injektovanja alarma po poruci |

## Najvažnije env varijable (kafka-storage)

| Varijabla | Default | Opis |
|---|---|---|
| `BATCH_SIZE` | `500` | broj poruka po batch upisu |
| `FLUSH_INTERVAL_MS` | `2000` | max vreme između flush-eva |
| `DISABLE_DB_WRITE` | `false` | isključuje upis u Postgres (stress testovi A/C) |

## Najvažnije env varijable (kafka-analytics)

| Varijabla | Default | Opis |
|---|---|---|
| `TEMP_ALERT_THRESHOLD` | `50` | prag za `[ALERT]` (°C) |
| `WINDOW_SEC` | `10` | dužina tumbling window-a |
| `LATENCY_SAMPLE_EVERY` | `1` | loguje `[LATENCY]` za svaku N-tu poruku |

## Scenariji (`scripts/`)

- `scenario_a_kafka.sh` — Massive Sensor Ingestion (100/1000/10000 uređaja × acks=0/1/all)
- `scenario_b_kafka.sh` — Edge Connectivity Failure (`docker network disconnect` 30s, offset recovery)
- `scenario_c_kafka.sh` — Burst Event Load (50→5000→50 msg/s, consumer lag sampling)
- `scenario_d_kafka.sh` — Real-Time Alerting (p50/p95/p99 end-to-end latencija)
- `kafka_producer_perf_test.sh [acks] [num-records] [record-size]` — native `kafka-producer-perf-test`
- `consumer_lag.sh [group]` — `kafka-consumer-groups --describe`
- `collect_docker_stats.sh [out.csv]` — snapshot `docker stats` za sve kafka-side kontejnere

Rezultati se upisuju u `results/`.
