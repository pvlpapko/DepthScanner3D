@echo off
setlocal EnableExtensions
set "GRADLE_VERSION=8.13"
set "BASE_DIR=%~dp0"
if defined GRADLE_USER_HOME (
  set "CACHE_ROOT=%GRADLE_USER_HOME%\depthscanner-wrapper"
) else (
  set "CACHE_ROOT=%USERPROFILE%\.gradle\depthscanner-wrapper"
)
set "DIST_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_BIN=%DIST_DIR%\bin\gradle.bat"

if not exist "%GRADLE_BIN%" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ZIP_FILE%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%'"
    if errorlevel 1 exit /b 1
  )
  if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -Force '%ZIP_FILE%' '%CACHE_ROOT%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_BIN%" -p "%BASE_DIR%" %*
exit /b %ERRORLEVEL%
