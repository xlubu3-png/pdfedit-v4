# Starts the backend (Spring Boot, :19000) and the frontend dev server (Vite, :5173, which
# proxies /api to the backend) each in its own PowerShell window.
$root = $PSScriptRoot

Start-Process powershell -ArgumentList '-NoExit', '-Command', "`$env:PDFEDIT_NO_BROWSER='true'; cd '$root\backend'; .\gradlew.bat bootRun"
Start-Process powershell -ArgumentList '-NoExit', '-Command', "cd '$root\frontend'; npm run dev"

Write-Host "UI (hot reload) -> http://localhost:5173"
Write-Host "backend         -> http://localhost:19000/api/v1/pdf/health"
