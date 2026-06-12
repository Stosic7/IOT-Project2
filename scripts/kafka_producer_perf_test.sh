#!/usr/bin/env bash
# Native Kafka benchmark preko kafka-producer-perf-test (direktno na broker, bez .NET ingestion servisa).
# Upotreba: ./scripts/kafka_producer_perf_test.sh [acks] [num-records] [record-size]
set -euo pipefail

ACKS="${1:-1}"
NUM_RECORDS="${2:-100000}"
RECORD_SIZE="${3:-256}"

docker exec kafka kafka-producer-perf-test \
  --topic iot-sensors \
  --num-records "$NUM_RECORDS" \
  --record-size "$RECORD_SIZE" \
  --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092 acks="$ACKS"
