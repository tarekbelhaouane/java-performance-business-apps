#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
OUT=results
DUMPS=dumps
rm -rf "$OUT" && mkdir -p "$OUT/A" "$OUT/B" "$OUT/C" "$DUMPS"
unset JAVA_TOOL_OPTIONS
log() { echo "== $(date +%T) $*" | tee -a "$OUT/run.log"; }
{ java -version 2>&1; echo "cpus: $(nproc)"; grep MemTotal /proc/meminfo; } > "$OUT/environment.txt"

log "Compile the companion code"
W=/tmp/norvane && rm -rf $W && mkdir -p $W/out $W/out-plugin
javac -d $W/out $(find code/src -name '*.java') || { log "compile failed"; exit 1; }
javac -d $W/out -cp $W/out $(find code/bench -name '*.java') || { log "compile failed"; exit 1; }
javac -d $W/out-plugin -cp $W/out $(find code/plugin-src -name '*.java') || { log "compile failed"; exit 1; }

wait_for() {
  for i in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }

log "Scenario A: audit list leak until OutOfMemoryError (256 MB heap, 20,000 quotes/s)"
java -Xms256m -Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$DUMPS/scenarioA.hprof \
     -Xlog:gc:file=$OUT/A/gc.log:uptime \
     -XX:StartFlightRecording=filename=$OUT/A/incident.jfr,settings=profile,path-to-gc-roots=true \
     -cp $W/out com.bank.payments.incident.AuditIncident 20000 > $OUT/A/service.log 2>&1 &
PID=$!
sleep 15; jcmd $PID GC.class_histogram > $OUT/A/histogram-15s.txt 2>&1
sleep 30; jcmd $PID GC.class_histogram > $OUT/A/histogram-45s.txt 2>&1
jcmd $PID JFR.dump name=1 filename=$OUT/A/live-45s.jfr path-to-gc-roots=true > /dev/null 2>&1
( sleep 300; kill -9 $PID 2>/dev/null ) & GUARD=$!
wait $PID 2>/dev/null; kill $GUARD 2>/dev/null
ls -l $DUMPS/scenarioA.hprof >> $OUT/A/service.log 2>&1
jfr print --events OldObjectSample $OUT/A/live-45s.jfr 2>/dev/null | head -120 > $OUT/A/old-object-samples.txt

log "Scenario B: fee-grid module redeployed 50 times without stop()"
java -Xmx512m -cp $W/out com.bank.payments.incident.RedeployDemo $W/out-plugin 50 false hold \
     > $OUT/B/service.log 2>&1 &
PID=$!
if wait_for $OUT/B/service.log "deploy 50" 180; then
  jcmd $PID VM.classloader_stats > $OUT/B/classloader-stats.txt 2>&1
  jcmd $PID VM.metaspace > $OUT/B/metaspace.txt 2>&1
  jcmd $PID GC.class_histogram > $OUT/B/histogram.txt 2>&1
  jcmd $PID GC.heap_dump $DUMPS/scenarioB.hprof > $OUT/B/heap-dump.txt 2>&1
fi
kill $PID 2>/dev/null; wait $PID 2>/dev/null

log "Chapter 5: lock held during the remote call - three thread dumps and a JFR recording"
for MODE in true false; do
  D=$OUT/C/lock-$MODE
  mkdir -p $D
  java -XX:StartFlightRecording=filename=$D/recording.jfr,settings=profile,duration=22s \
       -cp $W/out com.bank.payments.cards.AuthorisationLoad 50 $MODE 25 > $D/service.log 2>&1 &
  PID=$!
  sleep 8
  for k in 1 2 3; do jcmd $PID Thread.print -l > $D/threaddump-$k.txt 2>&1; sleep 3; done
  wait $PID 2>/dev/null
  for v in contention-by-site hot-methods thread-count; do
    jfr view --width 200 $v $D/recording.jfr > $D/jfr-view-$v.txt 2>&1
  done
  jfr summary $D/recording.jfr > $D/jfr-summary.txt 2>&1
  for k in 1 2 3; do
    echo "--- dump $k"
    grep -h 'java.lang.Thread.State' $D/threaddump-$k.txt | sort | uniq -c
    awk '/^"auth-worker/{getline; getline; print}' $D/threaddump-$k.txt | sort | uniq -c | sort -rn | head -5
  done > $D/grouped.txt
done

log "Eclipse MAT: download (once) and headless analysis"
for tool in curl unzip; do
  command -v $tool >/dev/null || (apt-get update -qq && apt-get install -y -qq $tool >/dev/null)
done
MATV=MemoryAnalyzer-1.16.1.20250109-linux.gtk.x86_64.zip
mkdir -p tools
if [ ! -d tools/mat/plugins ]; then
  if [ ! -s tools/$MATV ]; then
    for url in "https://download.eclipse.org/mat/1.16.1/rcp/$MATV" \
               "https://ftp.nl.freebsd.org/os/Linux/distr/eclipse/mat/1.16.1/rcp/$MATV" \
               "https://mirror.dkm.cz/eclipse/mat/1.16.1/rcp/$MATV"; do
      curl -fL --retry 2 -o tools/$MATV "$url" && break
    done
  fi
  rm -rf tools/mat && unzip -q tools/$MATV -d tools
fi
LAUNCHER=$(ls tools/mat/plugins/org.eclipse.equinox.launcher_*.jar 2>/dev/null | head -1)
if [ -z "$LAUNCHER" ]; then
  log "MAT not available: put $MATV in the tools folder and run again"
else
  mat() {
    local dump=$1 label=$2; shift 2
    echo "== $label" >> $OUT/mat.log
    java -Xmx3g -jar "$LAUNCHER" -consoleLog -nosplash -data /tmp/mat-workspace \
         -application org.eclipse.mat.api.parse "$dump" "$@" >> $OUT/mat.log 2>&1
  }
  for S in A B; do
    DUMP=$DUMPS/scenario$S.hprof
    [ -s $DUMP ] || { log "no dump for scenario $S"; continue; }
    log "MAT on scenario $S: parse, suspects, overview, top components"
    mat $DUMP "$S reports" org.eclipse.mat.api:suspects org.eclipse.mat.api:overview \
        org.eclipse.mat.api:top_components
  done
  log "MAT queries (histogram, OQL)"
  if [ -s $DUMPS/scenarioA.hprof ]; then
    mat $DUMPS/scenarioA.hprof "A histogram" "-command=histogram" org.eclipse.mat.api:query
    mv $DUMPS/scenarioA_Query.zip $DUMPS/scenarioA_histogram.zip 2>/dev/null
    mat $DUMPS/scenarioA.hprof "A OQL" \
      "-command=oql \"SELECT s, s.recentQuotes.size AS quotes, s.@retainedHeapSize AS retained FROM com.bank.payments.lifetime.QuoteAuditService s\"" \
      org.eclipse.mat.api:query
    mv $DUMPS/scenarioA_Query.zip $DUMPS/scenarioA_oql.zip 2>/dev/null
  fi
  if [ -s $DUMPS/scenarioB.hprof ]; then
    mat $DUMPS/scenarioB.hprof "B duplicate classes" "-command=duplicate_classes" org.eclipse.mat.api:query
    mv $DUMPS/scenarioB_Query.zip $DUMPS/scenarioB_duplicate_classes.zip 2>/dev/null
    mat $DUMPS/scenarioB.hprof "B OQL" \
      "-command=oql \"SELECT l, l.@retainedHeapSize AS retained FROM java.net.URLClassLoader l\"" \
      org.eclipse.mat.api:query
    mv $DUMPS/scenarioB_Query.zip $DUMPS/scenarioB_oql.zip 2>/dev/null
  fi
  mkdir -p $OUT/mat
  cp $DUMPS/*.zip $OUT/mat/ 2>/dev/null
  ls -l $DUMPS > $OUT/mat/dump-files.txt
fi
log "Done"
