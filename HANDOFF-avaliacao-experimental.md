# HANDOFF — Instrumentação da avaliação experimental (Objetivos 4 e 5)

> Escrito a partir do Capítulo 6 da monografia (`cap6-avaliacao.tex`, repo
> `tcc_tex`), que define o desenho experimental. Este documento traduz aquele
> desenho em trabalho de implementação. **O capítulo é a fonte de verdade do
> que precisa ser medido**; este documento é a fonte de verdade de como
> construir o instrumento.

## Contexto mínimo

- `SensorProvider` / `CapabilitySet` já existem (`data/provider/`), com uma
  implementação: `SamsungSensorProvider`.
- O schema v1 do payload está implementado nos dois repos e documentado em
  `ESPECIFICACAO-protocolo-payload-v1 (1).md`. Não mudar campo sem passar
  pela política de versionamento da §2 daquele documento.
- `SensorStreamService` ainda chama `SamsungSensorMonitor` **direto**, não
  através de `SensorProvider`. `WearDataSender.sendCapabilities()` existe e
  não é chamado de lugar nenhum.

Essas duas últimas pendências não são esquecimento: com um provedor só, nem
a indireção nem o anúncio de capacidade informam nada. Elas se resolvem na
tarefa 2 abaixo, que é quando passam a ter alternativa do outro lado.

## Estado (2026-08-03)

| Tarefa | Estado |
|---|---|
| 1 — `ReplayProvider` | **feita**, validada em dispositivo (determinismo entre duas execuções reais) |
| 2 — `HealthServicesProvider` + seleção | **feita**, validada em dispositivo e ponta a ponta no app |
| 3 — Coletor no app companion | **feita** (`src/wearable/evaluationCollector.ts` + `EvaluationLog.kt`), execução real pendente |
| 4 — Harness de conciliação | **feita** (`tools/reconcile.py`), verificada com registros sintéticos |

O que falta é executar uma sessão de replay com os dois registros ligados e
passar o par pelo `reconcile.py` — a primeira medida real de perda e latência.

## Ordem de execução

As quatro tarefas são sequencialmente dependentes: 1 → 2 → 3 → 4. Não vale
paralelizar 3 antes de 2, porque a seleção de provedor é o que o harness
liga e desliga.

---

## Tarefa 1 — `ReplayProvider` (determinístico)

**Objetivo 5 depende inteiramente disto.** Sem verdade fundamental conhecida
não há como medir perda, só "o que chegou".

Criar `data/provider/ReplayProvider.kt`, implementando `SensorProvider`:

- `providerId = "replay"`.
- `capabilities()` retorna **tudo `true`**, inclusive `providesEda` — o ponto
  é exercitar todos os campos do contrato, inclusive os que nenhum hardware
  do estudo preenche.
- `readingFlow()` emite a série a partir de semente fixa (`Random(seed)`) ou
  de um arquivo de série gravada em `assets/`. Duas execuções com a mesma
  semente **precisam** produzir a mesma sequência de valores, campo a campo.
- Cadência parametrizável no construtor (`emitIntervalMs`), com o valor do
  sensor real (1 Hz) como padrão e múltiplos dela como níveis do experimento.
- Tamanho de lote parametrizável, para reproduzir tanto o caso de tela acesa
  (1 amostra) quanto a rajada de tela apagada (até ~66 — ver `SENSORS.md`
  §4.1).
- `sequenceNumber` segue a mesma regra dos outros provedores
  (ESPECIFICACAO §5): monotônico por amostra, reinicia a 0 a cada coleta de
  `readingFlow()`.

**Registro de emissão (não esquecer — é metade do instrumento).** Cada
amostra emitida vai para um arquivo NDJSON no relógio, com todos os campos +
o instante de emissão. Esse arquivo é o conjunto `S` da avaliação. Inferir
`|S|` de duração × cadência **não** é aceitável: a §4.3 do `SENSORS.md` já
mostrou que a premissa de amostragem uniforme é falsa mesmo para o provedor
real.

Teste unitário: duas instâncias com a mesma semente produzem sequências
idênticas; `sequenceNumber` não tem buracos na origem.

---

## Tarefa 2 — `HealthServicesProvider` + seleção de provedor

**Fecha o Objetivo 4** (≥2 provedores com capacidades assimétricas), que é a
demonstração central da abstração.

1. Criar `data/provider/HealthServicesProvider.kt` adaptando o código de
   `LiveMeasureMonitor`/`PassiveMonitoringManager` (deletados em `d7f6a593`,
   recuperáveis do histórico do git). **Adaptar, não copiar**: o payload
   mudou de forma desde então.
   - `providerId = "health-services"`.
   - `capabilities()`: só `providesHrv=false`, `providesBaevskySI=false`,
     `providesSpo2=false`, `providesSkinTemp=false`, `providesEda=false`;
     `bpm` sempre, `providesMotionFlag` verdadeiro **apenas se** o
     acelerômetro for lido à parte via a API de sensores do Android.
   - MeasureClient não expõe IBI ⇒ não há como derivar RMSSD nem Baevsky SI
     aqui. Os campos vão `null`, e é a capacidade declarada — não o `null` —
     que diz ao celular que a origem nunca fornecerá aquilo.
2. Trocar `SensorStreamService` para coletar de um `SensorProvider`
   selecionado, em vez de instanciar `SamsungSensorMonitor` direto.
3. Chamar `WearDataSender.sendCapabilities()` **uma vez por conexão de
   provedor**, no ponto em que o serviço começa a coletar o fluxo — não por
   amostra (ESPECIFICACAO §6.3).
4. No celular (`pulselab-companion`): tratar `/sensor/capabilities`,
   guardar a declaração por `providerId` e associá-la às amostras daquele
   provedor até a próxima conexão.

Critério de aceite: alternar entre os dois provedores em runtime muda o
`providerId` das amostras e o conjunto de campos preenchidos, sem que
nenhuma linha do consumidor JS precise mudar.

---

## Tarefa 3 — Coletor no app companion

Registrador em `pulselab-companion` que grava, **na camada de aplicação
JS** (depois da ponte nativa, não no listener nativo), uma linha NDJSON por
amostra recebida: todos os campos do payload + instante de recepção. Esse
arquivo é o conjunto `R`.

O ponto de gravação é decisão de projeto, não detalhe: o escopo do protocolo
termina onde o dado se torna utilizável pela aplicação, então é ali que a
latência fim a fim precisa ser fechada.

---

## Tarefa 4 — Harness de medição e conciliação

Script de análise **fora dos dispositivos** (Python), que recebe os dois
NDJSON e produz, por execução:

| Métrica | Como calcular |
|---|---|
| Taxa de perda | `(|S| − |S ∩ R|) / |S|`, casando por `(providerId, sequenceNumber)` |
| Latência fim a fim | `phoneReceivedAt − timestamp`, **corrigida** pelo desvio de relógio |
| Variação da latência | Diferença sucessiva das latências (imune à deriva) |
| Taxa de reordenamento | Sequência menor que a anterior, descontados reinícios de provedor |
| Divergência de conteúdo | Comparação campo a campo contra a série emitida |

Sobre a correção de relógio: medir o desvio por ida-e-volta pelo mesmo
transporte, no início e no fim da execução, e aplicar correção linear
(deriva constante é premissa razoável em execuções de minutos). Reportar a
incerteza residual junto do valor — e **não** derivar conclusão de projeto
de diferença menor que ela.

Condições experimentais a suportar (níveis do fator de conectividade):
tela acesa/próximo; tela apagada/próximo; tela apagada/borda do alcance;
enlace interrompido e restabelecido.

---

## Armadilhas já pagas — não repetir

1. **Não contar amostras por `adb logcat -d`.** O buffer é circular, 1 MiB, e
   vive saturado: um dump de 8 minutos devolveu o último minuto. Toda taxa
   calculada assim mede rotação de buffer. Ou se transmite o logcat para
   arquivo (sem `-d`), ou se conta no NDJSON — que é o registro que interessa.
2. **Não assumir `n = duração × 1 Hz`.** A série real perde ~18 % de cada
   minuto de forma regular (`SENSORS.md` §4.3).
3. **Não usar o `SIMULATED` antigo** (`HeartRateRepository`) como fonte de
   avaliação: ruído aleatório sem verdade fundamental não mede perda.
4. **Não comparar `timestamp` e `phoneReceivedAt` cru** e chamar de latência:
   são relógios independentes.
5. **Renomear campo é mudança cross-repo.** Os nomes são o contrato; o
   desempacotamento no celular é por nome.

## Estado dos documentos

Corrigidos nesta rodada, a partir do código: `README.md` (rearme do SpO2 é
5 min, não 60 s), `ESPECIFICACAO-protocolo-payload-v1 (1).md` §3 e o KDoc de
`SensorReading.kt` (Baevsky SI é **escala bruta, sem `sqrt`**). Se aparecer
divergência nova entre documento e código, o padrão é o mesmo: auditar,
decidir quem está certo, corrigir o outro — e registrar a decisão.
