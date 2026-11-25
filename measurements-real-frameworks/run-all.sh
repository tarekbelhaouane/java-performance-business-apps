#!/usr/bin/env bash

set -u
cd "$(dirname "$0")"
CHAPTERS="${*:-9 10 11 12 13 14}"
OUT="results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
JAR=app/target/norvane-bench.jar
PROXY_PID=""
APP_PID=""

log()  { echo "== $*" | tee -a "$OUT/run.log"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing: $1"; exit 1; }; }
want() { [[ " $CHAPTERS " == *" $1 "* ]]; }

need java; need mvn; need docker; need curl
unset JAVA_TOOL_OPTIONS

{
  echo "date: $(date -u +%FT%TZ)"
  java -version 2>&1
  mvn -v 2>&1 | head -1
  docker version --format 'docker {{.Server.Version}}' 2>&1
  echo "cpus: $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null)"
  uname -a
} > "$OUT/environment.txt"
log "Environment written to $OUT/environment.txt"

log "Starting PostgreSQL 16 and Kafka (first start loads 6 million rows: 2 to 5 minutes)"
docker compose up -d || exit 1
until docker compose exec -T postgres psql -U postgres -d norvane -tAc \
      "select count(*) from notification_log" >/dev/null 2>&1; do sleep 5; done
log "Database ready"

log "Building the application"
mvn -q -f app/pom.xml -DskipTests package || exit 1

start_app() {
  local name=$1 heap=$2; shift 2
  java -Xms"$heap" -Xmx"$heap" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$OUT/$name.hprof" \
       -Xlog:gc:file="$OUT/gc-$name.log" -jar "$JAR" "$@" > "$OUT/app-$name.log" 2>&1 &
  APP_PID=$!
  for i in $(seq 1 120); do
    curl -sf --max-time 2 localhost:8080/actuator/health >/dev/null 2>&1 && return 0
    kill -0 $APP_PID 2>/dev/null || { log "application $name failed to start"; return 1; }
    sleep 1
  done
  log "application $name did not start in 120 s"; return 1
}
stop_app() {
  [ -n "$APP_PID" ] && kill $APP_PID 2>/dev/null && wait $APP_PID 2>/dev/null
  APP_PID=""; sleep 2
  rm -f "$OUT"/*.hprof
}
start_proxy() {
  java loadgen/LatencyProxy.java 15432 5432 250 > /dev/null 2>&1 &
  PROXY_PID=$!; sleep 3
}
stop_proxy() { [ -n "$PROXY_PID" ] && kill $PROXY_PID 2>/dev/null; PROXY_PID=""; }
bench() {
  echo "GET $2" >> "$OUT/$1"
  curl -s --max-time 1800 "localhost:8080$2" >> "$OUT/$1"; echo >> "$OUT/$1"
}
sample_metrics() {
  ( for i in $(seq 1 $(( $2 / 5 ))); do
      sleep 5
      busy=$(curl -s --max-time 3 localhost:8080/actuator/metrics/tomcat.threads.busy | grep -o '"value":[0-9.]*' | head -1)
      pend=$(curl -s --max-time 3 localhost:8080/actuator/metrics/hikaricp.connections.pending | grep -o '"value":[0-9.]*' | head -1)
      echo "t=$((i * 5))s tomcat.threads.busy $busy hikaricp.connections.pending $pend"
    done >> "$OUT/$1" ) &
  SAMPLER=$!
}
load() {
  echo "LoadGenerator $2/s for $3 s on $4" >> "$OUT/$1"
  sample_metrics "$1" "$3"
  java loadgen/LoadGenerator.java "$2" "$3" 8080 "$4" >> "$OUT/$1" 2>&1
  wait $SAMPLER
}
trap 'stop_app; stop_proxy' EXIT

if want 9; then
  log "Chapter 9: Tomcat saturation, platform threads"
  start_app ch9-platform 1g && {
    load ch09.txt 300 20 "/accounts/gateway?delayMs=50"
    load ch09.txt 300 20 "/accounts/gateway?delayMs=800"
    load ch09.txt 300 20 "/accounts/db-wait?queryMs=50"
  }; stop_app
  log "Chapter 9: virtual threads"
  start_app ch9-virtual 1g --spring.threads.virtual.enabled=true && {
    load ch09-virtual.txt 300 20 "/accounts/gateway?delayMs=800"
    load ch09-virtual.txt 300 20 "/accounts/db-wait?queryMs=50"
  }; stop_app
fi

if want 10; then
  log "Chapter 10: start-up of the real application under CPU limits"
  for cpus in 2 1 0.5; do
    for mem in 768m 2g; do
      name="ch10-cpu$cpus-mem$mem"
      docker run -d --name "$name" --network norvane_default --cpus "$cpus" -m "$mem" \
        -v "$PWD/app/target:/app:ro" eclipse-temurin:21-jre \
        java -XshowSettings:system -Xlog:gc -jar /app/norvane-bench.jar \
             --db.host=postgres --kafka.host=kafka --bench.create-topic=false > /dev/null
      for i in $(seq 1 180); do
        docker logs "$name" 2>&1 | grep -q "Started NorvaneBenchApplication" && break; sleep 1
      done
      { echo "### cpus=$cpus memory=$mem"
        docker logs "$name" 2>&1 | grep -E "Effective CPU Count|Memory Limit|Using |Started NorvaneBenchApplication"
      } >> "$OUT/ch10.txt"
      docker rm -f "$name" > /dev/null
    done
  done
fi

if want 11; then
  log "Chapter 11: N+1 with Hibernate, database on the same host"
  start_app ch11-local 1g && for m in lazy join dto; do bench ch11.txt "/bench/card-page?mode=$m&pages=300"; done; stop_app
  start_proxy
  log "Chapter 11: database 0.9 ms away"
  start_app ch11-remote 1g --db.port=15432 && for m in lazy join dto; do bench ch11-remote.txt "/bench/card-page?mode=$m&pages=300"; done; stop_app
  log "Chapter 11: batch fetching (hibernate.default_batch_fetch_size=16)"
  start_app ch11-batchfetch 1g --db.port=15432 --spring.jpa.properties.hibernate.default_batch_fetch_size=16 \
    && bench ch11-remote.txt "/bench/card-page?mode=lazy&pages=300"; stop_app
  stop_proxy
  log "Chapter 11: monthly report (load, stream, sql)"
  start_app ch11-report 512m && for m in load stream sql; do bench ch11-report.txt "/bench/monthly-report?mode=$m"; done; stop_app
  log "Chapter 11: monthly report with a 256 MB heap (load is expected to fail)"
  start_app ch11-report-256m 256m && bench ch11-report.txt "/bench/monthly-report?mode=stream" \
    && bench ch11-report.txt "/bench/monthly-report?mode=load"; stop_app
  grep -h "OutOfMemoryError" "$OUT"/app-ch11-report-256m.log | head -2 >> "$OUT/ch11-report.txt"
fi

if want 12; then
  log "Chapter 12: pool size from 1 to 64"
  for p in 1 2 4 8 16 32 64; do
    start_app ch12-pool$p 1g --spring.datasource.hikari.maximum-pool-size=$p \
      && { echo "pool=$p" >> "$OUT/ch12-pool.txt"; bench ch12-pool.txt "/bench/pool?threads=100&seconds=8"; }
    stop_app
  done
  start_proxy
  log "Chapter 12: inserts, database 0.9 ms away"
  start_app ch12-insert 1g --db.port=15432 && for m in autocommit tx batch; do bench ch12-insert.txt "/bench/insert?mode=$m&rows=20000"; done; stop_app
  log "Chapter 12: Scenario C (salary day)"
  start_app ch12-c1 1g --db.port=15432 && load ch12-scenarioC.txt 4 25 "/accounts/upcoming-payments?idType=decimal"; stop_app
  start_app ch12-c2 1g --db.port=15432 && load ch12-scenarioC.txt 50 25 "/accounts/upcoming-payments?idType=decimal"; stop_app
  start_app ch12-c3 1g --db.port=15432 --spring.datasource.hikari.maximum-pool-size=100 \
    && load ch12-scenarioC.txt 50 25 "/accounts/upcoming-payments?idType=decimal"; stop_app
  start_app ch12-c4 1g --db.port=15432 && load ch12-scenarioC.txt 50 25 "/accounts/upcoming-payments?idType=long"; stop_app
  stop_proxy
  docker compose exec -T postgres psql -U postgres -d norvane -c \
    "explain (analyze, costs off) select due_date from scheduled_payment where account_id = 2::numeric" > "$OUT/ch12-plan-numeric.txt"
fi

if want 13; then
  start_proxy
  log "Chapter 13: Spring Batch, item vs chunk lookups, chunk sizes"
  start_app ch13 1g --db.port=15432 && {
    bench ch13.txt "/bench/batch?mode=item&accounts=30000&chunk=100"
    bench ch13.txt "/bench/batch?mode=chunk&accounts=5000&chunk=1"
    for c in 10 100 1000; do bench ch13.txt "/bench/batch?mode=chunk&accounts=30000&chunk=$c"; done
  }; stop_app
  stop_proxy
  log "Chapter 13: Scenario D (job summary keeping every statement): live heap with and without"
  start_app ch13-keep 512m && for n in 100000 200000 300000; do
    bench ch13-keep.txt "/bench/batch?mode=chunk&accounts=$n&chunk=100&keep=true"; done; stop_app
  start_app ch13-nokeep 512m && bench ch13-keep.txt "/bench/batch?mode=chunk&accounts=300000&chunk=100&keep=false"; stop_app
fi

if want 14; then
  start_proxy
  log "Chapter 14: Kafka notification consumer, sequential vs batched (push stub 5 ms)"
  start_app ch14 1g --db.port=15432 && {
    bench ch14.txt "/bench/kafka/produce?events=100000"
    bench ch14.txt "/bench/kafka/consume?mode=sequential&events=5000"
    bench ch14.txt "/bench/kafka/consume?mode=batch&events=100000"
  }; stop_app
  log "Chapter 14: push stub at 600 ms"
  start_app ch14-slow 1g --db.port=15432 --bench.push-delay-ms=600 && {
    bench ch14-slow.txt "/bench/kafka/produce?events=2000"
    bench ch14-slow.txt "/bench/kafka/consume?mode=batch&events=1000&timeoutSeconds=300"
  }; stop_app
  stop_proxy
fi

tar czf "$OUT.tar.gz" -C results "$(basename "$OUT")"
log "Done. Results: $OUT.tar.gz"
log "To stop the containers: docker compose down   (add -v to delete the database)"
