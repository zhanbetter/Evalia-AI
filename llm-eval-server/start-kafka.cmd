@echo off
setlocal EnableExtensions
chcp 936 >nul
cd /d "%~dp0"

echo ============================================================
echo   Evalia-AI - Kafka 启动脚本
echo   将启动 Zookeeper + Kafka (Docker 容器)
echo ============================================================
echo.

REM ---- 1. 检查 Docker 是否在运行 ----
docker info >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Docker。请先打开 Docker Desktop 并等待它就绪。
    pause
    exit /b 1
)
echo [1/3] Docker 正常，开始启动容器...

REM ---- 2. 兼容 docker compose v2 和 docker-compose v1 ----
where docker compose >nul 2>nul
if not errorlevel 1 (
    echo       使用 "docker compose"
    docker compose up -d zookeeper kafka
) else (
    echo       使用 "docker-compose"
    docker-compose up -d zookeeper kafka
)
if errorlevel 1 (
    echo [错误] 容器启动失败，请查看上面的报错信息。
    pause
    exit /b 1
)

REM ---- 3. 等待 9092 端口监听（最多 60 秒）----
echo [2/3] 等待 Kafka 就绪...
set tries=0
:wait
set /a tries+=1
powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 9092 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if not errorlevel 1 goto ready
if %tries% geq 30 (
    echo [错误] 等待超时(60秒)。请手动检查:
    echo       docker logs llm-eval-kafka --tail 50
    pause
    exit /b 1
)
timeout /t 2 /nobreak >nul
goto wait

:ready
echo [3/3] Kafka 已就绪，监听在 9092 端口！
echo.
echo   后续步骤:
echo     1. 重启后端服务 (IDEA 中重新运行 LlmEvalApplication)
echo     2. 前端 npm run dev 正常访问
echo     3. 到「评测任务」页新建任务或重跑任务试试
echo.
echo   查看容器日志:  docker logs llm-eval-kafka --tail 50
echo   停止容器:      docker compose stop zookeeper kafka   (本目录下)
echo.
pause