@echo off
title Client - Game 2D Co-op
echo [1/2] Dang bien dich Client...
javac -encoding UTF-8 -cp "source/common/src;source/client/src" source/client/src/Main.java

if %ERRORLEVEL% NEQ 0 (
    echo [LOI] Bien dich Client thất bại!
    pause
    exit /b %ERRORLEVEL%
)

echo [2/2] Dang chay Client...
java -cp "source/common/src;source/client/src" Main
pause