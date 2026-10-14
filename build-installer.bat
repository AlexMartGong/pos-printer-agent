@echo off
REM ============================================
REM Script para crear instalador de Windows
REM Requiere: JDK 17+ con jpackage
REM ============================================

echo === POS Printer Agent - Build Installer ===
echo.

REM Verificar que JAVA_HOME este configurado
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME no esta configurado
    echo Configura JAVA_HOME apuntando a tu JDK 17+
    exit /b 1
)

REM Verificar version de Java
"%JAVA_HOME%\bin\java" -version 2>&1 | findstr /i "version" > nul
if errorlevel 1 (
    echo ERROR: No se puede ejecutar Java
    exit /b 1
)

REM Usar Maven wrapper si existe, sino mvn del sistema
set MVN_CMD=
if exist "mvnw.cmd" (
    set MVN_CMD=mvnw.cmd
) else (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Maven no esta instalado y no hay mvnw.cmd
        exit /b 1
    )
    set MVN_CMD=mvn
)

echo [1/3] Compilando proyecto con Maven...
call %MVN_CMD% clean package -DskipTests -q
if errorlevel 1 (
    echo ERROR: Fallo la compilacion
    exit /b 1
)

echo [2/3] Preparando archivos...
REM Crear directorio temporal para jpackage
if exist "target\installer" rmdir /s /q "target\installer"
mkdir "target\installer\input"

REM Copiar JAR y config
copy "target\pos-printer-agent.jar" "target\installer\input\" > nul
copy "config.properties" "target\installer\input\" > nul

echo [3/3] Generando instalador con jpackage...
"%JAVA_HOME%\bin\jpackage" ^
    --input "target\installer\input" ^
    --name "POS Printer Agent" ^
    --main-jar pos-printer-agent.jar ^
    --main-class com.pasadita.pos.POSPrinterAgent ^
    --type exe ^
    --dest "target\installer" ^
    --app-version "1.0.0" ^
    --vendor "La Pasadita" ^
    --description "Agente de impresion para terminales POS" ^
    --win-dir-chooser ^
    --win-shortcut ^
    --win-menu ^
    --win-menu-group "La Pasadita" ^
    --java-options "-Xmx256m" ^
    --java-options "--add-opens=java.desktop/sun.print=ALL-UNNAMED"

if errorlevel 1 (
    echo ERROR: Fallo jpackage
    exit /b 1
)

echo.
echo === Instalador creado exitosamente ===
echo Ubicacion: target\installer\POS Printer Agent-1.0.0.exe
echo.
pause
