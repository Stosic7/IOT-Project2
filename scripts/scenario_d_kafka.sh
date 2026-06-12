#!/usr/bin/env bash
# Scenario D (Real-Time Alerting) - Kafka strana
# Ubacuje povremene kriticne temperature (>50C) i mer end-to-end latenciju
# od trenutka slanja (sent_at) do trenutka kada Analytics servis ispise [LATENCY]/[ALERT].
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose-kafka.yml"
RESULTS_DIR="results"
mkdir -p "$RESULTS_DIR"

echo ">>> Starting broker + storage + analytics"
$COMPOSE up -d kafka kafka-init postgres-kafka kafka-storage kafka-analytics
sleep 5

echo ">>> Ingestion sa INJECT_ALERTS=true, 20 uredjaja, 60s"
$COMPOSE run --rm \
  -e DEVICE_COUNT=20 \
  -e RATE_PER_DEVICE_MS=200 \
  -e INJECT_ALERTS=true \
  -e ALERT_PROBABILITY=0.1 \
  -e RUN_DURATION_SEC=60 \
  kafka-ingestion > "$RESULTS_DIR/scenario_d_ingestion.log" 2>&1

echo ">>> Cekanje 12s da se poslednji tumbling window (10s) obradi"
sleep 12

echo ">>> Izvlacenje [LATENCY] / [ALERT] redova iz kafka-analytics logova"
docker logs kafka-analytics 2>&1 | grep -E '\[LATENCY\]|\[ALERT\]' > "$RESULTS_DIR/scenario_d_analytics.log" || true

grep '\[LATENCY\]' "$RESULTS_DIR/scenario_d_analytics.log" \
  | grep -oE '[0-9.]+ms' | tr -d 'ms' | sort -n > "$RESULTS_DIR/scenario_d_latencies.txt"

awk '
{ a[NR] = $1 }
END {
  n = NR
  if (n == 0) { print "no samples"; exit }
  p50 = a[(n*50/100 < 1) ? 1 : int(n*50/100)]
  p95 = a[(n*95/100 < 1) ? 1 : int(n*95/100)]
  p99 = a[(n*99/100 < 1) ? 1 : int(n*99/100)]
  printf "samples=%d p50=%sms p95=%sms p99=%sms\n", n, p50, p95, p99
}' "$RESULTS_DIR/scenario_d_latencies.txt" | tee "$RESULTS_DIR/scenario_d_summary.txt"

echo "Done. Vidi $RESULTS_DIR/scenario_d_analytics.log i $RESULTS_DIR/scenario_d_summary.txt"
