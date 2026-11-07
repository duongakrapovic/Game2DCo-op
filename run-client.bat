@echo off
chcp 65001 >nul
cls

echo [1/4] Xoa sach file .class cu...
for /r source %%f in (*.class) do del /q "%%f" >nul 2>&1

echo [2/4] Bien dich Common...
javac -encoding UTF-8 source/common/*.java
if errorlevel 1 (
    echo [LOI] Bien dich Common that bai!
    pause
    exit /b
)

echo [3/4] Bien dich Client...
javac -encoding UTF-8 -cp . source/client/*.java
if errorlevel 1 (
    echo [LOI] Bien dich Client that bai!
    pause
    exit /b
)

echo [4/4] Khoi chay Client...
java -Dfile.encoding=UTF-8 -cp . source.client.ClientApp

pause