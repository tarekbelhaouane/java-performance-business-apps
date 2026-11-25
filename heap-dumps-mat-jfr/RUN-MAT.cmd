@echo off
setlocal
cd /d "%~dp0"
set "HERE=%CD%"
set "LOG=%HERE%\mat-run.log"
set IMG=maven:3.9-eclipse-temurin-21
echo == start %date% %time% > "%LOG%"
docker info >nul 2>&1
if errorlevel 1 ( echo Docker Desktop is not running: start it, wait for "Engine running", then run RUN-MAT.cmd again & echo == Docker not running >> "%LOG%" & pause & exit /b 1 )
echo Running, 10 to 15 minutes. Keep this window open.
docker run --rm --name norvane-mat -m 6g -v "%HERE%:/work" -w /work %IMG% bash run-mat.sh >> "%LOG%" 2>&1
if exist norvane-mat-results.zip del norvane-mat-results.zip
tar -a -c -f norvane-mat-results.zip results mat-run.log
echo == finished %date% %time% >> "%LOG%"
echo Finished. Results: norvane-mat-results.zip; the heap dumps stay in the dumps folder.
pause
