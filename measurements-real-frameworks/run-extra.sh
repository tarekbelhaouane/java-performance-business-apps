#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
OUT="results/extra-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
unset JAVA_TOOL_OPTIONS
mkdir -p /tmp/app && cp app/target/norvane-bench.jar /tmp/app/
JAR=/tmp/app/norvane-bench.jar
log() { echo "== $(date +%T) $*" | tee -a "$OUT/run.log"; }
{ java -version 2>&1; echo "cpus: $(nproc)"; grep MemTotal /proc/meminfo; } > "$OUT/environment.txt"
java loadgen/LatencyProxy.java 5432 5432 0 postgres > /dev/null 2>&1 & FWD=$!
java loadgen/LatencyProxy.java 9092 9092 0 kafka > /dev/null 2>&1 & FWDK=$!
sleep 3
start_app() {
  local name=$1 heap=$2; shift 2
  java -Xmx$heap -Xlog:gc:file="$OUT/gc-$name.log" -jar $JAR "$@" > "$OUT/app-$name.log" 2>&1 & APP=$!
  for i in $(seq 1 120); do curl -sf --max-time 2 localhost:8080/actuator/health >/dev/null 2>&1 && return 0; sleep 1; done
  log "application $name did not start"; return 1
}
stop_app() { kill $APP 2>/dev/null; sleep 5; kill -9 $APP 2>/dev/null; wait $APP 2>/dev/null; sleep 2; }
bench() { echo "GET $2" >> "$OUT/$1"; curl -s --max-time 900 "localhost:8080$2" >> "$OUT/$1"; echo >> "$OUT/$1"; }

java loadgen/LatencyProxy.java 15432 5432 250 postgres > /dev/null 2>&1 & PROXY=$!
sleep 3
log "Calibration: select 1 directly and through the latency proxy (same setting as run-linux.sh)"
start_app rtt-direct 512m && bench rtt.txt "/bench/rtt?n=2000"; stop_app
start_app rtt-proxy 512m --db.port=15432 && bench rtt.txt "/bench/rtt?n=2000"; stop_app
log "Chapter 11: 1, 5 and 20 distinct merchants per page, database behind the proxy"
start_app ch11-distinct 1g --db.port=15432 && {
  bench ch11-distinct-warmup.txt "/bench/card-page-distinct?mode=join&distinct=1"
  for d in 1 5 20; do for m in lazy join dto; do
    bench ch11-distinct.txt "/bench/card-page-distinct?mode=$m&distinct=$d"; done; done
}; stop_app
log "Chapter 11: the same with hibernate.default_batch_fetch_size=16"
start_app ch11-distinct-bf 1g --db.port=15432 --spring.jpa.properties.hibernate.default_batch_fetch_size=16 && {
  bench ch11-distinct-warmup.txt "/bench/card-page-distinct?mode=join&distinct=1"
  for d in 1 5 20; do bench ch11-distinct.txt "/bench/card-page-distinct?mode=lazy&distinct=$d"; done
}; stop_app
log "Chapter 14: Kafka consumer, counter read with the clock, group join separated"
start_app ch14 1g --db.port=15432 && {
  bench ch14-kafka.txt "/bench/kafka/produce?events=100000"
  bench ch14-kafka.txt "/bench/kafka/consume?mode=sequential&events=5000"
  bench ch14-kafka.txt "/bench/kafka/consume?mode=batch&events=95000"
}; stop_app
start_app ch14-slow 1g --db.port=15432 --bench.push-delay-ms=600 && {
  bench ch14-kafka-slow.txt "/bench/kafka/produce?events=3000"
  bench ch14-kafka-slow.txt "/bench/kafka/consume?mode=batch&events=2500&timeoutSeconds=300"
}; stop_app
kill $PROXY $FWD $FWDK 2>/dev/null
tar czf "$OUT.tar.gz" -C results "$(basename "$OUT")"
log "Done: $OUT"
