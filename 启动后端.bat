@echo off
title 文栈博客 - 后端 (8021)
cd /d "%~dp0"

rem ===== 前置检查 =====
if not exist ".env" (
  echo [!] 未找到 .env 配置文件，后端无法启动。请从旧目录复制或参照 .env.example 生成。
  pause
  exit /b 1
)
if not exist "tools\apache-maven-3.9.16\bin\mvn.cmd" (
  echo [!] 未找到内置 Maven（tools\apache-maven-3.9.16），请勿单独移走 tools 目录。
  pause
  exit /b 1
)
netstat -ano | findstr /C:":8021 " | findstr "LISTENING" >nul
if %errorlevel%==0 (
  echo [!] 端口 8021 已被占用：可能已有后端实例在运行，请勿重复启动。
  pause
  exit /b 1
)

rem ===== Java 环境 =====
if not defined JAVA_HOME (
  if exist "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" (
    set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
  ) else (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
  )
)
set "PATH=%JAVA_HOME%\bin;%~dp0tools\apache-maven-3.9.16\bin;%PATH%"
echo [i] Java: %JAVA_HOME%
echo [i] 启动后端（local 配置，端口 8021）... 按 Ctrl+C 可停止
call mvn spring-boot:run -Dspring-boot.run.profiles=local
if errorlevel 1 (
  echo.
  echo [!] 后端启动失败，请检查上方日志（常见原因：MySQL/Redis 未启动、.env 配置有误）。
  pause
)
