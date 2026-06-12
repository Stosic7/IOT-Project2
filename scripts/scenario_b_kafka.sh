#!/usr/bin/env bash
# Scenario B (Edge Connectivity Failures) - Kafka strana
# Pokrene ingestion kontinuirano, prekine mu mrezu na 30s, pa posmatra recovery
# (Kafka consumer nastavlja od poslednjeg committed offseta).
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose-kafka.yml"
NETWORK="kafka-side_default"
RESULTS_DIR="results"
mkdir -p "$RESULTS_DIR"
OUT="$RESULTS_DIR/scenario_b.log"

echo ">>> Starting full stack (kafka-ingestion radi 120s, 20 uredjaja na 200ms)"
RUN_DURATION_SEC=120 DEVICE_COUNT=20 RATE_PER_DEVICE_MS=200 $COMPOSE up -d
sleep 15

echo ">>> [t=0s] Consumer lag pre prekida:" | tee "$OUT"
./scripts/consumer_lag.sh storage-group | tee -a "$OUT"

echo ">>> Disconnecting kafka-ingestion od mreze '$NETWORK' na 30s" | tee -a "$OUT"
docker network disconnect "$NETWORK" kafka-ingestion
sleep 30

echo ">>> Reconnecting kafka-ingestion" | tee -a "$OUT"
docker network connect "$NETWORK" kafka-ingestion

echo ">>> Cekanje 20s na recovery..."
sleep 20

echo "--- kafka-ingestion logs (tail) ---" | tee -a "$OUT"
docker logs --tail 40 kafka-ingestion | tee -a "$OUT"
echo "--- kafka-storage logs (tail) ---" | tee -a "$OUT"
docker logs --tail 40 kafka-storage | tee -a "$OUT"
echo "--- kafka-analytics logs (tail) ---" | tee -a "$OUT"
docker logs --tail 40 kafka-analytics | tee -a "$OUT"
echo "--- consumer lag (storage-group) posle recovery ---" | tee -a "$OUT"
./scripts/consumer_lag.sh storage-group | tee -a "$OUT"

echo "Done. Rezultati u $OUT"
