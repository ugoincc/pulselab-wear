# Varredura do desenho fatorial — 2026-08-03 (tarde)

Continuação de `tools/sample-run/` (que tem a execução 1, tela acesa/próximo/1×,
132 amostras, 0% perda — mantida lá, não duplicada aqui). Esta pasta cobre o
restante do desenho de cap6-avaliacao.tex §6.5: os quatro níveis do fator de
conectividade e três níveis do fator de cadência, em execuções curtas (não o
desenho fatorial completo com repetições — uma passada representativa).

Rodar `reconcile-summary.txt` foi gerado por:
```
for par in ...; do python3 tools/reconcile.py <emitted> <received>; done
```

## Resultado consolidado

| # | Condição | Cadência | Emitidas | Perda | Latência mediana | Latência máx |
|---|---|---|---|---|---|---|
| 2 | Tela apagada, próximo, ~29 min | 1× | 1740 | 0% | 77 ms | **3839 ms** |
| 3 | Tela apagada, próximo, ~90 s | 2× | 176 | 0% | 45 ms | 461 ms |
| 4 | Tela acesa, próximo | 2× | 116 | 0% | 53 ms | 467 ms |
| 5 | Tela acesa, próximo | 4× | 232 | 0% | 45 ms | 420 ms |
| 6 | Interrupção de Bluetooth ~20 s, tela acesa | 1× | 110 | **4,55%** (5 seq. consecutivas) | 408 ms | 2365 ms |
| 7 | Tela apagada, ~2 cômodos de distância | 1× | 78 | 0% | 104 ms | 845 ms |

(numeração da tabela contínua com a execução 1 de `tools/sample-run/`.)

## Achados

**A rajada de doze é função do tempo em doze, não do estado da tela por si
só.** A execução 2 (29 minutos contínuos com a tela apagada) mostrou latência
subindo a 3839 ms; a execução 3, mesma condição de tela mas com apenas ~90 s
de duração, não mostrou rajada nenhuma (latência máxima 461 ms, comparável à
condição favorável). Isso é consistente com o comportamento documentado para
o sensor real (SENSORS.md §4.1) — a plataforma parece conceder uma janela de
tolerância antes de entrar no regime de acúmulo/descarga em rajada.

**A interrupção de enlace produz perda limpa e localizável.** Cinco amostras
consecutivas (sequência 24–28) perdidas, exatamente coincidindo com a janela
de ~20 s de Bluetooth desligado — sem reordenamento, sem duplicação, sem
divergência de conteúdo. O número de sequência fez exatamente o que a
especificação promete: tornou a perda detectável e localizável sem depender
de sincronização de relógio.

**Dois cômodos de distância não é "borda do alcance" — ainda é alcance
confortável.** 0% de perda. Não foi possível, nesta sessão, alcançar uma
condição de degradação real por distância dentro do espaço físico
disponível; permanece como pendência para uma sessão com mais espaço (ou
obstáculos deliberados, como paredes adicionais/andares).

## Duas anomalias de instrumentação encontradas e como foram tratadas

**Perda sistemática de ~10 s (≈20 amostras) após reiniciar os dois
aplicativos.** As primeiras tentativas de reduzir a duração das execuções
reiniciavam tanto o app do relógio quanto o do celular antes de cada
execução, na tentativa de garantir um arquivo de recepção novo por execução.
Isso introduziu uma perda espúria de ~10 s no início de toda execução —
tempo de reconexão da Data Layer, não perda de protocolo. Corrigido
mantendo o app do celular **sempre em execução** entre execuções (só o
relógio é reiniciado), o que elimina a perda de reconexão às custas de o
arquivo de recepção não trocar de nome a cada execução.

**Arquivo de recepção compartilhado entre execuções.** Consequência direta
da correção acima: como o app do celular não reinicia, o coletor
(`evaluationCollector.ts`) não abre um arquivo novo a cada execução — ele
segue anexando ao mesmo arquivo enquanto o processo do celular estiver vivo.
Isso é detectável e contornável sem perda de rigor: como `sequenceNumber`
reinicia em zero a cada nova conexão do provedor (ESPECIFICACAO §5), basta
segmentar o arquivo de recepção nos pontos em que a sequência recomeça, e
tratar cada segmento como o conjunto `R` da execução correspondente — foi
assim que os pares emitido/recebido desta pasta foram extraídos (ver
histórico de comandos, não automatizado em script por ora). Fica registrado
como melhoria futura do coletor: fechar e reabrir arquivo por conexão de
provedor, não só por processo do celular.
