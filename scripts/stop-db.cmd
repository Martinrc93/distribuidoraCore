@echo off
setlocal
if not defined DISTRIBUIDORA_PG_HOME set "DISTRIBUIDORA_PG_HOME=%USERPROFILE%\.pgsql\pgsql"
if not defined DISTRIBUIDORA_PGDATA set "DISTRIBUIDORA_PGDATA=%USERPROFILE%\.pgsql\data"
if not exist "%DISTRIBUIDORA_PG_HOME%\bin\pg_ctl.exe" (
  echo No se encontro pg_ctl.exe en "%DISTRIBUIDORA_PG_HOME%\bin".
  exit /b 1
)
echo Deteniendo PostgreSQL...
"%DISTRIBUIDORA_PG_HOME%\bin\pg_ctl.exe" -D "%DISTRIBUIDORA_PGDATA%" stop
