#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
OUT="results/ch14-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
mkdir -p /tmp/app && cp app14/target/norvane-conn.jar /tmp/app/
JAR=/tmp/app/norvane-conn.jar
unset JAVA_TOOL_OPTIONS
log() { echo "== $(date +%T) $*" | tee -a "$OUT/run.log"; }
{ java -version 2>&1; echo "cpus: $(nproc)"; grep MemTotal /proc/meminfo; echo "ulimit -n: $(ulimit -n)"; } > "$OUT/environment.txt"
mkdir -p /tmp/lg && javac -d /tmp/lg loadgen14/*.java || exit 1
CL="java -cp /tmp/lg com.bank.connections.QuoteStreamClients"

up() { for i in $(seq 1 120); do curl -sf "localhost:$1/partner/consents/1/status" >/dev/null 2>&1 && return 0; sleep 1; done
       log "port $1 did not start"; return 1; }
start() {
  local name=$1 heap=$2; shift 2
  java -Xmx$heap -jar $JAR "$@" > "$OUT/app-$name.log" 2>&1 & APP=$!; up 8080; }
start_limited() {
  local name=$1 heap=$2; shift 2
  bash -c "ulimit -n 1024; exec java -Xmx$heap -jar $JAR $*" > "$OUT/app-$name.log" 2>&1 & APP=$!; up 8080; }
stop() { kill $APP 2>/dev/null; sleep 5; kill -9 $APP 2>/dev/null; wait $APP 2>/dev/null; sleep 2; }
poll() {
  ( for i in $(seq 5 5 $2); do sleep 5; echo "t=${i}s $(curl -s --max-time 8 "localhost:8080/bench/ws/stats?gc=$3")"; done \
      >> "$OUT/$1" ) & POLL=$!; }

log "partner bank API on port 8090"
java -Xmx512m -jar $JAR --server.port=8090 > "$OUT/app-partner.log" 2>&1 & PARTNER=$!
up 8090

if [ "${1:-all}" != ws ]; then
log "Jersey default connector: status only, 1 GB heap, 1,024 descriptors"
start_limited jersey-leak 1g; curl -s --max-time 300 "localhost:8080/bench/jersey/leak?mode=leak&calls=5000" > "$OUT/jersey-leak-1g.txt"; stop
log "Jersey default connector: status only, 64 MB heap"
start_limited jersey-leak64 64m; curl -s --max-time 300 "localhost:8080/bench/jersey/leak?mode=leak&calls=5000" > "$OUT/jersey-leak-64m.txt"; stop
log "Jersey default connector: closed"
start_limited jersey-close 1g; curl -s --max-time 300 "localhost:8080/bench/jersey/leak?mode=close&calls=5000" > "$OUT/jersey-close.txt"; stop
log "Jersey default connector: 800 leaked calls, then 30 s (partner keep-alive timeout 20 s)"
start jersey-hold 1g; curl -s --max-time 300 "localhost:8080/bench/jersey/leak?mode=leak&calls=800&every=400&hold=30" > "$OUT/jersey-closewait.txt"; stop
log "Jersey Apache connector, pool of 20, 1 % of 404"
start jersey-pool 1g
for m in leak leak-timeout fixed; do
  curl -s --max-time 120 "localhost:8080/bench/jersey/pool?mode=$m&seconds=15" > "$OUT/jersey-pool-$m.txt"
done; stop
log "Jersey: one client or one per call"
for run in 1 2; do
  start jersey-clients$run 1g
  for m in shared new-closed new; do curl -s --max-time 300 "localhost:8080/bench/jersey/clients?mode=$m&calls=2000" >> "$OUT/jersey-clients.txt"; done
  stop
done

fi
for mode in leak fixed; do
  log "WebSocket registry, mode=$mode: 40,000 sessions, 80 % without close frame"
  start ws-$mode 512m --ws.mode=$mode
  poll ws-churn-$mode.txt 130 true
  timeout 125 $CL churn 8080 40000 200 500 80 > "$OUT/ws-churn-$mode.client" 2>&1
  wait $POLL; stop
done
for d in sync decorator queue; do
  log "WebSocket slow client, delivery=$d"
  gc=false; [ $d = queue ] && gc=true
  start ws-slow-$d 1g --ws.delivery=$d
  poll ws-slow-$d.txt 60 $gc
  $CL frozen 8080 1 10 > /dev/null 2>&1 & FROZEN=$!
  timeout 70 $CL watch 8080 50 60 > "$OUT/ws-slow-$d.watch" 2>&1
  kill $FROZEN 2>/dev/null; wait $POLL; stop
done
for hb in 0 5; do
  log "WebSocket heartbeat=$hb: 500 silent and 100 healthy clients"
  start ws-hb$hb 512m --ws.heartbeat=$hb
  $CL silent 8080 500 > /dev/null 2>&1 & S=$!
  timeout 50 $CL idle 8080 100 45 > /dev/null 2>&1 & I=$!
  poll ws-heartbeat-$hb.txt 40 false; wait $POLL
  kill $S $I 2>/dev/null; stop
done
for run in 1 2; do
  log "WebSocket: 2,000 idle connections (run $run)"
  start ws-idle$run 512m
  echo "empty: $(curl -s --max-time 8 'localhost:8080/bench/ws/stats?gc=true')" >> "$OUT/ws-idle.txt"
  timeout 40 $CL idle 8080 2000 35 > /dev/null 2>&1 & I=$!
  sleep 25; echo "2000 idle: $(curl -s --max-time 8 'localhost:8080/bench/ws/stats?gc=true')" >> "$OUT/ws-idle.txt"
  wait $I; stop
done
for st in immediate jitter; do
  log "WebSocket reconnection after a restart: $st"
  start ws-storm-$st 1g --ws.auth-ms=50 --ws.auth-permits=10
  timeout 65 $CL storm 8080 2000 3000 $st 10000 > "$OUT/ws-storm-$st.txt" 2>&1
  stop
done

kill $PARTNER 2>/dev/null
tar czf "$OUT.tar.gz" -C results "$(basename "$OUT")"
log "Done: $OUT"
