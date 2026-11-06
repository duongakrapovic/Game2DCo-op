@echo off
title Dedicated Server (Hybrid TCP/UDP 60Hz)
chcp 65001 >nul
cls

echo ==================================================
echo      DUNGEON CO-OP - DEDICATED SERVER SYSTEM
echo ==================================================

echo [1/3] Dang don dep file .class cu cua Common ^& Server...
del /s /q source\common\*.class source\server\*.class >nul 2>&1

echo [2/3] Dang bien dich DUY NHAT source/common va source/server...
javac -encoding UTF-8 source/common/*.java source/server/*.java
if %errorlevel% neq 0 (
    echo.
    echo ==================================================
    echo [LOI] Bien dich THAT BAI! Kiem tra lai code.
    echo ==================================================
    pause
    exit /b
)

echo [3/3] Bien dich thanh cong! Dang khoi chay Dedicated Server...
echo --------------------------------------------------
java -Dfile.encoding=UTF-8 -cp . source.server.ServerApp

if %errorlevel% neq 0 (
    echo.
    echo [THONG BAO] Server da dung hoac gap loi khi chay.
)
pause