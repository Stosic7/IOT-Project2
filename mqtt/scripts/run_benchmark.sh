#!/bin/bash

RESULTS_FILE="$(dirname "$0")/../results/mqtt_benchmark.csv"
NETWORK="iot-project2_mqtt-net"
DURATION=20

cleanup_bench_containers() {
  docker ps -aq --filter ancestor=emqx/emqtt-bench:latest | xargs -r docker rm -f > /dev/null 2>&1
}

echo "qos,clients,avg_msg_s,peak_msg_s,total_msgs,overrun_pct,mosquitto_cpu_pct,mosquitto_mem_mb" > "$RESULTS_FILE"

run_bench() {
  local QOS=$1
  local CLIENTS=$2

  echo ""
  echo "  QoS=${QOS}  Clients=${CLIENTS}  Duration=${DURATION}s..."

  # Ocisti sve stale emqtt-bench kontejnere pre pokretanja
  cleanup_bench_containers

  CONTAINER_ID=$(docker run -d --network "$NETWORK" emqx/emqtt-bench:latest \
    pub -h mosquitto -p 1883 -t "iot/sensors/bench" \
    -c "$CLIENTS" -I 1 -s 256 --qos "$QOS")

  if [ -z "$CONTAINER_ID" ]; then
    echo "  [ERROR] Failed to start container, skipping..."
    return
  fi

  sleep "$DURATION"

  # Uzmi docker stats za mosquitto na sredini testa
  sleep 5
  STATS=$(docker stats mosquitto --no-stream --format "{{.CPUPerc}},{{.MemUsage}}")
  MOSQ_CPU=$(echo "$STATS" | cut -d',' -f1 | tr -d '%')
  MOSQ_MEM_RAW=$(echo "$STATS" | cut -d',' -f2 | cut -d'/' -f1 | tr -d ' ')
  if echo "$MOSQ_MEM_RAW" | grep -q "GiB"; then
    MOSQ_MEM_MB=$(echo "$MOSQ_MEM_RAW" | tr -d 'GiB' | awk '{printf "%.0f", $1 * 1024}')
  else
    MOSQ_MEM_MB=$(echo "$MOSQ_MEM_RAW" | tr -d 'MiB')
  fi

  # Sacekaj ostatak trajanja
  sleep $(( DURATION - 5 ))

  # Uzmi logove pre ubijanja
  OUTPUT=$(docker logs "$CONTAINER_ID" 2>&1)

  # Ubij i cekaj da zaista stane
  docker kill "$CONTAINER_ID" > /dev/null 2>&1
  docker wait "$CONTAINER_ID" > /dev/null 2>&1
  docker rm "$CONTAINER_ID" > /dev/null 2>&1

  # Pauza da OS oslobodi resurse
  sleep 3

  TOTAL=$(echo "$OUTPUT" | grep "pub total=" | tail -1 | grep -oE 'pub total=[0-9]+' | grep -oE '[0-9]+')
  TOTAL_OVERRUN=$(echo "$OUTPUT" | grep "pub_overrun total=" | tail -1 | grep -oE 'pub_overrun total=[0-9]+' | grep -oE '[0-9]+')
  RATES=$(echo "$OUTPUT" | grep "s pub total=" | awk -F'rate=' '{print $2}' | awk '{print $1}')

  AVG=$(echo "$RATES" | awk '{sum+=$1; n++} END {if(n>0) printf "%.0f", sum/n; else print 0}')
  PEAK=$(echo "$RATES" | awk 'BEGIN{max=0} {if($1>max) max=$1} END {printf "%.0f", max}')

  TOTAL=${TOTAL:-0}
  TOTAL_OVERRUN=${TOTAL_OVERRUN:-0}
  AVG=${AVG:-0}
  PEAK=${PEAK:-0}

  if [ "$TOTAL" -gt 0 ]; then
    OVERRUN_PCT=$(awk "BEGIN {printf \"%.1f\", ($TOTAL_OVERRUN/$TOTAL)*100}")
  else
    OVERRUN_PCT=0
  fi

  echo "  -> avg: ${AVG} msg/s | peak: ${PEAK} msg/s | total: ${TOTAL} | overrun: ${OVERRUN_PCT}% | CPU: ${MOSQ_CPU}% | MEM: ${MOSQ_MEM_MB}MB"
  echo "${QOS},${CLIENTS},${AVG},${PEAK},${TOTAL},${OVERRUN_PCT},${MOSQ_CPU},${MOSQ_MEM_MB}" >> "$RESULTS_FILE"
}

echo "========================================"
echo "  MQTT Broker Benchmark (emqtt-bench)"
echo "  Broker: Mosquitto, msg size: 256B"
echo "========================================"

# Pocisti sve stare kontejnere pre pocetka
cleanup_bench_containers

for QOS in 0 1 2; do
  for CLIENTS in 100 500 1000; do
    run_bench $QOS $CLIENTS
  done
done

# Finalno ciscenje
cleanup_bench_containers

echo ""
echo "========================================"
echo "  Benchmark done. Results: $RESULTS_FILE"
echo "========================================"
cat "$RESULTS_FILE"
