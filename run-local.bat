@echo off
rem ============================================================================
rem  URL Shortener - one-step local run (Windows).
rem
rem    run-local.bat           start MySQL/Redis/RabbitMQ in Docker, then the app
rem    run-local.bat noopen    same, but do not open Swagger UI in the browser
rem
rem  Needs: Docker Desktop (running) and JDK 21. No Maven install needed (mvnw.cmd).
rem  Ports are chosen automatically: containers that are already running are
rem  reused, and anything else that is busy (3306, 6379, 5672, 15672, 8080) is
rem  moved to the next free port, so this does not clash with software you have.
rem  Stop with Ctrl+C here, then run stop-local.bat.
rem ============================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title URL Shortener - local run

echo.
echo === URL Shortener: local run ===
echo.

rem ---- 1. Docker ---------------------------------------------------------------
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not installed or not running. Start Docker Desktop, wait until it says "running", then run this again.
    goto :fail
)
docker compose version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Compose v2 is required - the "docker compose" command. Update Docker Desktop.
    goto :fail
)

rem ---- 2. JDK 21 ---------------------------------------------------------------
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found. Install JDK 21 and set JAVA_HOME to it, for example:
    echo         setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21..."
    echo         then open a NEW terminal.
    goto :fail
)
"%JAVA_CMD%" -version 2>&1 | findstr /R /C:"version .21" >nul
if errorlevel 1 (
    echo [ERROR] JDK 21 is required. Java found:
    "%JAVA_CMD%" -version 2>&1 | findstr /C:"version"
    echo         Install JDK 21 and point JAVA_HOME at it, then open a NEW terminal.
    goto :fail
)

rem ---- 3. Ports ----------------------------------------------------------------
echo Choosing ports - reusing running containers, otherwise the first free port...
call :resolve MYSQL_PORT mysql 3306 3306
if errorlevel 1 goto :fail
call :resolve REDIS_PORT redis 6379 6379
if errorlevel 1 goto :fail
call :resolve RABBIT_PORT rabbitmq 5672 5672
if errorlevel 1 goto :fail
call :resolve RABBIT_MGMT_PORT rabbitmq 15672 15672
if errorlevel 1 goto :fail
set "_start=8080"
if defined SERVER_PORT set "_start=!SERVER_PORT!"
call :findfree SERVER_PORT !_start!
if errorlevel 1 goto :fail
echo   app             -^> !SERVER_PORT!

rem ---- 4. Backing services -----------------------------------------------------
echo.
echo Starting MySQL, Redis and RabbitMQ in Docker - the first run pulls images...
docker compose up -d --wait mysql redis rabbitmq
if errorlevel 1 (
    echo [ERROR] "docker compose up" failed. Run "docker compose ps" and "docker compose logs" to see why.
    goto :fail
)

set /a _try=0
:wait_rabbit
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping >nul 2>&1
if not errorlevel 1 goto :rabbit_ready
set /a _try+=1
if !_try! GEQ 30 (
    echo [WARN] RabbitMQ did not confirm ready after 60 s - continuing anyway.
    goto :rabbit_ready
)
timeout /t 2 /nobreak >nul
goto :wait_rabbit
:rabbit_ready

rem ---- 5. App configuration - read by application.yml --------------------------
set "DB_URL=jdbc:mysql://localhost:!MYSQL_PORT!/urlshortener?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=3000"
set "PUBLIC_BASE_URL=http://localhost:!SERVER_PORT!"
rem REDIS_PORT, RABBIT_PORT and SERVER_PORT are already set above and are read by the app as-is.

echo.
echo ------------------------------------------------------------------------
echo   App          http://localhost:!SERVER_PORT!
echo   Swagger UI   http://localhost:!SERVER_PORT!/swagger-ui.html
echo   Health       http://localhost:!SERVER_PORT!/actuator/health
echo   RabbitMQ UI  http://localhost:!RABBIT_MGMT_PORT!   user guest / password guest
echo   MySQL        localhost:!MYSQL_PORT!   database urlshortener, user urlshortener / urlshortener
echo ------------------------------------------------------------------------
echo   Try it:  see "Manual testing with curl" in README.md
echo   Stop:    Ctrl+C in this window, then run stop-local.bat
echo.
echo Starting the application - the first run downloads dependencies and can take a few minutes...
echo.

rem Open Swagger UI once the app reports healthy. Runs in a separate minimised window; failure here is harmless.
if /i not "%~1"=="noopen" (
    start "" /min powershell -NoProfile -Command "for($i=0;$i -lt 120;$i++){try{Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://localhost:!SERVER_PORT!/actuator/health | Out-Null; Start-Process http://localhost:!SERVER_PORT!/swagger-ui.html; break}catch{Start-Sleep 3}}"
)

call mvnw.cmd -pl url-shortener-service spring-boot:run
if errorlevel 1 (
    echo.
    echo [ERROR] The application exited with an error. See the log above.
    echo         Common causes: wrong JDK ^(need 21^), a port taken after the check, or Docker services stopped.
    goto :fail
)
echo.
echo Application stopped. Run stop-local.bat to stop MySQL, Redis and RabbitMQ.
endlocal
exit /b 0

:fail
echo.
pause
endlocal
exit /b 1

rem ---------------------------------------------------------------------------
rem  :resolve VAR SERVICE CONTAINER_PORT DEFAULT_PORT
rem  Reuse the host port of an already-running compose service, else find a free one.
rem ---------------------------------------------------------------------------
:resolve
set "_line="
set "_found="
for /f "delims=" %%l in ('docker compose port %~2 %~3 2^>nul') do set "_line=%%l"
if defined _line (
    set "_x=!_line::= !"
    for %%a in (!_x!) do set "_found=%%a"
)
if "!_found!"=="0" set "_found="
if defined _found (
    set "%~1=!_found!"
    echo   %~2 %~3 -^> !_found! ^(already running^)
    exit /b 0
)
if defined %~1 (set "_start=!%~1!") else set "_start=%~4"
call :findfree %~1 !_start!
if errorlevel 1 exit /b 1
echo   %~2 %~3 -^> !%~1!
exit /b 0

rem ---------------------------------------------------------------------------
rem  :findfree VAR START_PORT   - first TCP port >= START_PORT nobody is listening on
rem ---------------------------------------------------------------------------
:findfree
set "_p=%~2"
set /a _max=_p+50
:ff_loop
netstat -ano | findstr /R /C:":!_p! .*LISTENING" >nul
if errorlevel 1 goto :ff_done
set /a _p+=1
if !_p! GTR !_max! (
    echo [ERROR] No free port found starting at %~2.
    exit /b 1
)
goto :ff_loop
:ff_done
set "%~1=!_p!"
exit /b 0
