@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$startup = [Environment]::GetFolderPath('Startup');" ^
  "$shortcutPath = Join-Path $startup 'Distribuidora Impresion.lnk';" ^
  "if (Test-Path -LiteralPath $shortcutPath) { Remove-Item -LiteralPath $shortcutPath -Force };" ^
  "Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'powershell*' -and $_.CommandLine -like '*print-agent.ps1*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue };" ^
  "Write-Host 'Listo: se detuvo el servicio y se quito del inicio de Windows.'"
pause
