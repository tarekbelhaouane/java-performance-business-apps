# Java Performance for Business Applications - companion code

Code and measurement projects for the book *Java Performance for Business Applications:
Solving Production Incidents from the JVM to the Database* by Tarek Belhaouane.

| Folder | What it contains | Requirements |
|---|---|---|
| `companion-code/` | Every program of the book: the Norvane Bank transfer-quote domain and the benchmarks and demonstrations of Chapters 2 to 15. `README.md` gives the command for each figure and table. | JDK 21, PostgreSQL 16 for Chapters 11 to 14 |
| `measurements-real-frameworks/` | The same experiments with Spring Boot, Tomcat, Hibernate, HikariCP, Spring Batch, Kafka, Jersey and Spring WebSocket (Chapters 9 and 11 to 14). One command: `RUN-ALL.cmd` on Windows, `run-linux.sh` on Linux. | Docker |
| `heap-dumps-mat-jfr/` | Scenarios A and B of Chapter 8 analysed with Eclipse MAT, before and after the fix, and the thread dumps and JFR recordings of Chapter 5. `RUN-MAT.cmd`, then `RUN-MAT-FIX.cmd`. | Docker |

Norvane Bank and its data are fictional. Your numbers will differ from the book's;
the shapes, the ratios and the methods should not.

Licence: MIT (see `LICENSE`).
