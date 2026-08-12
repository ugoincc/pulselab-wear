#!/usr/bin/env python3
"""
Extrai a Complexidade Ciclomática por função do relatório XML do detekt e
resume a distribuição por pacote (cap6-avaliacao.tex §6.2, cap3 §3.2).

Uso:
    python3 tools/metrics/cc_detekt.py build/detekt-cc.xml \\
        --csv tools/metrics/resultados/complexidade.csv

A regra `CyclomaticComplexMethod` do detekt implementa a contagem de McCabe
(1976). Configurada com limiar 1 em `detekt-metricas.yml`, ela reporta toda
função em vez de só as que violam um limiar — o que interessa aqui é a
distribuição, não a lista de infrações.

Limite conhecido da ferramenta, que precisa ser declarado junto do número: a
regra não visita declarações locais, isto é, funções escritas dentro do corpo
de outra função. No módulo :wear isso deixa de fora as implementações de
callback declaradas em expressões `object : ...` (os ouvintes do SDK e do
Health Services). A população medida é, portanto, a das funções nomeadas de
nível de classe ou de arquivo.
"""

import argparse
import csv
import os
import re
import statistics
import sys
from collections import defaultdict

ARQUIVO = re.compile(r'<file name="([^"]+)"')
FUNCAO = re.compile(
    r"The function (\w+) appears.*?\(complexity: (\d+)\)"
)

# Limiar proposto por McCabe (1976) como teto de complexidade por módulo.
LIMIAR_MCCABE = 10


def extrair(caminho_xml):
    linhas = []
    arquivo = None
    with open(caminho_xml, encoding="utf-8") as fh:
        for linha in fh:
            m = ARQUIVO.search(linha)
            if m:
                # Caminho absoluto do worktree não diz nada a quem lê depois.
                arquivo = m.group(1).split("/java/")[-1]
                continue
            m = FUNCAO.search(linha)
            if m and arquivo:
                linhas.append({
                    "arquivo": arquivo,
                    "pacote": arquivo.rsplit("/", 1)[0],
                    "funcao": m.group(1),
                    "cc": int(m.group(2)),
                })
    return linhas


def resumo(linhas, chave=None):
    vs = sorted(l["cc"] for l in linhas)
    return {
        "escopo": chave or "total",
        "funcoes": len(vs),
        "soma": sum(vs),
        "mediana": statistics.median(vs),
        "maximo": max(vs),
        "acima_do_limiar": sum(1 for v in vs if v > LIMIAR_MCCABE),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("xml", help="relatório XML do detekt")
    ap.add_argument("--csv", help="CSV por função")
    ap.add_argument("--csv-resumo", help="CSV agregado por pacote")
    args = ap.parse_args()

    linhas = extrair(args.xml)
    if not linhas:
        sys.exit(f"nenhuma função em {args.xml} — o detekt rodou?")

    por_pacote = defaultdict(list)
    for l in linhas:
        por_pacote[l["pacote"]].append(l)
    resumos = [resumo(v, k) for k, v in sorted(por_pacote.items())]
    resumos.append(resumo(linhas))

    for destino, dados in ((args.csv, linhas), (args.csv_resumo, resumos)):
        if not destino:
            continue
        os.makedirs(os.path.dirname(destino) or ".", exist_ok=True)
        with open(destino, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(dados[0].keys()))
            w.writeheader()
            w.writerows(dados)
        print(f"{len(dados)} linhas -> {destino}")

    print(f"\n{'escopo':<34}{'n':>5}{'soma':>7}{'med':>6}{'máx':>6}{'>10':>5}")
    for r in resumos:
        print(f"{r['escopo'].replace('br/unioeste/pulselab/wear/',''):<34}"
              f"{r['funcoes']:>5}{r['soma']:>7}{r['mediana']:>6.0f}"
              f"{r['maximo']:>6}{r['acima_do_limiar']:>5}")


if __name__ == "__main__":
    main()
