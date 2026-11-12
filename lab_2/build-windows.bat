@echo off
setlocal
cd /d "%~dp0"
where jlink >nul 2>nul
if errorlevel 1 goto missing
where jpackage >nul 2>nul
if errorlevel 1 goto missing
if exist release\RasterLab (
  echo release\RasterLab already exists. Move it before building a new version.
  pause
  exit /b 1
)
call "%~dp0mvnw.cmd" clean verify javafx:jlink
if errorlevel 1 goto failed
jpackage --type app-image --name RasterLab --app-version 1.0.0 --vendor "Vladimir Matsukov" --runtime-image target\raster-runtime --module ru.matsukov.raster/ru.matsukov.raster.RasterApp --dest release
if errorlevel 1 goto failed
xcopy /E /I /Y samples release\RasterLab\samples >nul
copy /Y README.md release\RasterLab\README.md >nul
echo Built: release\RasterLab\RasterLab.exe
echo Distribute the ENTIRE release\RasterLab folder, not the exe alone.
echo No separate Java installation is needed on the target Windows x64 PC.
pause
exit /b 0
:missing
echo Install JDK 17 x64 with jpackage. Add their bin folders to PATH.
pause
exit /b 1
:failed
echo Build failed. Read the error above.
pause
exit /b 1
