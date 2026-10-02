@echo off
rem Optional local Node 20 runtime; otherwise use Node from PATH.
setlocal
if exist "%~dp0node20\node.exe" set "PATH=%~dp0node20;%PATH%"
where node >nul 2>nul
if errorlevel 1 (
  echo Install Node.js 20 and add it to PATH.
  exit /b 1
)
node -e "if (process.versions.node.split('.')[0] !== '20') { console.error('Node.js 20 is required'); process.exit(1); }"
if errorlevel 1 exit /b 1
if "%~1"=="" (
  node --version
  exit /b 0
)
%*
