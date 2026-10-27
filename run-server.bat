@echo off
title Server - Game 2D Co-op
echo [1/2] Dang bien dich Server...
javac -encoding UTF-8 -cp "source/common/src;source/server/src" source/server/src/Main.java

if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Bien dich Server thất bại!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/2] Dang chay Server...
java -cp "source/common/src;source/server/src" Main
pause