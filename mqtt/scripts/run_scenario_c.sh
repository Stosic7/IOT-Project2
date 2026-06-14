#!/bin/bash

COMPOSE_FILE="$(dirname "$0")/../../docker-compose-mqtt.yml"
RESULTS_FILE="$(dirname "$0")/../results/mqtt_scenario_c.csv"
MEASURE_WINDOW=10

echo "qos,phase,device_count,throughput_msg_s,cpu_percent,mem_mb" > "$RESULTS_FILE"

get_sent_count() {
  local RAW
  RAW=$(curl -s --max-time 3 http://localhost:8081/api/stats | grep -oE '"sentCount":[0-9]+' | grep -oE '[0-9]+')
  echo "  [DEBUG] get_sent_count raw='${RAW}'" >&2
  echo "${RAW:-0}"
}

measure() {
  local LABEL=$1
  local DEVICES=$2
  local QOS=$3
  local WARMUP=$4

  echo "  Stabilizing ${LABEL} (${DEVICES} devices) for ${WARMUP}s..."
  echo "  [DEBUG] ingestion container status: $(docker inspect -f '{{.State.Status}}' mqtt-ingestion 2>/dev/null)"
  sleep "$WARMUP"

  echo "  [DEBUG] Taking BEFORE count..."
  local BEFORE
  BEFORE=$(get_sent_count)
  echo "  [DEBUG] BEFORE=${BEFORE}, sleeping ${MEASURE_WINDOW}s..."
  sleep "$MEASURE_WINDOW"

  echo "  [DEBUG] Taking AFTER count..."
  local AFTER
  AFTER=$(get_sent_count)
  echo "  [DEBUG] AFTER=${AFTER}"

  local THROUGHPUT=$(( (AFTER - BEFORE) / MEASURE_WINDOW ))

  local STATS CPU MEM_RAW MEM_MB
  STATS=$(docker stats mqtt-ingestion --no-stream --format "{{.CPUPerc}},{{.MemUsage}}")
  CPU=$(echo "$STATS" | cut -d',' -f1 | tr -d '%')
  MEM_RAW=$(echo "$STATS" | cut -d',' -f2 | cut -d'/' -f1 | tr -d ' ')
  if echo "$MEM_RAW" | grep -q "GiB"; then
    MEM_MB=$(echo "$MEM_RAW" | tr -d 'GiB' | awk '{printf "%.0f", $1 * 1024}')
  else
    MEM_MB=$(echo "$MEM_RAW" | tr -d 'MiB')
  fi

  echo "  -> throughput: ${THROUGHPUT} msg/s | CPU: ${CPU}% | MEM: ${MEM_MB}MB"
  echo "${QOS},${LABEL},${DEVICES},${THROUGHPUT},${CPU},${MEM_MB}" >> "$RESULTS_FILE"
}

for QOS in 0 1; do
  echo ""
  echo "========================================"
  echo "  Scenario C — Burst Load  (QoS=${QOS})"
  echo "========================================"

  echo "[1] Normal load: 50 devices"
  MQTT_QOS=$QOS DEVICE_COUNT=50 \
    docker compose -f "$COMPOSE_FILE" up mqtt-ingestion -d --no-deps --force-recreate --no-build
  echo "  [DEBUG] compose up exit code: $?"
  measure "normal" 50 $QOS 15

  echo "[2] BURST: 5000 devices"
  MQTT_QOS=$QOS DEVICE_COUNT=5000 \
    docker compose -f "$COMPOSE_FILE" up mqtt-ingestion -d --no-deps --force-recreate --no-build
  echo "  [DEBUG] compose up exit code: $?"
  measure "burst" 5000 $QOS 15

  echo "[3] Recovery: back to 50 devices"
  MQTT_QOS=$QOS DEVICE_COUNT=50 \
    docker compose -f "$COMPOSE_FILE" up mqtt-ingestion -d --no-deps --force-recreate --no-build
  echo "  [DEBUG] compose up exit code: $?"
  measure "recovery" 50 $QOS 15

  echo "  QoS=${QOS} done."
done

echo ""
echo "========================================"
echo "  Scenario C done. Results: $RESULTS_FILE"
echo "========================================"
cat "$RESULTS_FILE"