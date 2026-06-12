#!/usr/bin/env bash
# Scenario A (Massive Sensor Ingestion) - Kafka strana
# Sweep DEVICE_COUNT x KAFKA_ACKS, mereci throughput i % izgubljenih poruka.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose-kafka.yml"
RESULTS_DIR="results"
mkdir -p "$RESULTS_DIR"
OUT="$RESULTS_DIR/scenario_a_kafka.csv"

extract() { echo "$1" | grep -oE "$2=[0-9.]+" | head -1 | cut -d= -f2; }

echo "device_count,acks,sent,failed,loss_pct,elapsed_sec,throughput_msg_s" > "$OUT"

echo ">>> Starting broker + storage + analytics"
$COMPOSE up -d kafka kafka-init postgres-kafka kafka-storage kafka-analytics
sleep 5

for DEVICE_COUNT in 100 1000 10000; do
  for ACKS in 0 1 all; do
    echo ">>> Scenario A: DEVICE_COUNT=$DEVICE_COUNT ACKS=$ACKS"
    LOG=$($COMPOSE run --rm \
      -e DEVICE_COUNT="$DEVICE_COUNT" \
      -e KAFKA_ACKS="$ACKS" \
      -e RUN_DURATION_SEC=30 \
      kafka-ingestion 2>&1 | tee "$RESULTS_DIR/scenario_a_${DEVICE_COUNT}_${ACKS}.log")

    SUMMARY=$(echo "$LOG" | grep '\[SUMMARY\]' | tail -1)
    SENT=$(extract "$SUMMARY" sent)
    FAILED=$(extract "$SUMMARY" failed)
    LOSS=$(extract "$SUMMARY" lossPct)
    ELAPSED=$(extract "$SUMMARY" elapsedSec)
    THROUGHPUT=$(extract "$SUMMARY" throughput)

    echo "$DEVICE_COUNT,$ACKS,$SENT,$FAILED,$LOSS,$ELAPSED,$THROUGHPUT" >> "$OUT"
    ./scripts/collect_docker_stats.sh "$RESULTS_DIR/scenario_a_docker_stats.csv"
  done
done

echo "Done. Rezultati u $OUT"
