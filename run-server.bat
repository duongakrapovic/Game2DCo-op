@echo off
title Dedicated Server
echo [1/2] Dang bien dich Server...
javac -encoding UTF-8 source/common/*.java source/server/*.java
if %errorlevel% neq 0 (
    echo Bien dich Server THAT BAI!
    pause
    exit /b
)
echo [2/2] Dang chay Server...
java -cp . source.server.ServerApp
pause