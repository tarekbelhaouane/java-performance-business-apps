# norvane-mat - heap dumps with Eclipse MAT, thread dumps and JFR

Companion experiments for Chapters 5 and 8 of *Java Performance for Business Applications*.

Double-click `RUN-MAT.cmd` (Docker Desktop started). It runs, in a JDK 21 container:

1. **Scenario A** - the audit-list leak of `transfer-service` until `OutOfMemoryError`
   (256 MB heap, 20,000 quotes/s): GC log, two class histograms, JFR with old-object
   samples, heap dump written by `HeapDumpOnOutOfMemoryError`.
2. **Scenario B** - the fee-grid module redeployed 50 times without `stop()`:
   `VM.classloader_stats`, `VM.metaspace`, histogram, heap dump.
3. **Chapter 5** - 50 card-authorisation threads, lock held during the remote call
   and fixed version: three thread dumps each, grouped by state and top frame, and
   a JFR recording read with `jfr view contention-by-site`.
4. **Eclipse MAT 1.16.1**, downloaded once into `tools/` and run headless on both
   dumps: Leak Suspects, System Overview, Top Components, histogram, duplicate
   classes and two OQL queries (retained size of the audit service and of the class
   loaders).

Results: `norvane-mat-results.zip` (reports, logs, dumps of threads, JFR files).
The heap dumps (about 500 MB) stay in `dumps/` and are not zipped: they are the input
for opening MAT's user interface yourself (File > Open Heap Dump), and they contain
fictional data only.

If the MAT download fails (proxy), download
`MemoryAnalyzer-1.16.1.20250109-linux.gtk.x86_64.zip` from eclipse.dev/mat, put it in
`tools/` and run `RUN-MAT.cmd` again.

## After the fixes (RUN-MAT-FIX.cmd)

Run it after `RUN-MAT.cmd` (it reuses `tools/mat`). Same evidence, same MAT reports,
for the fixed versions: Scenario A with `BoundedQuoteAudit` (same 256 MB heap and
20,000 quotes/s for 90 s), Scenario B with `stop()` called on undeployment.
Results: `norvane-mat-fix-results.zip`.
