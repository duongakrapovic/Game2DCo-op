@echo off
title Game Client
echo [1/2] Dang bien dich Client...
javac -encoding UTF-8 source/common/*.java source/client/*.java
if %errorlevel% neq 0 (
    echo Bien dich Client THAT BAI!
    pause
    exit /b
)
echo [2/2] Dang chay Client...
java -cp . source.client.ClientApp
pause