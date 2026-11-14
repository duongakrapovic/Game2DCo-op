@echo off
title Game Client
chcp 65001 >nul
cls

echo ==================================================
echo       DUNGEON CO-OP - GAME CLIENT
echo ==================================================

echo [1/3] Don dep file .class cu...
for /r source %%f in (*.class) do del /q "%%f" >nul 2>&1

echo [2/3] Bien dich Client...
javac -J-XX:+UseSerialGC -J-Xss256k -J-Xmx128m -J-Xms16m -encoding UTF-8 source/common/*.java source/server/ServerGame.java source/client/*.java
if %errorlevel% neq 0 (
    echo.
    echo ==================================================
    echo [LOI] Bien dich THAT BAI! Kiem tra lai code.
    echo ==================================================
    pause
    exit /b
)

echo [3/3] Dang khoi chay Game Client...
echo --------------------------------------------------
java -XX:+UseSerialGC -Xss256k -Xmx256m -Xms32m -Dfile.encoding=UTF-8 -cp . source.client.ClientApp

pause
