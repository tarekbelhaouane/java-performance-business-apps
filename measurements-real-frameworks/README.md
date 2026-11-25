# Measurements with the real frameworks (Chapters 9 to 14)

The experiments of Chapters 9 and 11 to 14, run with Spring Boot 3.5, Tomcat, Hibernate, HikariCP,
the PostgreSQL JDBC driver, Spring Batch, Kafka, Jersey and Spring WebSocket instead of the JDK models
of the companion code.

## Requirements

- Docker (Docker Desktop on Windows or macOS) with `docker compose`
- About 6 GB of free memory and 5 GB of disk
- Nothing else: Maven, the JDK and the dependencies run inside a `maven:3.9-eclipse-temurin-21` container

## Running

| Platform | Command | Duration | Results |
|---|---|---|---|
| Windows | double-click `RUN-ALL.cmd` | 45 to 60 min | `norvane-results.zip` |
| Linux, macOS | `./run-all.sh` (all chapters) or `./run-all.sh 11 12` | 45 to 60 min | `results/<date>.tar.gz` |
| Windows, additional runs | `RUN-EXTRA.cmd` | about 10 min | `norvane-extra-results.zip` |

The first run downloads PostgreSQL 16, Kafka 3.9 and the build image (about 1 GB), then loads the
fictional database with 6 million card transactions (2 to 5 minutes). Avoid using the machine for
anything else during a run: the results depend on the CPU available.

`RUN-EXTRA.cmd` calibrates the latency proxy (`select 1` directly and through the proxy), measures the
N+1 pattern with 1, 5 and 20 distinct merchants per page (lazy, fetch join, DTO, batch fetching) and
measures the Kafka consumer with the counter read at the same instant as the clock.

Stop and remove the containers with `docker compose down -v`.

## What is measured

| Chapter | Measurement | Result files |
|---|---|---|
| 9 | Tomcat saturation (200 threads) with a dependency at 50 and 800 ms; HikariCP pool of 10; virtual threads | `ch09*.txt` |
| 10 | Start-up in a container limited to 2, 1 and 0.5 CPU, with 768 MB and 2 GB (selected collector, start-up time) | `ch10.txt` |
| 11 | N+1 with Hibernate, fetch join, DTO projection, batch fetching; monthly report loaded in memory, streamed or computed in SQL | `ch11*.txt` |
| 12 | HikariCP pool size from 1 to 64; row-by-row versus batched inserts; Scenario C with the real driver | `ch12*.txt` |
| 13 | Spring Batch: lookup per item versus per chunk, chunk sizes, Scenario D | `ch13*.txt` |
| 14 | Kafka consumer, sequential versus batched; Jersey client leaks; Spring WebSocket sessions and slow clients | `ch14*.txt` |

## Layout

```
docker-compose.yml          PostgreSQL 16 and Kafka 3.9 (single KRaft broker)
sql/01_norvane_schema.sql   the fictional Norvane database, loaded on first start
loadgen/, loadgen14/        fixed-rate load generator, latency proxy, WebSocket clients (plain Java)
app/, app14/                Spring Boot applications exposing the measurement endpoints
run-linux.sh, run-ch14.sh   the measurement sequences, run inside the container
```

## Troubleshooting

- **Port 5432, 9092 or 8080 already in use**: stop the process using it (a local PostgreSQL, for example).
- **A step fails**: the script continues with the next ones; application logs are in `results/<date>/app-*.log`.
