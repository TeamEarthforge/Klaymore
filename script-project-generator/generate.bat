@echo off
chcp 65001 >nul
title Klaymore Script Project Generator (GTNH)

echo.
echo ================================================================
echo   Klaymore 脚本项目生成器  (GTNH Gradle / rfg.deobf)
echo ================================================================
echo.

cd /d "%~dp0"

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python 环境！
    echo.
    echo 请安装 Python 3.7+：https://www.python.org/downloads/
    echo 安装时必须勾选 "Add Python to PATH"
    echo.
    pause
    exit /b 1
)

python "%~dp0generate_project.py" %*

echo.
pause
