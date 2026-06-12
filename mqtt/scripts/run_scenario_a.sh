#!/bin/bash
set -e

COMPOSE_FILE="$(dirname "$0")/../../docker-compose-mqtt.yml"
RESULTS_FILE="$(dirname "$0")/../results/mqtt_scenario_a.csv"
MEASURE_SECONDS=60

# Header
echo "qos,device_count,throughput_msg_s,cpu_percent,mem_mb,total_sent" > "$RESULTS_FILE"

for QOS in 0 1 2; do
  for DEVICES in 100 1000 10000; do
    echo ""
    echo "========================================"
    echo "  QoS=$QOS  DEVICE_COUNT=$DEVICES"
    echo "========================================"

    # Restart ingestion sa novim parametrima
    MQTT_QOS=$QOS DEVICE_COUNT=$DEVICES \
      docker compose -f "$COMPOSE_FILE" up mqtt-ingestion -d --no-deps 2>/dev/null

    echo "Waiting ${MEASURE_SECONDS}s for stable throughput..."
    sleep "$MEASURE_SECONDS"

    # Uzmi sent count na pocetku i kraju 10s prozora za throughput
    SENT_BEFORE=$(docker logs mqtt-ingestion 2>/dev/null \
      | grep "Dispatched" | tail -1 \
      | grep -oE "total sent: [0-9]+" | grep -oE "[0-9]+")
    sleep 10
    SENT_AFTER=$(docker logs mqtt-ingestion 2>/dev/null \
      | grep "Dispatched" | tail -1 \
      | grep -oE "total sent: [0-9]+" | grep -oE "[0-9]+")

    THROUGHPUT=$(( (SENT_AFTER - SENT_BEFORE) / 10 ))

    # Docker stats — jedan snapshot
    STATS=$(docker stats mqtt-ingestion --no-stream --format "{{.CPUPerc}},{{.MemUsage}}")
    CPU=$(echo "$STATS" | cut -d',' -f1 | tr -d '%')
    MEM_RAW=$(echo "$STATS" | cut -d',' -f2 | cut -d'/' -f1 | tr -d ' ')
    # Pretvori MiB/GiB u MB
    if echo "$MEM_RAW" | grep -q "GiB"; then
      MEM_MB=$(echo "$MEM_RAW" | tr -d 'GiB' | awk '{printf "%.0f", $1 * 1024}')
    else
      MEM_MB=$(echo "$MEM_RAW" | tr -d 'MiB')
    fi

    TOTAL_SENT=$SENT_AFTER

    echo "  throughput: ${THROUGHPUT} msg/s | CPU: ${CPU}% | MEM: ${MEM_MB}MB | total_sent: ${TOTAL_SENT}"
    echo "${QOS},${DEVICES},${THROUGHPUT},${CPU},${MEM_MB},${TOTAL_SENT}" >> "$RESULTS_FILE"
  done
done

echo ""
echo "========================================"
echo "  Scenario A done. Results: $RESULTS_FILE"
echo "========================================"
cat "$RESULTS_FILE"