@echo off
chcp 65001 >nul
cls

echo ==================================================
echo       DUNGEON CO-OP - GAME CLIENT
echo ==================================================

echo [1/3] Xoa file .class cu...
for /r source %%f in (*.class) do del /q "%%f" >nul 2>&1

echo [2/3] Bien dich tat ca source...
javac -encoding UTF-8 source/common/*.java source/server/ServerGame.java source/client/*.java
if %errorlevel% neq 0 (
    echo.
    echo ==================================================
    echo [LOI] Bien dich THAT BAI! Kiem tra lai code.
    echo ==================================================
    pause
    exit /b
)

echo [3/3] Khoi chay Client...
echo --------------------------------------------------
java -Dfile.encoding=UTF-8 -cp . source.client.ClientApp

pause
