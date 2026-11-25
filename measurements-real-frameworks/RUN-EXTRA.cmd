@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "HERE=%CD%"
set "LOG=%HERE%\extra-run.log"
set IMG=maven:3.9-eclipse-temurin-21
echo == start %date% %time% > "%LOG%"
docker info >nul 2>&1
if errorlevel 1 ( echo Docker Desktop is not running: start it, wait for "Engine running", then run RUN-EXTRA.cmd again & echo == Docker not running >> "%LOG%" & pause & exit /b 1 )
set /a TRIES=0
docker rm -f norvane-runner >nul 2>&1
docker compose up -d >> "%LOG%" 2>&1
:waitdb
docker compose exec -T postgres psql -U postgres -d norvane -tAc "select count(*) from notification_log" >nul 2>&1
if errorlevel 1 ( set /a TRIES+=1 & if !TRIES! GEQ 60 ( echo == database not ready after 5 min >> "%LOG%" & echo Database not ready after 5 minutes, see extra-run.log & pause & exit /b 1 ) & timeout /t 5 /nobreak >nul & goto waitdb )
docker run --rm -v "%HERE%:/work" -v "%USERPROFILE%\.m2:/root/.m2" -w /work %IMG% mvn -q -B -f app/pom.xml -DskipTests package >> "%LOG%" 2>&1
if errorlevel 1 ( echo == BUILD FAILED >> "%LOG%" & exit /b 1 )
docker run --rm --name norvane-runner --network norvane_default -v "%HERE%:/work" -w /work %IMG% bash -c "(command -v curl >/dev/null || (apt-get update -qq && apt-get install -y -qq curl >/dev/null)); bash run-extra.sh" >> "%LOG%" 2>&1
if exist norvane-extra-results.zip del norvane-extra-results.zip
tar -a -c -f norvane-extra-results.zip results extra-run.log
docker compose down >nul 2>&1
echo == finished %date% %time% >> "%LOG%"
echo Finished. Results: norvane-extra-results.zip
pause
