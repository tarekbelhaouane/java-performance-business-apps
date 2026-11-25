@echo off
cd /d "%~dp0"
set "HERE=%CD%"
set "LOG=%HERE%\ch14-run.log"
set IMG=maven:3.9-eclipse-temurin-21
echo == start %date% %time% > "%LOG%"
docker run --rm -v "%HERE%:/work" -v "%USERPROFILE%\.m2:/root/.m2" -w /work %IMG% mvn -q -B -f app14/pom.xml -DskipTests package >> "%LOG%" 2>&1
if errorlevel 1 ( echo == BUILD FAILED >> "%LOG%" & exit /b 1 )
echo == build ok %time% >> "%LOG%"
docker run --rm --name norvane-ch14 -v "%HERE%:/work" -w /work %IMG% bash -c "(command -v curl >/dev/null || (apt-get update -qq && apt-get install -y -qq curl >/dev/null)); bash run-ch14.sh" >> "%LOG%" 2>&1
echo == finished %date% %time% >> "%LOG%"
