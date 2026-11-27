@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$startup = [Environment]::GetFolderPath('Startup');" ^
  "$shortcutPath = Join-Path $startup 'Distribuidora Impresion.lnk';" ^
  "$shell = New-Object -ComObject WScript.Shell;" ^
  "$shortcut = $shell.CreateShortcut($shortcutPath);" ^
  "$shortcut.TargetPath = 'powershell.exe';" ^
  "$shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"' + (Join-Path '%SCRIPT_DIR%' 'print-agent.ps1') + '\"';" ^
  "$shortcut.WorkingDirectory = '%SCRIPT_DIR%';" ^
  "$shortcut.WindowStyle = 7;" ^
  "$shortcut.Description = 'Servicio local de impresion directa para Distribuidora';" ^
  "$shortcut.Save();" ^
  "Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File',(Join-Path '%SCRIPT_DIR%' 'print-agent.ps1'));" ^
  "Write-Host 'Listo: el servicio quedo iniciado y configurado para arrancar con Windows.'"
pause
