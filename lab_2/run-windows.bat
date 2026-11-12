@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 goto missing
call "%~dp0mvnw.cmd" javafx:run
if errorlevel 1 pause
exit /b
:missing
echo Install JDK 17 x64, then add their bin folders to PATH.
echo Or open pom.xml in IntelliJ IDEA and run Maven: javafx:run.
pause
exit /b 1
