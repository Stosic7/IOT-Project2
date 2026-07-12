# IoT mikroservisi — MQTT + eKuiper CEP + MaaS (Projekat 3)

Nadogradnja Analytics mikroservisa iz Projekta 2 (MQTT strana) sa dve nove komponente:
streaming CEP obradom preko **eKuiper**-a i predikcijom preko **MaaS** (Model-as-a-Service)
mikroservisa. Kompletan sistem je kontejnerizovan (`docker-compose-mqtt.yml`).

## Arhitektura

```
Ingestion ──MQTT──► iot/sensors/# ──┬──► Storage ──► PostgreSQL
                                     ├──► eKuiper (CEP pravila) ──► iot/events ──┐
                                     └──► Analytics ◄────────────────────────────┘
                                              │
                                              └──REST──► MaaS (FastAPI + scikit-learn)
                                                              │
                                              ◄───────────────┘ predikcija temperature

Analytics ──REST /api/stats──► Frontend (React)
```

## Mikroservisi

- **Data Ingestion Service** (Spring Boot, profil `ingestion`) — simulira N IoT uređaja, čita
  `real_time_data.csv` i publikuje očitavanja na `iot/sensors/{device_id}` preko Mosquitto brokera.
- **Data Storage Service** (Spring Boot, profil `storage`) — pretplaćen na `iot/sensors/#`,
  batch upis (500 poruka) u PostgreSQL.
- **Analytics Service** (Spring Boot, profil `analytics`) — pretplaćen na `iot/sensors/#`
  (tumbling window 10s: prosečna temperatura, alert > 50°C, end-to-end latencija) i na
  `iot/events` (CEP događaji od eKuiper-a). Na kraju svakog prozora poziva MaaS REST endpoint
  i upoređuje predviđenu temperaturu sa stvarnim prosekom. Izlaže sve preko `GET /api/stats`.
- **eKuiper** (`lfedge/ekuiper:2.0-slim`) — CEP servis pretplaćen na isti `iot/sensors/#` topic;
  dva pravila (`rule_high_temp` > 29.5°C, `rule_low_temp` < 8.5°C) detektuju kritične vrednosti
  i šalju događaje na novi topic `iot/events`. Provisioning preko REST API-ja: `mqtt/scripts/init_ekuiper.sh`.
- **MaaS** (`mqtt/maas/`, Python + FastAPI + scikit-learn) — `RandomForestRegressor` predviđa
  temperaturu iz ostalih senzorskih veličina (humidity, pressure, light, sound, motion, location).
  Treniran/validiran/testiran hronološkim splitom 70/15/15 (`train.py`). Endpointi: `GET /health`,
  `POST /predict`.
- **Frontend** (React + Vite, `mqtt/frontend/`) — uživo prikazuje pipeline, CEP događaje, MaaS
  predikciju vs stvarnu vrednost i tumbling window statistiku (polling na `/api/stats`).

## Pokretanje

```bash
docker compose -f docker-compose-mqtt.yml up -d --build
./mqtt/scripts/init_ekuiper.sh          # provisioning eKuiper stream-a i pravila
cd mqtt/frontend && npm install && npm run dev
```

Provera:
- `curl localhost:9081/rules` — eKuiper pravila
- `curl localhost:8000/health` — MaaS
- `curl localhost:8083/api/stats` — Analytics (eventCount, eventsByType, predictedTemperature)

Kafka strana i uporedna MQTT/Kafka evaluacija (Projekat 2) opisani su u [izvestaj.md](izvestaj.md)
i [kafka/README.md](kafka/README.md).
