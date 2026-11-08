@echo off
setlocal
set "APP_HOME=%~dp0"
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  echo First run SETUP-FIRST.bat to install the official Gradle wrapper.
  exit /b 1
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Xmx64m -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
