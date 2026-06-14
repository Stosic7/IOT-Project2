#!/bin/bash

NETWORK="iot-project2_mqtt-net"
CONTAINER="mqtt-ingestion"
DISCONNECT_SECONDS=30
RESULTS_FILE="$(dirname "$0")/../results/mqtt_scenario_b.csv"

echo "event,timestamp,detail" > "$RESULTS_FILE"

log_event() {
  local EVENT=$1
  local DETAIL=$2
  local TS=$(date '+%Y-%m-%dT%H:%M:%S')
  echo "  [$TS] $EVENT — $DETAIL"
  echo "${EVENT},${TS},${DETAIL}" >> "$RESULTS_FILE"
}

echo "========================================"
echo "  Scenario B — Edge Connectivity Failure"
echo "========================================"

# Stanje pre disconnecta
echo ""
echo "[1] System running normally..."
sleep 5

SENT_BEFORE=$(curl -s --max-time 3 http://localhost:8081/api/stats | jq -r '.sentCount // 0')
RECORDS_BEFORE=$(curl -s --max-time 3 http://localhost:8082/api/stats | jq -r '.totalRecords // 0')
log_event "baseline" "sentCount=${SENT_BEFORE} totalRecords=${RECORDS_BEFORE}"

# Disconnect
echo ""
echo "[2] Disconnecting $CONTAINER from network for ${DISCONNECT_SECONDS}s..."
docker network disconnect "$NETWORK" "$CONTAINER"
log_event "disconnected" "container=${CONTAINER} network=${NETWORK}"

# Sacekaj tokom disconnecta
sleep "$DISCONNECT_SECONDS"

SENT_DURING=$(curl -s --max-time 3 http://localhost:8081/api/stats | jq -r '.sentCount // 0')
log_event "during_disconnect" "sentCount=${SENT_DURING} (expected: no increase or errors)"

# Reconnect
echo ""
echo "[3] Reconnecting..."
docker network connect "$NETWORK" "$CONTAINER"
log_event "reconnected" "container=${CONTAINER} network=${NETWORK}"

# Sacekaj recovery
echo ""
echo "[4] Waiting 20s for recovery..."
sleep 10
SENT_AFTER_10=$(curl -s --max-time 3 http://localhost:8081/api/stats | jq -r '.sentCount // 0')
log_event "recovery_10s" "sentCount=${SENT_AFTER_10}"

sleep 10
SENT_AFTER_20=$(curl -s --max-time 3 http://localhost:8081/api/stats | jq -r '.sentCount // 0')
RECORDS_AFTER=$(curl -s --max-time 3 http://localhost:8082/api/stats | jq -r '.totalRecords // 0')
log_event "recovery_20s" "sentCount=${SENT_AFTER_20} totalRecords=${RECORDS_AFTER}"

# Izracunaj recovery stats
SENT_DIFF=$(( SENT_AFTER_20 - SENT_DURING ))
RECORDS_DIFF=$(( RECORDS_AFTER - RECORDS_BEFORE ))
log_event "summary" "msgs_after_reconnect=${SENT_DIFF} new_db_records=${RECORDS_DIFF}"

echo ""
echo "========================================"
echo "  Scenario B done. Results: $RESULTS_FILE"
echo "========================================"
cat "$RESULTS_FILE"
