# Companion code (Chapters 2 to 15)

Fictional example for "Java Performance for Business Applications". Java 21.

    src/    business code: Money, Segment, TransferRequest, FeeLine, TransferQuote,
            FeePolicy + 4 policies, QuoteService
    bench/  demos and measurement harnesses used in the chapters
            quote/  QuoteDemo, QuoteBatch, QuoteBench, WarmupCurve, DeoptDemo   (ch. 2-3)
            memory/ MemoryMapDemo                                              (ch. 4)
            cards/  FraudScoring, CardAuthorisationService, AuthorisationLoad,
                    ThreadStatesDemo, VirtualThreadsDemo, SingleFlightDemo     (ch. 5)
            lifetime/ ObjectSizes, CollectionSizes, DuplicateStrings, EnumFields,
                    RegulatoryExport, MoneyFormatCheck, QuoteAuditService,
                    AuditLeakDemo                                              (ch. 6)
            gc/     GcWorkload: fixed-rate card-authorisation load              (ch. 7)
            incident/ AuditIncident, BankModule, RateChangeRegistry,
                    RedeployDemo                                               (ch. 8)
            web/ (com.bank.accounts.web) UpcomingPaymentsServer, LoadGenerator,
                    SharedFormatterDemo                                        (ch. 9)
            container/ PodMemoryDemo, QuoteHttpServer                         (ch. 10)
            data/   PgWire (minimal PostgreSQL client), LatencyProxy, SimplePool,
                    StatementPageDemo, MonthlyReportDemo, PoolSizingDemo,
                    WriteDemo, UpcomingPaymentsDbServer                       (ch. 11-12)
            batch/  StatementBatch, ChunkRestartDemo, OltpProbe                 (ch. 13)
            connections/ StatementApi, ResponseLeakDemo, PoolLeakDemo,
                    ClientPerRequestDemo, Ws, QuoteStreamServer,
                    QuoteStreamClients                                         (ch. 14)
            events/ ScoringService, AuthorisationCaller,
                    NotificationConsumerDemo                                   (ch. 14)
            method/ StallServer, ClosedLoopGenerator, PeriodicCausesDemo       (ch. 15)
            markets/ ReconciliationDemo, CommonPoolStarvationDemo, AuditLogDemo,
                    ConfirmationFanOutDemo, ThreadLocalBufferDemo               (ch. 5)
    sql/    norvane_schema.sql: the fictional Norvane database (PostgreSQL 16)
    plugin-src/  the fee-grid module (FeeGridModule + 400 generated FeeRule
                 classes), loaded by RedeployDemo in a new class loader      (ch. 8)

Compile and run:

    javac -d out src/com/bank/payments/quote/*.java
    javac -d out -cp out bench/com/bank/payments/quote/*.java
    java -cp out com.bank.payments.quote.QuoteDemo
    java -cp out com.bank.payments.quote.QuoteBench 1          # 1 to 4 segments
    java -XX:+PrintCompilation -cp out com.bank.payments.quote.QuoteBench 1
    java -Xlog:deoptimization=debug -cp out com.bank.payments.quote.DeoptDemo

    # Chapter 4: threads, direct MB, generated classes, optional NMT mode
    javac -d out -cp out bench/com/bank/payments/memory/*.java
    java -Xmx512m -XX:NativeMemoryTracking=summary -cp out \
         com.bank.payments.memory.MemoryMapDemo 200 64 5000 summary

The harnesses are simple loops, not JMH (see Chapter 15). Numbers depend on hardware.

    # Chapter 5: lock contention, thread dumps, deadlock, virtual-thread pinning
    javac -d out -cp out bench/com/bank/payments/cards/*.java
    java -cp out com.bank.payments.cards.AuthorisationLoad 50 true 20    # then: jcmd <pid> Thread.print
    java -cp out com.bank.payments.cards.AuthorisationLoad 50 false 5
    java -cp out com.bank.payments.cards.ThreadStatesDemo                # dump it; contains a deadlock
    java -cp out com.bank.payments.cards.SingleFlightDemo                # Table 5.5
    java -Djdk.tracePinnedThreads=short -cp out com.bank.payments.cards.VirtualThreadsDemo synchronized

    # Chapter 6: object sizes, allocation of the export, the audit-list leak
    javac -d out -cp out bench/com/bank/payments/lifetime/*.java
    java -cp out com.bank.payments.lifetime.ObjectSizes             # then: jcmd <pid> GC.class_histogram
    java -Xlog:gc -cp out com.bank.payments.lifetime.RegulatoryExport format 2000000
    java -Xlog:gc -cp out com.bank.payments.lifetime.RegulatoryExport builder 2000000
    java -cp out com.bank.payments.lifetime.MoneyFormatCheck             # Table 6.6
    java -XX:-UseCompressedOops -cp out com.bank.payments.lifetime.ObjectSizes   # then jcmd <pid> GC.class_histogram (Table 6.1)
    java -Xmx1g -cp out com.bank.payments.lifetime.AuditLeakDemo 6

    # Chapter 7: the same workload with three collectors (20 s at 20,000 requests/s)
    javac -d out -cp out bench/com/bank/payments/gc/*.java
    LOG="-Xlog:gc*,safepoint:file=gc.log:uptime,level,tags"
    java -Xms1g -Xmx1g -XX:+UseG1GC $LOG -cp out com.bank.payments.gc.GcWorkload 20 20000
    java -Xms1g -Xmx1g -XX:+UseParallelGC $LOG -cp out com.bank.payments.gc.GcWorkload 20 20000
    java -Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational $LOG -cp out com.bank.payments.gc.GcWorkload 20 20000
    java -Xms1g -Xmx1g -XX:MaxGCPauseMillis=10 $LOG -cp out com.bank.payments.gc.GcWorkload 20 20000

    # Chapter 8: the audit incident until OutOfMemoryError, with dump and JFR
    javac -d out -cp out bench/com/bank/payments/incident/*.java
    java -Xms256m -Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dump.hprof \
         -Xlog:gc:file=gc.log -XX:StartFlightRecording:settings=profile \
         -cp out com.bank.payments.incident.AuditIncident 20000
    #   meanwhile: jcmd <pid> GC.class_histogram (twice, 30 s apart)
    #              jcmd <pid> JFR.dump name=1 filename=live.jfr path-to-gc-roots=true
    #              jfr print --events OldObjectSample live.jfr

    # Chapter 8: class loader leak on redeployment
    javac -d out-plugin -cp out plugin-src/com/bank/feegrid/*.java
    java -Xms256m -Xmx256m -cp out com.bank.payments.incident.AuditIncidentFixed 20000 90   # after the fix
    java -cp out com.bank.payments.incident.RedeployDemo out-plugin 50 false   # stop() never called: leak
    java -cp out com.bank.payments.incident.RedeployDemo out-plugin 50 true    # stop() unregisters
    java -XX:MaxMetaspaceSize=64m -cp out com.bank.payments.incident.RedeployDemo out-plugin 50 false
    java -cp out com.bank.payments.incident.RedeployDemo out-plugin 20 false hold   # then jcmd VM.classloader_stats

Measured with OpenJDK 21.0.12 on 2 vCPUs. Your numbers will differ; the shapes should not.

    # Chapters 9 to 15
    javac -d out -cp out $(find bench/com/bank/accounts bench/com/bank/data bench/com/bank/batch \
          bench/com/bank/events bench/com/bank/connections bench/com/bank/method bench/com/bank/payments/container -name '*.java')

    # Chapter 9: 200 workers, 800 ms dependency, 300 requests/s (two terminals)
    java -cp out com.bank.accounts.web.UpcomingPaymentsServer platform 200 800 0 8080 25
    java -cp out com.bank.accounts.web.LoadGenerator 300 20 8080
    java -cp out com.bank.accounts.web.UpcomingPaymentsServer virtual 0 50 10 8080 25   # pool of 10
    java -cp out com.bank.accounts.web.SharedFormatterDemo 8 250000

    # Chapter 10: run inside a container with limits, e.g. docker run --cpus 1 --memory 768m
    java -XshowSettings:system -XX:+PrintFlagsFinal -version
    java -Xmx1g -cp out com.bank.payments.container.PodMemoryDemo 50 18 100            # OOMKilled
    java -XX:MaxRAMPercentage=60 -cp out com.bank.payments.container.PodMemoryDemo 50 18 100

    # Chapters 11-12: PostgreSQL 16 with trust authentication on localhost:5432
    psql -U postgres -h localhost -f sql/norvane_schema.sql
    java -cp out com.bank.data.LatencyProxy 15432 5432 250 &      # adds about 0.9 ms per round trip
    java -cp out com.bank.data.StatementPageDemo 15432 300
    java -cp out com.bank.data.MonthlyReportDemo 5432 load|cursor|sql
    java -cp out com.bank.data.PoolSizingDemo 5432 8 1 2 4 8 16 32 64
    java -cp out com.bank.data.WriteDemo 15432 batch
    java -cp out com.bank.data.WriteDemo 15432 hotrow 10
    java -cp out com.bank.data.UpcomingPaymentsDbServer 15432 200 10 numeric 8080 30   # Scenario C
    java -cp out com.bank.accounts.web.LoadGenerator 50 25 8080

    # Chapter 13
    java -cp out com.bank.batch.StatementBatch 15432 30000 100 1 item count
    java -cp out com.bank.batch.StatementBatch 15432 30000 100 1 chunk count
    java -Xmx128m -cp out com.bank.batch.StatementBatch 5432 300000 100 1 chunk keep   # Scenario D
    # Table 13.7: crash between write and checkpoint, then restart (same|separate, plain|idempotent)
    java -cp out com.bank.batch.ChunkRestartDemo 5432 separate plain crash
    java -cp out com.bank.batch.ChunkRestartDemo 5432 separate plain
    # Table 13.6: online latency while the batch runs (touch /tmp/stop-probe ends the probe)
    java -cp out com.bank.batch.OltpProbe 5432 30 5 partitions=4 &
    java -cp out com.bank.batch.StatementBatch 15432 120000 100 4 chunk count; touch /tmp/stop-probe

    # Chapter 14 - HTTP clients (partner API; nodelay avoids the 40 ms Nagle trap)
    java -Dsun.net.httpserver.nodelay=true -cp out com.bank.connections.StatementApi 8471 20
    (ulimit -n 1024; java -Xmx1g -cp out com.bank.connections.ResponseLeakDemo leak 8471 5000)
    (ulimit -n 1024; java -Xmx16m -cp out com.bank.connections.ResponseLeakDemo leak 8471 5000)
    (ulimit -n 1024; java -Xmx1g -cp out com.bank.connections.ResponseLeakDemo close 8471 5000)
    # partner with one 404 in 100:  StatementApi 8471 100
    java -cp out com.bank.connections.PoolLeakDemo leak|fixed|leak-timeout 8471 8 12
    # partner without errors:       StatementApi 8471 0
    java -cp out com.bank.connections.ClientPerRequestDemo shared|new-closed|new 8471 2000

    # Chapter 14 - WebSocket price stream (server options are -D system properties)
    java -Xmx512m -Dmode=leak|fixed -Dgc=true -cp out com.bank.connections.QuoteStreamServer 9101 5
    java -cp out com.bank.connections.QuoteStreamClients churn 9101 40000 200 500 80
    java -Ddelivery=sync|queue|bounded -cp out com.bank.connections.QuoteStreamServer 9111 5
    java -cp out com.bank.connections.QuoteStreamClients frozen 9111 1 10   # and, at the same time:
    java -cp out com.bank.connections.QuoteStreamClients watch 9111 50 60
    java -Dheartbeat=0|5 -cp out com.bank.connections.QuoteStreamServer 9140 5
    java -cp out com.bank.connections.QuoteStreamClients silent 9140 500    # and: idle 9140 100 45
    java -Dthreads=platform|virtual -Dgc=true -cp out com.bank.connections.QuoteStreamServer 9150 5
    java -cp out com.bank.connections.QuoteStreamClients idle 9150 2000 60
    java -DauthMs=50 -DauthPermits=10 -cp out com.bank.connections.QuoteStreamServer 9160 5
    java -cp out com.bank.connections.QuoteStreamClients storm 9160 2000 3000 immediate|jitter 10000

    # Chapter 14 - retries and Kafka-style consumer
    java -cp out com.bank.events.ScoringService 9000 20 100 6 22 10      # last arg 0 = no shedding
    java -cp out com.bank.events.AuthorisationCaller 9000 300 20 200 none|retry3|budget|breaker
    java -cp out com.bank.events.NotificationConsumerDemo 15432 9000 20 batched

    # Chapter 15
    java -cp out com.bank.method.StallServer 9100 45
    # Table 15.5: a periodic lock versus periodic System.gc() (GC log, safepoints, JFR)
    java -Xlog:gc,safepoint:file=lock.gc -XX:StartFlightRecording=filename=lock.jfr \
         -cp out com.bank.method.PeriodicCausesDemo lock 9200 60
    java -cp out com.bank.method.ClosedLoopGenerator 9100 10 48 40
    java -cp out com.bank.accounts.web.LoadGenerator 200 40 9100

Chapters 11 to 14 use PgWire, a deliberately minimal PostgreSQL client, so that every network round
trip is visible and no dependency is needed. In your own projects, use the PostgreSQL JDBC driver and
HikariCP; the round trips, plans and pool behaviour are the same. The same experiments with the real
driver and frameworks are in `../measurements-real-frameworks`.

    # Chapter 5 (cases with their fixes, Norvane Markets)
    javac -d out -cp out bench/com/bank/markets/*.java
    java -cp out com.bank.markets.ReconciliationDemo 50000
    java -XX:ActiveProcessorCount=4 -cp out com.bank.markets.CommonPoolStarvationDemo common|dedicated
    java -cp out com.bank.markets.AuditLogDemo sync|async /tmp 10
    java -cp out com.bank.markets.ConfirmationFanOutDemo platform|virtual
    java -Xmx2g -cp out com.bank.markets.ThreadLocalBufferDemo platform|virtual
    java "-XX:StartFlightRecording:filename=pin.jfr,jdk.VirtualThreadPinned#threshold=1ms" \
         -cp out com.bank.payments.cards.VirtualThreadsDemo synchronized
    jfr view pinned-threads pin.jfr

