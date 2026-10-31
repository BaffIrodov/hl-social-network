@echo off
setlocal
set "BENCHMARK_JAVA_HOME="
if defined JAVA_HOME call :checkJava "%JAVA_HOME%"
if not defined BENCHMARK_JAVA_HOME for /d %%J in ("%USERPROFILE%\.jdks\*") do call :checkJava "%%~fJ"
if not defined BENCHMARK_JAVA_HOME (
  echo JDK 21 not found. Set JAVA_HOME to your JDK 21 directory.
  exit /b 1
)
set "JAVA_HOME=%BENCHMARK_JAVA_HOME%"
pushd "%~dp0"
call "%~dp0mvnw.cmd" -B -ntp gatling:test %*
set "BENCHMARK_EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %BENCHMARK_EXIT_CODE%

:checkJava
if defined BENCHMARK_JAVA_HOME exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
if not exist "%~1\release" exit /b 0
findstr /r /c:"JAVA_VERSION=.21[.]" "%~1\release" >nul
if not errorlevel 1 set "BENCHMARK_JAVA_HOME=%~1"
exit /b 0
