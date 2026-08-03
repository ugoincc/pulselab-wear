# Especificação: Protocolo de Payload e Interface SensorProvider (v1)

> Este documento é a fonte de verdade do contrato de dados. Código deve
> implementar contra ele — não o contrário. Qualquer mudança de campo passa
> primeiro por aqui, com bump de versão, antes de tocar `SensorReading.kt`,
> `SensorBundle.kt` ou `WearBridge.ts`.

## 1. Escopo e não-escopo

Cobre: a estrutura da mensagem transportada do relógio ao celular via
`MessageClient`/`NodeClient`, até o ponto em que chega à camada JS
(`WearBridge.ts`). Não cobre: persistência, cálculo de índices compostos,
consumo por aplicações downstream.

## 2. Versionamento

Todo payload carrega um campo `schemaVersion: Int`, começando em `1`. Mudança
que adiciona campo opcional = mesma versão maior, incrementa uma versão
menor documentada aqui (não precisa existir no wire — é só controle deste
documento). Mudança que remove campo, muda tipo, ou muda semântica de campo
existente = incrementa `schemaVersion` e exige que o consumidor declare
quais versões suporta.

**v1 = esta especificação.** Corresponde ao estado do código no commit
`2e3061a` (branch `feature/indice-estresse-composto`, repo `pulselab-wear`),
mais os campos novos listados na Seção 4.

## 3. Dicionário de dados — campos existentes hoje (mantidos)

| Campo | Tipo (wire) | Unidade | Nulável | Proveniência / semântica |
|---|---|---|---|---|
| `bpm` | `Int` | bpm | não | Truncado de `Double` via `toInt()` (não arredondado) — **decisão documentada**: mantido assim por consistência com o comportamento já observado; perda de precisão é sub-unitária e considerada aceitável. Mesmo instante que `hrv`/`baevskySI`. |
| `hrv` | `Int?` | ms | sim | RMSSD calculado **no relógio**, sobre janela deslizante de 60s de IBI filtrado (regra de plausibilidade + Malik). `null` se a janela não tiver amostras suficientes. Arredondado via `roundToInt()`. Mesmo instante que `bpm`/`baevskySI`. |
| `spo2` | `Int?` | % | sim | Medição on-demand, rearmada a cada 5 min (~30s por medição). **Carry-forward**: valor do último ciclo completo, que pode ter até ~5 min de idade no momento em que acompanha uma emissão de `bpm`/`hrv`. Ver campo novo `spo2MeasuredAt` (Seção 4). |
| `skinTemp` | `Double?` | °C | sim | Contínuo, taxa não documentada no código-fonte atual. Carry-forward, mesma ressalva de idade que `spo2`. Ver campo novo `skinTempMeasuredAt`. |
| `baevskySI` | `Double?` | adimensional (**escala bruta, sem `sqrt`**) | sim | Calculado no relógio, mesma janela de 60s que `hrv`, mesmo `IbiWindow`. Mesmo instante que `bpm`/`hrv`. O `sqrt` do Kubios **não** é aplicado: as faixas de referência publicadas por Baevsky (~50–150 em repouso) descrevem o índice bruto, então reportar o valor transformado contra elas é erro de categoria — ver `BaevskyStressIndex.compute()`. |
| `edaTonic` | `Double?` | µS | sim | **Sempre `null` no hardware atual** (Galaxy Watch Ultra não expõe `EDA_CONTINUOUS` na capability list). Campo mantido no contrato para compatibilidade futura — não remover. |
| `edaScrPerMin` | `Int?` | eventos/min | sim | Mesma ressalva de `edaTonic` — sempre `null` hoje. |
| `motionFlag` | `Boolean` | — | não | Gate de artefato via pico-a-pico do acelerômetro (`MotionGate`, janela de 1s). **Assíncrono**: pode ter até ~1s de defasagem em relação ao `timestamp` da emissão. Não recebe timestamp próprio (ver Seção 6 — decisão de não adicionar, custo/benefício desfavorável). |
| `timestamp` | `Long` | epoch ms | não | Origem no relógio, de `dp.getTimestamp()` do `DataPoint` de HR que disparou a emissão. É o único timestamp que hoje existe, e só é literalmente verdadeiro para `bpm`/`hrv`/`baevskySI`. |

## 4. Campos novos (v1 introduz, não existem no código hoje)

| Campo | Tipo (wire) | Obrigatório | Motivo |
|---|---|---|---|
| `schemaVersion` | `Int` | sim | Versionamento do contrato (Seção 2). |
| `sequenceNumber` | `Long` | sim | Monotônico, incrementado por `SensorReading` emitida (não por lote/mensagem — um número por elemento de `samples`). Permite detectar perda mesmo dentro de uma rajada. Reinicia em `0` a cada (re)conexão do `MessageClient`/`NodeClient` — ver Seção 5. |
| `phoneReceivedAt` | `Long` | sim | Epoch ms, carimbado no celular no momento em que `WearableHeartRateListenerService.onMessageReceived` processa a mensagem — **não** no momento em que chega à camada JS (essa segunda travessia, nativo→JS, é interna ao celular e não faz parte do que este protocolo mede; ver Seção 7 sobre isso). |
| `spo2MeasuredAt` | `Long?` | não (nulo se `spo2` nulo) | Epoch ms de quando aquele ciclo de SpO2 de fato completou, distinto de `timestamp`. Resolve a ficção do timestamp único identificada na auditoria de código. |
| `skinTempMeasuredAt` | `Long?` | não (nulo se `skinTemp` nulo) | Idem, para skin temperature. |
| `providerId` | `String` | sim | Identifica qual `SensorProvider` (Seção 6) originou a leitura — `"samsung-shs-sdk"`, `"health-services"`, `"replay"`. Necessário a partir do momento em que existir mais de um provedor. |
| `capabilities` | `CapabilitySet` (objeto, não por-amostra — ver Seção 6.3) | sim, uma vez por conexão | Declaração do que aquele provedor é capaz de fornecer, enviada uma vez ao conectar, não repetida a cada amostra. |

**Não adicionado, decisão explícita:** timestamp próprio para `motionFlag`
(defasagem ~1s, custo de implementação não justificado pelo ganho — ver
histórico de decisão no `OBJETIVOS-E-DECISOES-tcc.md`).

## 5. Semântica de `sequenceNumber`

- Escopo: por sessão de conexão Data Layer, não global/persistente.
- Reinício: toda vez que `NodeClient`/`MessageClient` reconecta após queda,
  reinicia em `0`. O celular deve tratar um `sequenceNumber` menor que o
  último recebido como sinal de reconexão, não como amostra fora de ordem —
  não é erro, é um evento a ser logado.
- Uso pretendido: no harness de avaliação (Objetivo 5), `sequenceNumber`
  ausente na sequência recebida = amostra perdida. `sequenceNumber` fora de
  ordem = reordenamento em trânsito (não esperado dado que `MessageClient`
  garante ordem por nó, mas o campo permite detectar se essa garantia for
  violada na prática).

## 6. Interface `SensorProvider`

### 6.1 — Por que não envolve `SensorConnectionManager`

`SensorConnectionManager` tem `HealthTrackerType`, `HealthTracker` e
`HealthTrackerException` na própria assinatura pública — é vocabulário
Samsung de ponta a ponta. `SensorProvider` não pode generalizar por cima
dela sem quebrar esse acoplamento. Solução: `SensorProvider` é uma interface
nova, e a implementação Samsung (`SamsungSensorProvider`) usa
`SensorConnectionManager` internamente, exatamente como
`SamsungSensorMonitor` já faz hoje — sem mudar `SensorConnectionManager`.

### 6.2 — A interface

```kotlin
package br.unioeste.pulselab.wear.data.provider

/**
 * Uma fonte alternativa de dados biométricos, por trás do mesmo contrato de
 * payload. Cada implementação concreta (Samsung SDK, Health Services,
 * replay) fica isolada — nenhuma toca as dependências internas das outras.
 */
interface SensorProvider {

    /** Identificador estável, vai no campo `providerId` do payload. */
    val providerId: String

    /**
     * Capacidades declaradas uma vez, ao conectar — não reavaliadas por
     * amostra. Um provedor pode declarar suporte a um campo mesmo que uma
     * amostra específica venha `null` (ex.: spo2 momentaneamente
     * indisponível não muda a capacidade declarada do provedor).
     */
    suspend fun capabilities(): CapabilitySet

    /**
     * Inicia a leitura e emite lotes de amostras já no formato de payload
     * (Seção 3+4). Implementações continuas emitem enquanto o Flow for
     * coletado; a fronteira de lote é decidida por cada provedor conforme
     * a granularidade que sua fonte de dado realmente entrega (não há
     * imposição de tamanho de lote fixo nesta interface).
     */
    fun readingFlow(): Flow<List<SensorPayloadV1>>

    /** Libera recursos de conexão. Idempotente. */
    fun disconnect()
}
```

### 6.3 — `CapabilitySet`

```kotlin
data class CapabilitySet(
    val providesHrv: Boolean,
    val providesBaevskySI: Boolean,
    val providesSpo2: Boolean,
    val providesSkinTemp: Boolean,
    val providesEda: Boolean,
    val providesMotionFlag: Boolean,
    /** Ex.: Health Services MeasureClient só entrega HR — todos os outros false. */
)
```

Isto é enviado **uma vez** por conexão (não por amostra) — o celular guarda
a capacidade declarada e a associa a todas as amostras daquele `providerId`
até a próxima (re)conexão.

### 6.4 — Implicação para os provedores planejados (Objetivo 4)

- `SamsungSensorProvider`: envolve `SamsungSensorMonitor` como está hoje.
  `capabilities()` retorna tudo `true` exceto `providesEda` (hardware atual
  não suporta — Seção 3, `edaTonic`/`edaScrPerMin`).
- `HealthServicesProvider`: adapta `LiveMeasureMonitor` (código recuperado
  do histórico, Seção "Achados de código" do `OBJETIVOS-E-DECISOES-tcc.md`)
  ao formato `SensorPayloadV1`. `capabilities()` retorna só
  `providesHrv=false` (Health Services `MeasureClient` não expõe IBI —
  logo não há como calcular RMSSD a partir dele), `providesBaevskySI=false`
  (mesma razão), `providesSpo2/providesSkinTemp/providesEda=false`, só
  `bpm` e `motionFlag` (se o acelerômetro for lido à parte) verdadeiros.
  Essa assimetria é, por si, a demonstração central do Objetivo 4.
- `ReplayProvider`: a construir do zero (Seção 8) — não reaproveita o
  `SIMULATED` antigo (ruído aleatório sem verdade fundamental conhecida,
  já descartado — ver decisão fixada).

## 7. Nota sobre a travessia nativo→JS (dentro do celular)

`phoneReceivedAt` (Seção 4) mede a chegada da mensagem Data Layer no
celular, não a chegada à camada JS (`WearBridge.ts`). A travessia
`WearBridgeModule` → `NativeEventEmitter` → JS é interna ao celular e
tipicamente sub-milissegundo — mas se a avaliação (Objetivo 5) precisar
medir isso separadamente, um terceiro timestamp (`jsReceivedAt`, carimbado
dentro do listener `WearBridge.ts`) pode ser adicionado como v1.1 sem
quebrar compatibilidade, já que seria um campo novo opcional.

## 8. Provedor de replay — requisitos (não implementado ainda)

Não faz parte deste documento de schema, mas fica registrado aqui o
requisito mínimo para não repetir o erro do `SIMULATED` antigo: o replay
precisa reproduzir uma série **gravada ou determinística com seed fixa**,
com `sequenceNumber` e `timestamp` de origem conhecidos de antemão pelo
avaliador, para que perda e deriva sejam mensuráveis por comparação exata
contra o que foi de fato recebido — não por inspeção de plausibilidade do
valor recebido.
