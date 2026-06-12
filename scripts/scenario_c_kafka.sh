#!/usr/bin/env bash
# Scenario C (Burst Event Load) - Kafka strana
# Naglo povecanje sa 50 na 5000 msg/s, prati se consumer lag (backlog) i recovery.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose-kafka.yml"
RESULTS_DIR="results"
mkdir -p "$RESULTS_DIR"

echo ">>> Starting broker + storage + analytics"
$COMPOSE up -d kafka kafka-init postgres-kafka kafka-storage kafka-analytics
sleep 5

echo ">>> Burst ingestion: 50 -> 5000 -> 50 msg/s (25s ukupno)"
$COMPOSE run --rm \
  -e RUN_MODE=burst \
  -e DEVICE_COUNT=200 \
  -e BURST_BASE_RATE=50 \
  -e BURST_PEAK_RATE=5000 \
  -e BURST_PEAK_SEC=5 \
  -e BURST_BASE_SEC=10 \
  -e RUN_DURATION_SEC=0 \
  kafka-ingestion > "$RESULTS_DIR/scenario_c_ingestion.log" 2>&1 &
INGEST_PID=$!

echo ">>> Sampling consumer lag (storage-group) svake 2s tokom burst-a"
LAG_LOG="$RESULTS_DIR/scenario_c_lag.log"
: > "$LAG_LOG"
for i in $(seq 1 18); do
  echo "--- t=$((i*2))s ---" >> "$LAG_LOG"
  ./scripts/consumer_lag.sh storage-group >> "$LAG_LOG" 2>&1
  sleep 2
done

wait "$INGEST_PID"
echo "Done. Vidi $RESULTS_DIR/scenario_c_ingestion.log i $LAG_LOG"
