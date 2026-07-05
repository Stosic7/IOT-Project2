#!/usr/bin/env bash
# Provisioning eKuiper-a: stream nad iot/sensors/# + 2 CEP pravila koja salju
# dogadjaje na iot/events. Pokrenuti jednom nakon "docker compose up".
set -e

EKUIPER=${EKUIPER_URL:-http://localhost:9081}

echo "Cekam da eKuiper bude spreman..."
until curl -sf "$EKUIPER" > /dev/null; do sleep 1; done

# Stream nad istim topicom na koji je pretplacen i Analytics
curl -s -X POST "$EKUIPER/streams" -H 'Content-Type: application/json' -d '{
  "sql": "CREATE STREAM sensors () WITH (DATASOURCE=\"iot/sensors/#\", FORMAT=\"JSON\", TYPE=\"mqtt\")"
}'
echo

# Pravilo 1: kriticno visoka temperatura (>29.5C je ~0.1% poruka u datasetu)
curl -s -X POST "$EKUIPER/rules" -H 'Content-Type: application/json' -d '{
  "id": "rule_high_temp",
  "sql": "SELECT device_id, temperature, location, \"HIGH_TEMPERATURE\" AS event_type FROM sensors WHERE temperature > 29.5",
  "actions": [{"mqtt": {"server": "tcp://mosquitto:1883", "topic": "iot/events"}}]
}'
echo

# Pravilo 2: kriticno niska temperatura (<8.5C je ~0.1% poruka u datasetu)
curl -s -X POST "$EKUIPER/rules" -H 'Content-Type: application/json' -d '{
  "id": "rule_low_temp",
  "sql": "SELECT device_id, temperature, location, \"LOW_TEMPERATURE\" AS event_type FROM sensors WHERE temperature < 8.5",
  "actions": [{"mqtt": {"server": "tcp://mosquitto:1883", "topic": "iot/events"}}]
}'
echo

echo "Gotovo. Provera: curl $EKUIPER/rules"
