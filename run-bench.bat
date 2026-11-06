@echo off
title Bot Benchmark
REM Cach dung: run-bench.bat [host] [so_bot] [so_giay] [port]
REM Vi du:     run-bench.bat localhost 16 10
echo [1/2] Dang bien dich cong cu do tai...
javac -encoding UTF-8 -sourcepath . source/common/*.java source/tools/BotBench.java
if %errorlevel% neq 0 (
    echo Bien dich THAT BAI!
    pause
    exit /b
)
echo [2/2] Dang chay benchmark (can bat run-server.bat truoc)...
java -cp . source.tools.BotBench %*
pause
