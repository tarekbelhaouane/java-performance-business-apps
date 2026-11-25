@echo off
setlocal
cd /d "%~dp0"
set "HERE=%CD%"
set "LOG=%HERE%\mat-fix-run.log"
echo == start %date% %time% > "%LOG%"
docker info >nul 2>&1
if errorlevel 1 ( echo Docker Desktop is not running & pause & exit /b 1 )
echo Running the fixed versions, about 5 minutes. Keep this window open.
docker run --rm --name norvane-mat-fix -m 6g -v "%HERE%:/work" -w /work maven:3.9-eclipse-temurin-21 bash run-mat-fix.sh >> "%LOG%" 2>&1
if exist norvane-mat-fix-results.zip del norvane-mat-fix-results.zip
tar -a -c -f norvane-mat-fix-results.zip results-fix mat-fix-run.log
echo Finished. Results: norvane-mat-fix-results.zip
pause
