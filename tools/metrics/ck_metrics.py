#!/usr/bin/env python3
"""
Calcula Acoplamento entre Objetos (CBO) e Resposta por Classe (RFC) sobre o
bytecode compilado do módulo :wear (cap6-avaliacao.tex §6.2, cap3 §3.2).

Uso:
    python3 tools/metrics/ck_metrics.py \\
        wear/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes \\
        --csv tools/metrics/resultados/cbo-rfc.csv

Por que bytecode e não fonte: as duas métricas de Chidamber e Kemerer (1994)
são definidas sobre o grafo de dependências efetivo entre classes, e a suíte de
referência que as implementa opera sobre Java. Nenhuma ferramenta consolidada
as calcula sobre Kotlin. O bytecode é o denominador comum: o que o compilador
Kotlin de fato emitiu, com os tipos já resolvidos, o que dispensa a resolução
de tipos a partir do fonte (a parte frágil de analisar Kotlin estaticamente).

O preço dessa escolha é que o compilador emite classes que não existem no
projeto — lambdas, continuações de `suspend`, singletons do Compose. Elas são
dobradas na classe hospedeira (ver `hospedeira()`); sem isso, todo código com
corrotinas ou Compose apareceria fatiado em dezenas de classes artificiais e os
números não diriam nada sobre o projeto.
"""

import argparse
import csv
import os
import re
import subprocess
import sys
from collections import defaultdict

# javap do JDK que compilou o módulo. O do PATH pode não existir (o Android
# Studio traz o seu próprio JBR e não o expõe globalmente).
JAVAP = os.environ.get("JAVAP", "javap")

# Ruído injetado pelo compilador em praticamente todo método Kotlin: verificação
# de nulidade de parâmetros, boxing, intrínsecos de corrotina. Contá-los somaria
# a mesma constante a todas as classes sem dizer nada sobre o projeto.
RUIDO_COMPILADOR = (
    "kotlin.jvm.internal.Intrinsics",
    "kotlin.jvm.internal.Lambda",
    "kotlin.jvm.internal.FunctionReferenceImpl",
    "kotlin.coroutines.jvm.internal.ContinuationImpl",
    "kotlin.coroutines.jvm.internal.SuspendLambda",
    "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda",
    "kotlin.coroutines.jvm.internal.DebugProbesKt",
    "kotlin.coroutines.intrinsics.IntrinsicsKt",
    "kotlin.ResultKt",
    "kotlin.Unit",
    "java.lang.Object",
)

PRIMITIVOS = {
    "int", "long", "float", "double", "boolean", "byte", "char", "short",
    "void",
}

# Origem de cada dependência. A ordem importa: o primeiro prefixo que casar
# vence. É esta decomposição que responde à questão do capítulo 6 — se o
# vocabulário do SDK do fabricante fica ou não contido no adaptador.
ORIGENS = [
    ("projeto", ("br.unioeste.pulselab.",)),
    ("sdk", ("com.samsung.",)),
    ("plataforma", (
        "android.", "androidx.", "com.google.android.gms.",
        "java.", "javax.", "kotlin", "org.jetbrains.", "com.google.gson.",
    )),
]

# Membros que o compilador gera por conta própria numa `data class` e que
# ninguém escreveu. Contá-los faria uma classe de dados com 14 campos parecer
# mais complexa que um serviço de streaming — o oposto do que a métrica quer
# dizer. Nem suas declarações nem as chamadas feitas dentro delas contam.
GERADOS_DATA_CLASS = re.compile(
    r"^(component\d+|copy|equals|hashCode|toString|writeToParcel"
    r"|describeContents|values|valueOf|getEntries)$"
)

# Sufixos e padrões de classe emitida pelo compilador, não escrita por ninguém.
SINTETICA = re.compile(
    r"(\$\d+$)"                 # lambda anônima: Foo$1, Foo$bar$2
    r"|(\$Companion$)"          # objeto companion: pertence à hospedeira
    r"|(\$DefaultImpls$)"       # corpo default de método de interface
    r"|(\$WhenMappings$)"       # tabela de salto de `when` sobre enum
    r"|(\$\$inlined)"           # corpo de função inline materializado
    r"|(^ComposableSingletons\$)"
)


def hospedeira(fqn):
    """Classe do projeto à qual uma classe emitida pelo compilador pertence.

    `Foo$bar$1` (lambda dentro de Foo.bar), `Foo$Companion` e
    `ComposableSingletons$FooKt` são todas dobradas em `Foo`/`FooKt`. Classes
    aninhadas escritas à mão (`Foo$Estado`, um sealed class interno) também
    são dobradas: pertencem ao mesmo arquivo e ao mesmo ato de projeto, e
    separá-las inflaria a contagem de classes sem mudar o acoplamento real.
    """
    pacote, _, simples = fqn.rpartition(".")
    if simples.startswith("ComposableSingletons$"):
        simples = simples[len("ComposableSingletons$"):]
    simples = simples.split("$", 1)[0]
    return f"{pacote}.{simples}" if pacote else simples


def origem(fqn):
    for nome, prefixos in ORIGENS:
        if fqn.startswith(prefixos):
            return nome
    return "plataforma"


def listar_classes(raiz):
    """Nomes totalmente qualificados de todo .class sob a raiz de compilação."""
    achadas = []
    for pasta, _, arquivos in os.walk(raiz):
        for arq in arquivos:
            if not arq.endswith(".class"):
                continue
            rel = os.path.relpath(os.path.join(pasta, arq), raiz)
            achadas.append(rel[: -len(".class")].replace(os.sep, "."))
    return sorted(achadas)


def desmontar(raiz, classes):
    """Saída de `javap -p -c` para todas as classes, numa única invocação."""
    proc = subprocess.run(
        [JAVAP, "-p", "-c", "-classpath", raiz, *classes],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"javap falhou:\n{proc.stderr}")
    return proc.stdout


# --- extração de tipos ------------------------------------------------------

# Referências que o javap anota no comentário de cada instrução do pool de
# constantes: `// Method com/foo/Bar.baz:(...)V`, `// class com/foo/Bar`,
# `// Field com/foo/Bar.x:Lcom/foo/Baz;`.
REF_METODO = re.compile(r"// (?:Interface)?Method ([\w/$]+)\.([\w$<>]+):(\S+)")
REF_CAMPO = re.compile(r"// Field ([\w/$]+)\.([\w$]+):(\S+)")
REF_CLASSE = re.compile(r"// class ([\w/$]+)")
# Descritor de tipo dentro de uma assinatura JVM: Lcom/foo/Bar;
TIPO_DESCRITOR = re.compile(r"L([\w/$]+);")
# Assinatura legível de declaração de campo/método no cabeçalho do javap.
TIPO_LEGIVEL = re.compile(r"\b((?:[a-z][\w]*\.)+[A-Z][\w.$]*)")


def tipos_de_descritor(desc):
    return {m.replace("/", ".") for m in TIPO_DESCRITOR.findall(desc)}


def analisar(saida_javap):
    """Agrupa a saída do javap por classe hospedeira.

    Devolve, por hospedeira: tipos referenciados, métodos declarados, métodos
    invocados e os campos privados de cada classe (necessários para distinguir
    um acessor de propriedade de uma função escrita à mão — ver `e_acessor`).
    """
    tipos = defaultdict(set)
    declarados = defaultdict(set)
    invocados = defaultdict(set)
    campos = defaultdict(set)

    atual = None
    bruta = None          # classe como o compilador a nomeou, antes de dobrar
    ignorar_corpo = False
    cabecalho = re.compile(
        r"^(?:public |final |abstract |static |protected |private )*"
        r"(?:class|interface|enum) ([\w.$]+)"
    )

    for linha in saida_javap.splitlines():
        nu = linha.strip()

        m = cabecalho.match(nu)
        if m:
            bruta = m.group(1)
            atual = hospedeira(bruta)
            ignorar_corpo = False
            # `extends`/`implements` são acoplamento declarado.
            for t in TIPO_LEGIVEL.findall(nu.split(bruta, 1)[-1]):
                tipos[atual].add(t)
            continue

        if atual is None:
            continue

        if nu.startswith("Code:") or re.match(r"^\d+:", nu):
            if ignorar_corpo:
                continue
            # Corpo de método: só interessam as referências do pool.
            for cls, met, desc in REF_METODO.findall(nu):
                cls = cls.replace("/", ".")
                tipos[atual].add(cls)
                tipos[atual] |= tipos_de_descritor(desc)
                if met not in ("<init>", "<clinit>"):
                    # `foo$default` é a ponte de argumentos padrão gerada pelo
                    # compilador para `foo`, não um método distinto.
                    invocados[atual].add(f"{cls}.{met.split('$default')[0]}")
            for cls, _campo, desc in REF_CAMPO.findall(nu):
                tipos[atual].add(cls.replace("/", "."))
                tipos[atual] |= tipos_de_descritor(desc)
            for cls in REF_CLASSE.findall(nu):
                tipos[atual].add(cls.replace("/", "."))
            continue

        if nu.endswith(";") and "(" in nu and ")" in nu:
            # Declaração de método no cabeçalho: assinatura legível.
            nome = re.search(r"([\w.$]+)\(", nu)
            metodo = nome.group(1).rpartition(".")[2].split("$default")[0] \
                if nome else None
            ignorar_corpo = metodo is None or (
                metodo.startswith("access$")
                or GERADOS_DATA_CLASS.match(metodo)
            )
            if ignorar_corpo:
                continue
            for t in TIPO_LEGIVEL.findall(nu):
                tipos[atual].add(t)
            # Um construtor aparece no javap com o nome da classe, não como
            # `<init>`. E os membros de uma classe sintética (o corpo de uma
            # lambda, de uma continuação de `suspend`, de um listener anônimo)
            # não são métodos que a hospedeira expõe: o que eles *chamam* entra
            # no conjunto de resposta, mas eles próprios não são |M|.
            if metodo == bruta.rpartition(".")[2] or SINTETICA.search(bruta):
                continue
            declarados[atual].add(metodo)
        elif nu.endswith(";"):
            # Declaração de campo.
            nome = re.search(r"([\w$]+);$", nu)
            if nome:
                campos[atual].add(nome.group(1))
            for t in TIPO_LEGIVEL.findall(nu):
                tipos[atual].add(t)

    return tipos, declarados, invocados, campos


def e_acessor(metodo, campos):
    """`getBpm`/`setBpm` sobre um campo `bpm` é a propriedade `val bpm` do
    Kotlin, não um método escrito à mão. Só descarta quando o campo existe de
    fato, para não engolir uma função que por acaso se chame `getAlgo()`.
    """
    for prefixo in ("get", "set", "is"):
        if not metodo.startswith(prefixo):
            continue
        resto = metodo[len(prefixo):]
        if not resto:
            continue
        candidato = resto[0].lower() + resto[1:]
        if candidato in campos or resto in campos:
            return True
    return False


def metricas(raiz, prefixo_projeto):
    classes = listar_classes(raiz)
    if not classes:
        sys.exit(f"nenhum .class sob {raiz} — o módulo foi compilado?")
    tipos, declarados, invocados, campos = analisar(desmontar(raiz, classes))

    hospedeiras = sorted(
        h for h in tipos
        if h.startswith(prefixo_projeto) and not SINTETICA.search(h)
    )

    linhas = []
    for h in hospedeiras:
        # Uma referência à própria classe (ou a uma classe interna dela) não é
        # acoplamento; nem o ruído do compilador; nem tipo primitivo.
        deps = {
            t for t in tipos[h]
            if hospedeira(t) != h
            and t not in PRIMITIVOS
            and not t.startswith(RUIDO_COMPILADOR)
        }
        por_origem = defaultdict(set)
        for t in deps:
            por_origem[origem(t)].add(t)

        proprios = {
            m for m in declarados[h]
            if m != "<init>" and not e_acessor(m, campos[h])
        }
        resposta = proprios | {
            i for i in invocados[h]
            if not i.startswith(RUIDO_COMPILADOR)
        }

        linhas.append({
            "classe": h,
            "pacote": h.rpartition(".")[0],
            "cbo": len(deps),
            "cbo_projeto": len(por_origem["projeto"]),
            "cbo_sdk": len(por_origem["sdk"]),
            "cbo_plataforma": len(por_origem["plataforma"]),
            "rfc": len(resposta),
            "metodos_proprios": len(proprios),
            "sdk_referenciado": ";".join(sorted(por_origem["sdk"])),
        })
    return linhas


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("classes", help="raiz das classes compiladas")
    ap.add_argument("--prefixo", default="br.unioeste.pulselab.wear",
                    help="pacote do projeto a medir")
    ap.add_argument("--csv", help="arquivo de saída; ausente, imprime tabela")
    args = ap.parse_args()

    linhas = metricas(args.classes, args.prefixo)
    campos = list(linhas[0].keys())

    if args.csv:
        os.makedirs(os.path.dirname(args.csv) or ".", exist_ok=True)
        with open(args.csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=campos)
            w.writeheader()
            w.writerows(linhas)
        print(f"{len(linhas)} classes -> {args.csv}")

    print(f"\n{'classe':<52}{'CBO':>5}{'proj':>6}{'SDK':>5}{'plat':>6}{'RFC':>6}")
    for l in sorted(linhas, key=lambda r: -r["cbo"]):
        print(f"{l['classe'].replace('br.unioeste.pulselab.wear.', ''):<52}"
              f"{l['cbo']:>5}{l['cbo_projeto']:>6}{l['cbo_sdk']:>5}"
              f"{l['cbo_plataforma']:>6}{l['rfc']:>6}")


if __name__ == "__main__":
    main()
