@echo off
rem ============================================================================
rem  Stops the Docker services started by run-local.bat.
rem
rem    stop-local.bat          stop MySQL, Redis and RabbitMQ; the data is kept
rem    stop-local.bat reset    stop them AND delete all their data (fresh database next time)
rem
rem  The application itself runs in the run-local.bat window: stop it with Ctrl+C there.
rem ============================================================================
setlocal
cd /d "%~dp0"

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running, so there is nothing to stop.
    exit /b 1
)

if /i "%~1"=="reset" goto :reset

echo Stopping MySQL, Redis and RabbitMQ - data is kept...
docker compose down
exit /b %errorlevel%

:reset
echo This stops the services AND deletes all local MySQL, Redis and RabbitMQ data.
set /p "_ans=Type YES to continue: "
if /i not "%_ans%"=="YES" (
    echo Cancelled.
    exit /b 1
)
docker compose down -v
exit /b %errorlevel%
