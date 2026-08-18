@echo off
setlocal EnableExtensions
title LLM Eval Platform - Dev Env Launcher
cd /d "%~dp0.."

echo ============================================================
echo   LLM Eval Platform - one-click dev launcher
echo   - Kafka + Zookeeper   (Docker containers)
echo   - Backend Spring Boot (localhost:8080)
echo   - Frontend Vite       (localhost:3000)
echo ============================================================
echo.

set "SERVER=%~dp0..\llm-eval-server"
set "FRONT=%~dp0..\llm-eval-ui"

REM ---------- 0. Environment check ----------
echo [0/3] Checking environment...
where java >nul 2>nul
if errorlevel 1 ( echo     [ERROR] Java not found. Install JDK 17 and add it to PATH. & pause & exit /b 1 )
where mvn >nul 2>nul
if errorlevel 1 ( echo     [WARN] Maven not found. Will reuse an existing jar (may be stale). )
where npm >nul 2>nul
if errorlevel 1 ( echo     [ERROR] Node/npm not found. Install Node 18+. & pause & exit /b 1 )
echo     Environment OK
echo.

REM ---------- 1. Kafka ----------
echo [1/3] Checking Kafka...
call :ensure_kafka

REM ---------- 2. Backend ----------
echo [2/3] Starting backend...
call :start_backend

REM ---------- 3. Frontend ----------
echo [3/3] Starting frontend...
call :start_frontend

echo.
echo ============================================================
echo   Waiting for services (first backend build may take 1-2 min)
echo ============================================================

call :wait_port 8080 240 backend
call :wait_port 3000 120 frontend

echo.
echo ============================================================
echo   All ready!
echo     Frontend: http://localhost:3000
echo     Backend:  http://localhost:8080
echo   Closing any of the spawned windows stops that service.
echo ============================================================
start http://localhost:3000
pause
exit /b 0

REM ============ ensure Kafka runs ============
:ensure_kafka
where docker >nul 2>nul
if errorlevel 1 (
    echo     [INFO] Docker not installed. Skipping Kafka (eval tasks will not run).
    exit /b 0
)
docker ps --format "{{.Names}}" 2>nul | findstr /x "llm-eval-kafka" >nul
if not errorlevel 1 (
    echo     Kafka already running
    exit /b 0
)
echo     Starting Zookeeper + Kafka ...
pushd "%SERVER%"
( docker compose up -d zookeeper kafka >nul 2>&1 ) || ( docker-compose up -d zookeeper kafka >nul 2>&1 )
popd
echo     Waiting for Kafka (up to 60s)...
set tries=0
:kwait
set /a tries+=1
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if not errorlevel 1 (
    echo     Kafka ready (localhost:9092)
    exit /b 0
)
if %tries% geq 15 (
    echo     [WARN] Kafka startup timeout. Run scripts\start-kafka.cmd to debug.
    exit /b 0
)
timeout /t 4 /nobreak >nul
goto kwait

REM ============ start backend ============
:start_backend
where mvn >nul 2>nul
if errorlevel 1 (
    if not exist "%SERVER%\llm-eval-web\target\llm-eval-web-1.0.0-SNAPSHOT.jar" (
        echo     [ERROR] No existing jar and Maven not found. Build once in IDEA first.
        pause
        exit /b 1
    )
    echo     [WARN] Using existing jar (may be stale)
    pushd "%SERVER%"
    start "llm-eval-backend" cmd /k "java -jar llm-eval-web\target\llm-eval-web-1.0.0-SNAPSHOT.jar"
    popd
    exit /b 0
)
echo     Building and starting backend in a new window...
pushd "%SERVER%"
start "llm-eval-backend" cmd /k "mvn -B clean package -DskipTests && java -jar llm-eval-web\target\llm-eval-web-1.0.0-SNAPSHOT.jar"
popd
exit /b 0

REM ============ start frontend ============
:start_frontend
if not exist "%FRONT%\node_modules" (
    echo     First run: npm install in a new window...
    pushd "%FRONT%"
    start "llm-eval-frontend" cmd /k "npm install && npm run dev"
    popd
    exit /b 0
)
pushd "%FRONT%"
start "llm-eval-frontend" cmd /k "npm run dev"
popd
exit /b 0

REM ============ wait for port: %1=port %2=timeout(s) %3=name ============
:wait_port
set tries=0
:wp
set /a tries+=1
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %1 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if not errorlevel 1 (
    echo     [OK] %3 is listening on port %1
    exit /b 0
)
if %tries% geq %2 (
    echo     [WARN] %3 startup timeout (%2s). Check its window for logs.
    exit /b 0
)
timeout /t 1 /nobreak >nul
goto wp
