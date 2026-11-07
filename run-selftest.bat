@echo off
title Network Self Test
REM Cach dung: run-selftest.bat [host] [port]   (can bat run-server.bat truoc)
echo [1/2] Dang bien dich bo kiem thu mang...
javac -encoding UTF-8 -sourcepath . source/common/*.java source/tools/NetSelfTest.java
if %errorlevel% neq 0 (
    echo Bien dich THAT BAI!
    pause
    exit /b
)
echo [2/2] Dang chay kiem thu...
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp . source.tools.NetSelfTest %*
pause
