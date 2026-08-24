@echo off
chcp 65001 >nul
title CorePulse 启动器
cd /d "%~dp0"

echo ============================================
echo   CorePulse 电脑维修助手 - 启动器
echo ============================================
echo.

REM 服务器下载地址（镜像 tar 包）
set SERVER=http://175.24.172.13:8088
set MYSQL_TAR=mysql/mysql.tar
set REDIS_TAR=redis/redis.tar
set COREPULSE_TAR=corepulse/corepulse.tar

REM 检查 .env 是否存在
if not exist ".env" (
    echo [错误] 未找到 .env 配置文件！
    echo.
    echo 请先执行以下操作：
    echo   1. 复制 .env.example 为 .env
    echo   2. 用记事本打开 .env，填写你的 API Key
    echo.
    echo 复制命令：copy .env.example .env
    echo.
    pause
    exit /b 1
)

REM 检查 Docker 是否安装
docker --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Docker！
    echo.
    echo 请先安装 Docker Desktop：https://www.docker.com/products/docker-desktop/
    echo 安装后请启动 Docker Desktop，再运行本脚本。
    echo.
    pause
    exit /b 1
)

REM 检查 Docker 是否在运行
docker info >nul 2>&1
if errorlevel 1 (
    echo [错误] Docker 未启动，请先打开 Docker Desktop 并等待其就绪。
    echo.
    pause
    exit /b 1
)

REM 检查并下载 MySQL 镜像
docker image inspect mysql:latest >nul 2>&1
if errorlevel 1 (
    echo [1/5] 正在下载 MySQL 镜像（约 500MB，请耐心等待）...
    curl -L -o mysql.tar %SERVER%/%MYSQL_TAR%
    if errorlevel 1 (
        echo [错误] MySQL 镜像下载失败，请检查网络后重试。
        pause
        exit /b 1
    )
    echo 正在导入 MySQL 镜像...
    docker load -i mysql.tar
    del mysql.tar
) else (
    echo [1/5] MySQL 镜像已存在，跳过下载。
)

REM 检查并下载 Redis 镜像
docker image inspect redis:latest >nul 2>&1
if errorlevel 1 (
    echo [2/5] 正在下载 Redis 镜像（约 110MB）...
    curl -L -o redis.tar %SERVER%/%REDIS_TAR%
    if errorlevel 1 (
        echo [错误] Redis 镜像下载失败，请检查网络后重试。
        pause
        exit /b 1
    )
    echo 正在导入 Redis 镜像...
    docker load -i redis.tar
    del redis.tar
) else (
    echo [2/5] Redis 镜像已存在，跳过下载。
)

REM 检查并下载 CorePulse 镜像
docker image inspect corepulse:latest >nul 2>&1
if errorlevel 1 (
    echo [3/5] 正在下载 CorePulse 镜像（约 230MB）...
    curl -L -o corepulse.tar %SERVER%/%COREPULSE_TAR%
    if errorlevel 1 (
        echo [错误] CorePulse 镜像下载失败，请检查网络后重试。
        pause
        exit /b 1
    )
    echo 正在导入 CorePulse 镜像...
    docker load -i corepulse.tar
    del corepulse.tar
) else (
    echo [3/5] CorePulse 镜像已存在，跳过下载。
)

echo.
echo [4/5] 正在启动容器（首次启动需初始化数据库，请耐心等待）...
docker-compose up -d
if errorlevel 1 (
    echo.
    echo [错误] 启动失败，请检查上方日志。
    pause
    exit /b 1
)

echo.
echo [5/5] 等待服务就绪...
timeout /t 8 /nobreak >nul

echo 启动完成！
echo 正在打开浏览器：http://localhost:927
echo.
start http://localhost:927

echo 提示：如需停止服务，请运行 停止.bat
echo.
pause