@echo off
cd /d "%~dp0"
echo.
echo  Norvane measurements - do not close this window, do not put the PC to sleep.
echo  Progress: windows-run.log (chapters 9-14), then ch14-run.log (Jersey, WebSocket).
echo.
docker info >nul 2>&1
if errorlevel 1 ( echo  Docker Desktop is not running: start it, then run this file again. & pause & exit /b 1 )
docker rm -f norvane-runner norvane-ch14 >nul 2>&1
call RUN-WINDOWS.cmd
call RUN-CH14.cmd
echo == packaging results >> windows-run.log
if exist norvane-results.zip del norvane-results.zip
tar -a -c -f norvane-results.zip results windows-run.log ch14-run.log
docker compose down >nul 2>&1
echo.
echo  Finished. Results: norvane-results.zip (in this folder).
pause
