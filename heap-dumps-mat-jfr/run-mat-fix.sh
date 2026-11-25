#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"
OUT=results-fix
DUMPS=dumps
rm -rf "$OUT" && mkdir -p "$OUT/A" "$OUT/B" "$DUMPS"
unset JAVA_TOOL_OPTIONS
log() { echo "== $(date +%T) $*" | tee -a "$OUT/run.log"; }
wait_for() { for i in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1; }

log "Compile"
W=/tmp/norvane && rm -rf $W && mkdir -p $W/out $W/out-plugin
javac -nowarn -d $W/out $(find code/src -name '*.java') 2>/dev/null
javac -nowarn -d $W/out -cp $W/out $(find code/bench -name '*.java') 2>/dev/null || { log "compile failed"; exit 1; }
javac -nowarn -d $W/out-plugin -cp $W/out $(find code/plugin-src -name '*.java') 2>/dev/null

log "Scenario A fixed: same load (256 MB, 20,000 quotes/s) for 90 s with BoundedQuoteAudit"
java -Xms256m -Xmx256m -Xlog:gc:file=$OUT/A/gc.log:uptime \
     -cp $W/out com.bank.payments.incident.AuditIncidentFixed 20000 90 > $OUT/A/service.log 2>&1 &
PID=$!
sleep 45; jcmd $PID GC.class_histogram > $OUT/A/histogram-45s.txt 2>&1
if wait_for $OUT/A/service.log "ready for dump" 120; then
  jcmd $PID GC.class_histogram > $OUT/A/histogram-90s.txt 2>&1
  jcmd $PID GC.heap_dump $DUMPS/scenarioA-fixed.hprof > $OUT/A/heap-dump.txt 2>&1
fi
kill $PID 2>/dev/null; wait $PID 2>/dev/null

log "Scenario B fixed: 50 redeployments with stop()"
java -Xmx512m -cp $W/out com.bank.payments.incident.RedeployDemo $W/out-plugin 50 true hold \
     > $OUT/B/service.log 2>&1 &
PID=$!
if wait_for $OUT/B/service.log "deploy 50" 180; then
  jcmd $PID VM.classloader_stats > $OUT/B/classloader-stats.txt 2>&1
  jcmd $PID VM.metaspace > $OUT/B/metaspace.txt 2>&1
  jcmd $PID GC.class_histogram > $OUT/B/histogram.txt 2>&1
  jcmd $PID GC.heap_dump $DUMPS/scenarioB-fixed.hprof > $OUT/B/heap-dump.txt 2>&1
fi
kill $PID 2>/dev/null; wait $PID 2>/dev/null

LAUNCHER=$(ls tools/mat/plugins/org.eclipse.equinox.launcher_*.jar 2>/dev/null | head -1)
if [ -z "$LAUNCHER" ]; then log "MAT not found in tools/: run RUN-MAT.cmd first"; exit 0; fi
mat() { local dump=$1 label=$2; shift 2; echo "== $label" >> $OUT/mat.log
  java -Xmx3g -jar "$LAUNCHER" -consoleLog -nosplash -data /tmp/mat-workspace \
       -application org.eclipse.mat.api.parse "$dump" "$@" >> $OUT/mat.log 2>&1; }
for S in A-fixed B-fixed; do
  D=$DUMPS/scenario$S.hprof
  [ -s $D ] || continue
  log "MAT on scenario $S"
  mat $D "$S reports" org.eclipse.mat.api:suspects org.eclipse.mat.api:overview org.eclipse.mat.api:top_components
done
[ -s $DUMPS/scenarioA-fixed.hprof ] && {
  mat $DUMPS/scenarioA-fixed.hprof "A-fixed histogram" "-command=histogram" org.eclipse.mat.api:query
  mv $DUMPS/scenarioA-fixed_Query.zip $DUMPS/scenarioA-fixed_histogram.zip 2>/dev/null; }
[ -s $DUMPS/scenarioB-fixed.hprof ] && {
  mat $DUMPS/scenarioB-fixed.hprof "B-fixed duplicate classes" "-command=duplicate_classes" org.eclipse.mat.api:query
  mv $DUMPS/scenarioB-fixed_Query.zip $DUMPS/scenarioB-fixed_duplicate_classes.zip 2>/dev/null
  mat $DUMPS/scenarioB-fixed.hprof "B-fixed OQL" \
    "-command=oql \"SELECT l, l.@retainedHeapSize AS retained FROM java.net.URLClassLoader l\"" org.eclipse.mat.api:query
  mv $DUMPS/scenarioB-fixed_Query.zip $DUMPS/scenarioB-fixed_oql.zip 2>/dev/null; }
mkdir -p $OUT/mat && cp $DUMPS/*fixed*.zip $OUT/mat/ 2>/dev/null
log "Done"
