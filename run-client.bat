@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
pushd "%~dp0"
if errorlevel 1 exit /b 1

rem Keep generated files inside the project; never remove another JVM's classes.
set "GAME_TEMP_DIR=%~dp0.runtime\tmp"
if not exist "%GAME_TEMP_DIR%" mkdir "%GAME_TEMP_DIR%"
if errorlevel 1 goto build_error
set "TEMP=%GAME_TEMP_DIR%"
set "TMP=%GAME_TEMP_DIR%"
set "GAME_CLASSES_DIR=%~dp0.runtime\client-%RANDOM%-%RANDOM%"
if exist "%GAME_CLASSES_DIR%" goto build_error
mkdir "%GAME_CLASSES_DIR%"
if errorlevel 1 goto build_error

echo [1/3] Thu muc class rieng: %GAME_CLASSES_DIR%
echo [2/3] Dang bien dich source/common va source/client...
powershell -NoProfile -Command "$ErrorActionPreference = 'Stop'; $javaSources = @(Get-ChildItem -LiteralPath source/common,source/client -Filter '*.java' -File | Where-Object { $_.Name -notlike '*RegressionChecks.java' } | Select-Object -ExpandProperty FullName); if ($javaSources.Count -eq 0) { exit 1 }; $jvmTemp = '-J-Djava.io.tmpdir=' + $env:GAME_TEMP_DIR; & javac --release 17 -encoding UTF-8 -implicit:none -J-XX:-UsePerfData $jvmTemp -classpath $env:GAME_CLASSES_DIR -d $env:GAME_CLASSES_DIR $javaSources; exit $LASTEXITCODE"
if errorlevel 1 goto build_error

echo [3/3] Bien dich thanh cong.
if /I "%~1"=="--compile-only" (
    popd
    exit /b 0
)

java -XX:-UsePerfData -Dfile.encoding=UTF-8 "-Djava.io.tmpdir=%GAME_TEMP_DIR%" -cp "%GAME_CLASSES_DIR%" source.client.ClientApp
set "GAME_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%GAME_EXIT_CODE%"=="0" echo [LOI] Client dung voi ma %GAME_EXIT_CODE%.
pause
exit /b %GAME_EXIT_CODE%

:build_error
echo [LOI] Khong the tao thu muc class hoac bien dich Client. Can JDK 17+ trong PATH.
popd
if /I not "%~1"=="--compile-only" pause
exit /b 1
