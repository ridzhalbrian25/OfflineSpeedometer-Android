@echo off
echo Building Offline Speedometer Android APK...
call gradlew.bat assembleDebug
echo Build complete! APK located at: app\build\outputs\apk\debug\app-debug.apk
pause
