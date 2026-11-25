@echo off
cd /d "%~dp0"
set "HERE=%CD%"
set "LOG=%HERE%\windows-run.log"
set IMG=maven:3.9-eclipse-temurin-21
echo == start %date% %time% > "%LOG%"
echo Norvane measurements - progress in windows-run.log (this window can stay open)
echo == docker compose up >> "%LOG%"
docker compose up -d >> "%LOG%" 2>&1
:waitdb
docker compose exec -T postgres psql -U postgres -d norvane -tAc "select count(*) from notification_log" >nul 2>&1
if errorlevel 1 ( timeout /t 5 /nobreak >nul & goto waitdb )
echo == database ready %time% >> "%LOG%"
echo == build %time% >> "%LOG%"
docker run --rm -v "%HERE%:/work" -v "%USERPROFILE%\.m2:/root/.m2" -w /work %IMG% mvn -q -B -f app/pom.xml -DskipTests package >> "%LOG%" 2>&1
if errorlevel 1 ( echo == BUILD FAILED >> "%LOG%" & exit /b 1 )
echo == build ok %time% >> "%LOG%"
echo == measurements %time% >> "%LOG%"
docker run --rm --name norvane-runner --network norvane_default -v "%HERE%:/work" -w /work %IMG% bash -c "(command -v curl >/dev/null || (apt-get update -qq && apt-get install -y -qq curl >/dev/null)); bash run-linux.sh %*" >> "%LOG%" 2>&1
docker compose exec -T postgres psql -U postgres -d norvane -c "explain (analyze, costs off) select due_date from scheduled_payment where account_id = 2::numeric" > "%HERE%\results\ch12-plan-numeric.txt" 2>&1
echo == finished %date% %time% >> "%LOG%"
