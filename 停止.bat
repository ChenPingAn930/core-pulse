@echo off
chcp 65001 >nul
title CorePulse 停止器
cd /d "%~dp0"

echo ============================================
echo   CorePulse - 停止服务
echo ============================================
echo.

echo 正在停止所有 CorePulse 容器...
docker-compose down

echo.
echo 已停止。数据已保存在 Docker 卷中，下次启动不会丢失。
echo.
pause