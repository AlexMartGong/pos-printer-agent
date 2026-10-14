#!/bin/bash
# ============================================
# Script para crear instalador
# Linux: genera .deb
# Windows: requiere ejecutar build-installer.bat en Windows
# ============================================

set -e

echo "=== POS Printer Agent - Build Installer ==="
echo ""

# Verificar JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME no está configurado"
    exit 1
fi

# Verificar jpackage
if ! command -v "$JAVA_HOME/bin/jpackage" &> /dev/null; then
    echo "ERROR: jpackage no encontrado. Requiere JDK 14+"
    exit 1
fi

echo "[1/3] Compilando proyecto con Maven..."
mvn clean package -DskipTests -q

echo "[2/3] Preparando archivos..."
rm -rf target/installer
mkdir -p target/installer/input
cp target/pos-printer-agent.jar target/installer/input/
cp config.properties target/installer/input/

echo "[3/3] Generando instalador..."

# Detectar SO
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    INSTALLER_TYPE="deb"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    INSTALLER_TYPE="dmg"
else
    echo "SO no soportado para generar instalador"
    exit 1
fi

"$JAVA_HOME/bin/jpackage" \
    --input "target/installer/input" \
    --name "pos-printer-agent" \
    --main-jar pos-printer-agent.jar \
    --main-class com.pasadita.pos.POSPrinterAgent \
    --type "$INSTALLER_TYPE" \
    --dest "target/installer" \
    --app-version "1.0.0" \
    --vendor "La Pasadita" \
    --description "Agente de impresion para terminales POS" \
    --java-options "-Xmx256m" \
    --java-options "--add-opens=java.desktop/sun.print=ALL-UNNAMED"

echo ""
echo "=== Instalador creado exitosamente ==="
echo "Ubicación: target/installer/"
ls -la target/installer/*.$INSTALLER_TYPE 2>/dev/null || echo "Archivo generado en target/installer/"
