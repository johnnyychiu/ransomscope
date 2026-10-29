@echo off
setlocal
set "RANSOMSCOPE_WEB_ROOT=%~dp0"
if not exist "%RANSOMSCOPE_WEB_ROOT%index.html" (
  set "RANSOMSCOPE_WEB_ROOT=%~dp0..\web\build\dist\js\productionExecutable"
)
if not exist "%RANSOMSCOPE_WEB_ROOT%index.html" (
  echo RansomScope web build was not found. Extract the complete web ZIP first.
  echo From source, run gradlew.bat :web:jsBrowserDistribution first.
  pause
  exit /b 1
)
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -c "import sys; assert sys.version_info.major == 3" >nul 2>nul
  if not errorlevel 1 goto run_py
)
where python >nul 2>nul
if not errorlevel 1 (
  python -c "import sys; assert sys.version_info.major == 3" >nul 2>nul
  if not errorlevel 1 goto run_python
)
echo Python 3 is needed for this local demo server; nothing was installed.
echo You can watch RansomScope-demo.mp4 or use another local static HTTP server.
pause
exit /b 1

:run_py
echo RansomScope local demo: http://127.0.0.1:8766/
echo Press Ctrl+C in this window when finished. No LAN binding or persistence.
py -3 -c "import functools,http.server,webbrowser,sys; handler=functools.partial(http.server.SimpleHTTPRequestHandler,directory=sys.argv[1]); server=http.server.ThreadingHTTPServer(('127.0.0.1',8766),handler); webbrowser.open('http://127.0.0.1:8766/'); server.serve_forever()" "%RANSOMSCOPE_WEB_ROOT%"
exit /b %errorlevel%

:run_python
echo RansomScope local demo: http://127.0.0.1:8766/
echo Press Ctrl+C in this window when finished. No LAN binding or persistence.
python -c "import functools,http.server,webbrowser,sys; handler=functools.partial(http.server.SimpleHTTPRequestHandler,directory=sys.argv[1]); server=http.server.ThreadingHTTPServer(('127.0.0.1',8766),handler); webbrowser.open('http://127.0.0.1:8766/'); server.serve_forever()" "%RANSOMSCOPE_WEB_ROOT%"
exit /b %errorlevel%
