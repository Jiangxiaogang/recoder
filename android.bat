@echo off
set "ANDROID_ROOT=D:\IDE\android"
set "JAVA_HOME=%ANDROID_ROOT%\studio\2022.2\jbr"
set "ANDROID_HOME=%ANDROID_ROOT%\sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%ANDROID_ROOT%\gradle"

set "GRADLE_BIN="
for /d %%A in ("%GRADLE_USER_HOME%\wrapper\dists\gradle-*") do (
    for /d %%B in ("%%~A\*") do (
        for /d %%C in ("%%~B\gradle-*") do (
            if exist "%%~C\bin\gradle.bat" set "GRADLE_BIN=%%~C\bin"
        )
    )
)
if defined GRADLE_BIN set "PATH=%GRADLE_BIN%;%PATH%"

set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\tools;%ANDROID_HOME%\tools\bin;%ANDROID_HOME%\build-tools\33.0.2;%PATH%"

echo ============================================================
echo  JAVA_HOME        = %JAVA_HOME%
echo  ANDROID_HOME     = %ANDROID_HOME%
echo  ANDROID_SDK_ROOT = %ANDROID_SDK_ROOT%
echo  GRADLE_USER_HOME = %GRADLE_USER_HOME%
if defined GRADLE_BIN (echo  gradle           = %GRADLE_BIN%\gradle.bat) else (echo  gradle           = [not found in wrapper cache])
echo ============================================================

if "%~1"=="" (
    echo Starting Android development shell...
    cmd /k
) else (
    %*
)
