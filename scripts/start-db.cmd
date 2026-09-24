@echo off
setlocal
if not defined DISTRIBUIDORA_PG_HOME set "DISTRIBUIDORA_PG_HOME=%USERPROFILE%\.pgsql\pgsql"
if not defined DISTRIBUIDORA_PGDATA set "DISTRIBUIDORA_PGDATA=%USERPROFILE%\.pgsql\data"
if not exist "%DISTRIBUIDORA_PG_HOME%\bin\postgres.exe" (
  echo No se encontro postgres.exe en "%DISTRIBUIDORA_PG_HOME%\bin".
  exit /b 1
)
echo Iniciando PostgreSQL Portable...
start /b "" "%DISTRIBUIDORA_PG_HOME%\bin\postgres.exe" -D "%DISTRIBUIDORA_PGDATA%"
if errorlevel 1 exit /b 1
echo PostgreSQL iniciado usando "%DISTRIBUIDORA_PGDATA%".
