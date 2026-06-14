#!/bin/bash

COMPOSE_FILE="$(dirname "$0")/../../docker-compose-mqtt.yml"
RESULTS_FILE="$(dirname "$0")/../results/mqtt_scenario_d.csv"
SAMPLES=100
INTERVAL=3

echo "qos,sample,p50_ms,p95_ms,p99_ms,avg_temperature,window_messages" > "$RESULTS_FILE"

run_samples() {
  local QOS=$1
  local COLLECTED=0

  echo "  Starting ingestion with QoS=${QOS}..."
  MQTT_QOS=$QOS DEVICE_COUNT=100 \
    docker compose -f "$COMPOSE_FILE" up mqtt-ingestion -d --no-deps --force-recreate 2>/dev/null

  echo "  Warming up 15s..."
  sleep 15

  echo "  Collecting ${SAMPLES} samples (every ${INTERVAL}s)..."
  while [ $COLLECTED -lt $SAMPLES ]; do
    RESPONSE=$(curl -s --max-time 3 http://localhost:8083/api/stats)

    P50=$(echo "$RESPONSE" | jq -r '.p50 // 0')
    P95=$(echo "$RESPONSE" | jq -r '.p95 // 0')
    P99=$(echo "$RESPONSE" | jq -r '.p99 // 0')
    AVG=$(echo "$RESPONSE" | jq -r '.avgTemperature // 0')
    MSG=$(echo "$RESPONSE" | jq -r '.lastWindowMessages // 0')

    if [ -n "$P50" ] && [ "$P50" != "0" ]; then
      COLLECTED=$((COLLECTED + 1))
      echo "  Sample ${COLLECTED}/${SAMPLES} — p50:${P50}ms p95:${P95}ms p99:${P99}ms"
      echo "${QOS},${COLLECTED},${P50},${P95},${P99},${AVG},${MSG}" >> "$RESULTS_FILE"
    fi

    sleep "$INTERVAL"
  done
}

for QOS in 0 1 2; do
  echo ""
  echo "========================================"
  echo "  Scenario D — Latency  (QoS=${QOS})"
  echo "========================================"
  run_samples $QOS
  echo "  QoS=${QOS} done."
done

echo ""
echo "========================================"
echo "  Scenario D done. Results: $RESULTS_FILE"
echo "========================================"

# Ispisi summary po QoS
echo ""
echo "Summary (avg across samples):"
for QOS in 0 1 2; do
  AVG_P50=$(grep "^${QOS}," "$RESULTS_FILE" | awk -F',' '{sum+=$3; n++} END {printf "%.0f", sum/n}')
  AVG_P95=$(grep "^${QOS}," "$RESULTS_FILE" | awk -F',' '{sum+=$4; n++} END {printf "%.0f", sum/n}')
  AVG_P99=$(grep "^${QOS}," "$RESULTS_FILE" | awk -F',' '{sum+=$5; n++} END {printf "%.0f", sum/n}')
  echo "  QoS ${QOS}: p50=${AVG_P50}ms  p95=${AVG_P95}ms  p99=${AVG_P99}ms"
done
