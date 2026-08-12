#!/usr/bin/env bash
# Pipeline completo das métricas estáticas do módulo :wear.
#
#   bash tools/metrics/run.sh
#
# Requer JAVA_HOME (ou java/javap no PATH) apontando para um JDK 17+ e o
# detekt-cli 1.23.8, baixado na primeira execução para tools/metrics/.bin/.
# Os CSVs vão para tools/metrics/resultados/ e são a fonte dos números das
# tabelas do capítulo 6 — nenhum valor do texto é digitado à mão.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RAIZ"

DETEKT_VERSAO=1.23.8
BIN="tools/metrics/.bin"
JAR="$BIN/detekt-cli-$DETEKT_VERSAO-all.jar"
SAIDA="tools/metrics/resultados"
CLASSES="wear/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
export JAVAP="${JAVA_HOME:+$JAVA_HOME/bin/}javap"

mkdir -p "$BIN" "$SAIDA"

if [ ! -f "$JAR" ]; then
  echo "== baixando detekt-cli $DETEKT_VERSAO"
  curl -sSL -o "$JAR" \
    "https://github.com/detekt/detekt/releases/download/v$DETEKT_VERSAO/detekt-cli-$DETEKT_VERSAO-all.jar"
fi

echo "== compilando :wear (CBO/RFC são medidos sobre o bytecode)"
./gradlew --console=plain :wear:compileDebugKotlin

echo "== complexidade ciclomática (detekt)"
# O detekt sai com código != 0 quando há achados; aqui todo achado é um dado.
"$JAVA" -jar "$JAR" \
  --input wear/src/main/java \
  --config tools/metrics/detekt-metricas.yml \
  --report "xml:$SAIDA/detekt-cc.xml" >"$SAIDA/detekt-log.txt" 2>&1 || true

# O detekt grava caminhos absolutos; versioná-los guardaria o diretório de
# quem rodou, não a evidência. Relativiza para a raiz do repositório.
sed -i "s#$RAIZ/##g" "$SAIDA/detekt-cc.xml"

python3 tools/metrics/cc_detekt.py "$SAIDA/detekt-cc.xml" \
  --csv "$SAIDA/complexidade.csv" \
  --csv-resumo "$SAIDA/complexidade-por-pacote.csv"

echo
echo "== acoplamento (CBO) e resposta por classe (RFC)"
python3 tools/metrics/ck_metrics.py "$CLASSES" --csv "$SAIDA/cbo-rfc.csv"
