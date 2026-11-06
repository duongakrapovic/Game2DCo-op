@echo off
title Dungeon Co-op Game Client (Hybrid TCP/UDP)
chcp 65001 >nul
cls

echo ==================================================
echo      DUNGEON CO-OP - GAME CLIENT SYSTEM
echo ==================================================

echo [1/3] Dang don dep file .class cu cua Common ^& Client...
del /s /q source\common\*.class source\client\*.class >nul 2>&1

echo [2/3] Dang bien dich Engine Core ^& Client...
javac -encoding UTF-8 source/common/*.java source/client/*.java
if %errorlevel% neq 0 (
    echo.
    echo ==================================================
    echo [LOI] Bien dich Client THAT BAI! Kiem tra lai code.
    echo ==================================================
    pause
    exit /b
)

echo [3/3] Bien dich thanh cong! Dang khoi chay Client...
echo --------------------------------------------------
java -Dfile.encoding=UTF-8 -cp . source.client.ClientApp

if %errorlevel% neq 0 (
    echo.
    echo [THONG BAO] Game Client da dong hoac gap loi khi chay.
)
pause