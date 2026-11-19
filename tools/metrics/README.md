# Métricas estáticas do módulo `:wear`

Instrumentos da Seção 6.7 do TCC (qualidade estrutural da camada de
provedores). Medem três métricas sobre o código de produção do relógio:

| Métrica | Origem | Ferramenta |
|---|---|---|
| Complexidade Ciclomática | McCabe (1976) | detekt 1.23.8, regra `CyclomaticComplexMethod` |
| Acoplamento entre Objetos (CBO) | Chidamber e Kemerer (1994) | `ck_metrics.py` (este diretório) |
| Resposta por Classe (RFC) | Chidamber e Kemerer (1994) | `ck_metrics.py` (este diretório) |

## Reproduzir

```sh
JAVA_HOME=/opt/android-studio/jbr bash tools/metrics/run.sh
```

Baixa o detekt-cli na primeira execução, compila `:wear`, e escreve em
`resultados/`:

- `complexidade.csv` — uma linha por função
- `complexidade-por-pacote.csv` — agregado
- `cbo-rfc.csv` — uma linha por classe, com o CBO decomposto por origem
- `detekt-cc.xml` — relatório bruto do detekt

Os números das tabelas do capítulo 6 saem desses arquivos. Nenhum é digitado
à mão no texto.

## Por que uma ferramenta própria para CBO e RFC

A suíte de referência que implementa as métricas de Chidamber e Kemerer opera
sobre Java. Nenhuma ferramenta consolidada as calcula sobre Kotlin — o detekt
cobre complexidade, não acoplamento. `ck_metrics.py` mede sobre o bytecode
compilado (`javap`), onde os tipos já estão resolvidos, em vez de tentar
resolvê-los a partir do fonte.

O preço é que o compilador Kotlin emite classes e métodos que ninguém
escreveu. As regras de normalização estão documentadas no cabeçalho de
`ck_metrics.py` e resumidas aqui:

- lambdas, continuações de `suspend`, singletons do Compose e objetos
  `companion` são **dobrados na classe hospedeira**; o que eles chamam entra
  no conjunto de resposta, mas eles próprios não contam como métodos da
  classe;
- membros gerados por `data class` (`componentN`, `copy`, `equals`,
  `hashCode`, `toString`) e acessores de propriedade (`getX`/`setX` sobre um
  campo `x` existente) não contam em `|M|`;
- o ruído que o compilador injeta em todo método (`Intrinsics`, `Boxing`,
  `Unit`, `java.lang.Object`) é excluído do acoplamento: somaria a mesma
  constante a todas as classes.

## Limite conhecido da medida de complexidade

A regra do detekt não visita declarações locais — funções escritas dentro do
corpo de outra função. No `:wear` isso deixa de fora 20 implementações de
callback declaradas em expressões `object : ...` (ouvintes do SDK Samsung e do
Health Services). A população medida é a das 105 funções nomeadas de nível de
classe ou de arquivo, de um total de 125 no fonte.
