#!/usr/bin/env bash
# Prikazuje consumer lag (Kafka strana) za dati consumer group.
# Upotreba: ./scripts/consumer_lag.sh [storage-group|analytics-group]
set -euo pipefail

GROUP="${1:-storage-group}"

docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group "$GROUP"
