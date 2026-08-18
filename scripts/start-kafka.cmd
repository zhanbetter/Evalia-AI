@echo off
setlocal EnableExtensions
title LLM Eval Platform - Kafka Launcher
cd /d "%~dp0..\llm-eval-server"

echo ============================================================
echo   LLM Eval Platform - Kafka launcher
echo   Starts Zookeeper + Kafka (Docker containers)
echo ============================================================
echo.

REM ---- 1. Check Docker ----
docker info >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Docker not running. Please start Docker Desktop first.
    pause
    exit /b 1
)
echo [1/3] Docker OK, starting containers...

REM ---- 2. docker compose v2 vs v1 ----
where docker compose >nul 2>nul
if not errorlevel 1 (
    echo       Using "docker compose"
    docker compose up -d zookeeper kafka
) else (
    echo       Using "docker-compose"
    docker-compose up -d zookeeper kafka
)
if errorlevel 1 (
    echo [ERROR] Failed to start containers. See error above.
    pause
    exit /b 1
)

REM ---- 3. Wait for port 9092 (up to 60s) ----
echo [2/3] Waiting for Kafka...
set tries=0
:wait
set /a tries+=1
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if not errorlevel 1 goto ready
if %tries% geq 30 (
    echo [ERROR] Timeout (60s). Check manually:
    echo        docker logs llm-eval-kafka --tail 50
    pause
    exit /b 1
)
timeout /t 2 /nobreak >nul
goto wait

:ready
echo [3/3] Kafka ready, listening on port 9092!
echo.
echo   Background services:
echo     mysql / redis / minio / zookeeper / kafka
echo.
echo   Logs:  docker logs llm-eval-kafka --tail 50
echo   Stop:  docker compose stop zookeeper kafka   (from llm-eval-server)
echo.
pause
