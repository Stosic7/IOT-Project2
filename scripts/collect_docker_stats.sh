#!/usr/bin/env bash
# Snima jedan docker-stats snapshot Kafka-strane kontejnera u CSV.
# Upotreba: ./scripts/collect_docker_stats.sh [results/docker_stats.csv]
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-results/docker_stats.csv}"
mkdir -p "$(dirname "$OUT")"

if [ ! -f "$OUT" ]; then
  echo "timestamp,name,cpu_perc,mem_usage,net_io" > "$OUT"
fi

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

for c in kafka kafka-ingestion kafka-storage kafka-analytics postgres-kafka; do
  if docker inspect "$c" >/dev/null 2>&1; then
    docker stats --no-stream --format "{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.NetIO}}" "$c" \
      | sed "s/^/${TS},/" >> "$OUT"
  fi
done

echo "Snapshot appended to $OUT"
