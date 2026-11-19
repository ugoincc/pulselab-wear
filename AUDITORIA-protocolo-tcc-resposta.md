# Auditoria do protocolo relógio → app companion — resposta

Repositórios auditados:
- `pulselab-wear` (relógio), branch `feature/indice-estresse-composto`, HEAD `2e3061a`.
- `pulselab-companion` (celular, `pulselab-companion`), branch `hugo/indices-instantaneos`, HEAD `7f04f29`.

---

## 3.1 — Localização e conteúdo do schema/payload atual

**Arquivos que definem o formato:**

- `wear/src/main/java/br/unioeste/pulselab/wear/model/SensorReading.kt` (relógio, forma interna, uma leitura)
- `wear/src/main/java/br/unioeste/pulselab/wear/model/SensorBundle.kt` (relógio, forma de fio, uma amostra dentro do lote)
- `wear/src/main/java/br/unioeste/pulselab/wear/model/MessagePaths.kt` (relógio, path da mensagem)
- `android/app/src/main/java/br/unioeste/pulselab/wearable/WearBridgeModule.kt` (celular, desempacotamento nativo Kotlin → evento JS)
- `android/app/src/main/java/br/unioeste/pulselab/wearable/WearMessagePaths.kt` (celular, path espelhado)
- `src/wearable/WearBridge.ts` (celular, tipo TypeScript do evento)

Não há `.md` de handoff dedicado ao payload em si. `SENSORS.md` descreve a arquitetura de coleta e o comportamento observado do transporte (§4), mas não é uma especificação formal do schema — é o código (`SensorBundle`/`SensorReading`/`HeartRateEvent`) que define o formato.

**Definições copiadas na íntegra:**

`wear/src/main/java/br/unioeste/pulselab/wear/model/SensorReading.kt:11-26`
```kotlin
data class SensorReading(
    val bpm: Double,
    /** RMSSD over the IBI window, in ms. */
    val hrv: Int?,
    val spo2: Int?,
    val skinTemp: Double?,
    /** Baevsky Stress Index over the same IBI window (sqrt-transformed). */
    val baevskySI: Double?,
    /** Tonic skin conductance level (SCL), in microsiemens. */
    val edaTonic: Double?,
    /** Phasic skin-conductance responses per minute. */
    val edaScrPerMin: Int?,
    /** True when the accelerometer saw movement in this window (artefact gate). */
    val motionFlag: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
```

`wear/src/main/java/br/unioeste/pulselab/wear/model/SensorBundle.kt:11-42`
```kotlin
data class SensorBundle(
    val bpm: Int,
    val hrv: Int?,
    val spo2: Int?,
    val skinTemp: Double?,
    val baevskySI: Double?,
    val edaTonic: Double?,
    val edaScrPerMin: Int?,
    val motionFlag: Boolean,
    val timestamp: Long
)

data class SensorBatch(
    val samples: List<SensorBundle>
)
```

`wear/src/main/java/br/unioeste/pulselab/wear/model/MessagePaths.kt:3-13`
```kotlin
object MessagePaths {
    const val SENSOR_BATCH = "/sensor/batch"
}
```

`android/app/src/main/java/br/unioeste/pulselab/wearable/WearMessagePaths.kt` (celular, espelha o path acima):
```kotlin
object WearMessagePaths {
    const val SENSOR_BATCH = "/sensor/batch"
}
```

`src/wearable/WearBridge.ts:12-29` (tipo TypeScript, lado JS):
```typescript
export type HeartRateEvent = {
  bpm: number;
  hrv?: number;
  spo2?: number;
  skinTemp?: number;
  baevskySI?: number;
  edaTonic?: number;
  edaScrPerMin?: number;
  motionFlag: boolean;
  timestamp: number;
};
```

Observação: não existe `.aidl`, protobuf ou schema JSON formal — a serialização é feita por Gson diretamente sobre a data class Kotlin (`WearDataSender.kt:23`, `gson.toJson(batch)`), e o desempacotamento do lado do celular é feito manualmente campo a campo com `org.json.JSONObject` (`WearBridgeModule.kt:59-72`), não por (des)serialização automática de um tipo compartilhado. Não há um arquivo de schema único — o contrato é mantido por convenção de nomes entre três arquivos em dois repositórios, e isso é dito explicitamente nos comentários (`SensorBundle.kt:4-6`, `WearBridgeModule.kt:62`, `WearBridge.ts:5-6`).

---

## 3.2 — Metadados de proveniência e qualidade já existentes

**Indicador -1/0 no IBI mencionado no documento: não existe no código atual.** Busquei por qualquer uso de `-1` como flag de erro em `SensorReading`, `SensorBundle` ou no pipeline de HRV — não há. O que existe é diferente do que foi descrito:

- `IBI_STATUS_VALID` é uma constante que compara o **status retornado pelo próprio SDK Samsung** por intervalo (`ValueKey.HeartRateSet.IBI_STATUS_LIST`), definida em `wear/src/main/java/br/unioeste/pulselab/wear/data/health/Hrv.kt:19`:
  ```kotlin
  const val IBI_STATUS_VALID = 0
  ```
  Usada em `wear/src/main/java/br/unioeste/pulselab/wear/data/health/SamsungSensorMonitor.kt:141`:
  ```kotlin
  if (ibiStatus.getOrNull(i) == IBI_STATUS_VALID && ibi > 0) {
  ```
  Ou seja: `0` é o único valor tratado como válido; qualquer outro valor (não necessariamente `-1`) descarta o intervalo. O comentário em `SamsungSensorMonitor.kt:108` registra explicitamente que essa convenção "is still an assumption" — não foi confirmada contra a documentação do `.aar`, só contra comportamento observado em Logcat.
- Intervalos rejeitados **não geram um campo de erro no payload**. Eles simplesmente não entram na janela (`ibiWindow.noteDropped()`, `SamsungSensorMonitor.kt:147`) e portanto não aparecem como amostra individual alguma — o único resultado visível é que `hrv`/`baevskySI` saem de um conjunto menor de intervalos.

**Outros campos de qualidade/proveniência encontrados:**

1. `motionFlag: Boolean` em `SensorReading`/`SensorBundle`/`HeartRateEvent` — verdadeiro quando o acelerômetro detectou movimento na janela (gate de artefato via pico-a-pico, `wear/src/main/java/br/unioeste/pulselab/wear/data/health/MotionGate.kt:36-40`). É o único flag de qualidade que de fato viaja no payload de fio até o JS.
2. `IbiWindow.rejectionRate`, `IbiWindow.rejected`, `IbiWindow.accepted` — `Hrv.kt:144-152` — mas são contadores internos ao relógio, usados só em `Log.d` (`SamsungSensorMonitor.kt:156-163`). **Não são enviados ao celular** — não há campo equivalente em `SensorBundle`.
3. Status por sensor lidos do SDK (`HR_STATUS_VALID`, `HR_STATUS_OFF_BODY`, `SPO2_STATUS_COMPLETED`, `SKIN_TEMP_STATUS_VALID` — `SamsungSensorMonitor.kt:25-28`) — usados como gate de aceitação/descarte no relógio, mas nenhum desses status é propagado como campo no payload. Só o efeito (valor presente vs. `null`) chega ao celular.
4. Ausência vs. zero: campos opcionais (`hrv`, `spo2`, `skinTemp`, `baevskySI`, `edaTonic`, `edaScrPerMin`) ficam `null`/ausentes em vez de `0` quando o sensor correspondente não produziu leitura válida — tratado explicitamente do lado nativo (`WearBridgeModule.kt:61-72`, função `hasValue`) e documentado no tipo TS (`WearBridge.ts:19-22`). Isso é uma convenção de "dado ausente ≠ zero", não um flag de qualidade por si.

Não encontrei nenhum campo de confiança/interpolação (ex.: `confidence`, `interpolated`, `signalQuality`) em nenhum sensor.

---

## 3.3 — Instrumentação temporal e de sequência

**Número de sequência por amostra: não existe.** Busquei por `sequence`, `seqNum`, `seqNo` em todo `wear/src/main/java` — nenhuma ocorrência fora deste relatório. `SensorBundle` e `SensorReading` não têm nenhum campo desse tipo. A ordem da série depende inteiramente de `timestamp` e da ordem de entrega dentro de `samples: List<SensorBundle>` (documentada como "ordered oldest first", `SensorBundle.kt:37`), sem número monotônico explícito.

**Timestamp de origem no relógio: existe, mas é o único carimbo, e não é distinto do "timestamp de recepção".**

- Fonte: `dp.getTimestamp()` do `DataPoint` do SDK Samsung, não `System.currentTimeMillis()`. Usado em `SamsungSensorMonitor.kt:177` para `SensorReading.timestamp`, e também em `SamsungSensorMonitor.kt:119` e `:134` para calcular `lagMs` e reconstruir `ibiEndTimes`.
- `SensorReading.kt:25` tem um valor default `System.currentTimeMillis()`, mas esse default nunca é usado no caminho real — `SamsungSensorMonitor.kt:177` sempre passa `dp.getTimestamp()` explicitamente. O default só se aplicaria em algum outro caller de `SensorReading(...)` que omitisse o parâmetro, e não encontrei nenhum caso desses no código de produção (`grep` mostrou apenas `BiaMeasurementManager.kt:88`, que é um fluxo separado de composição corporal, fora do pipeline de streaming contínuo).
- Esse único timestamp — o de aquisição no relógio — é o que atravessa toda a cadeia até o JS: `SensorReading.timestamp` → `SensorBundle.timestamp` (`SensorStreamService.kt:82`) → JSON → `WearBridgeModule.emit`, campo `timestamp` (`WearBridgeModule.kt:70`) → `HeartRateEvent.timestamp` (`WearBridge.ts:28`).
- **Não existe um timestamp de recepção separado no celular.** Nem `WearableHeartRateListenerService.onMessageReceived` (celular, `WearableHeartRateListenerService.kt`) nem `WearBridgeModule.emitBatch`/`emit` carimbam a hora de chegada — eles só repassam o `timestamp` de origem que veio no JSON. O único lugar onde uma hora de recepção é calculada é `lagMs` em `SamsungSensorMonitor.kt:119`, e isso acontece **no próprio relógio** (para logging/diagnóstico local), comparando `System.currentTimeMillis()` do relógio com `dp.getTimestamp()` do mesmo relógio — não é uma medição relógio→celular, e não viaja no payload.

**Confirmação explícita:** não há número de sequência monotônico em nenhum ponto do pipeline relógio→celular, e não há timestamp de recepção no celular separado do timestamp de origem — apenas um timestamp de origem (`dp.getTimestamp()`) que percorre a cadeia inteira sem ser complementado por um segundo carimbo do lado do celular.

---

## 3.4 — Estrutura da travessia relógio → celular

**Correção de premissa:** o documento menciona `ChannelClient` como o transporte (§2.1 do handoff, e também `HANDOFF-samsung-sensor-sdk.md:239`, seção "Wiring no streaming existente (`ChannelClient` → telefone)"). O código atual **não usa `ChannelClient`** — usa `MessageClient` + `NodeClient` da Wear Data Layer API (`com.google.android.gms.wearable.Wearable`). Isso é o que está de fato implementado; a menção a `ChannelClient` no handoff antigo está desatualizada frente ao código.

**Caminho ponta a ponta, com arquivos e funções:**

1. **Callback do SDK Samsung** — `SamsungSensorMonitor.kt`, classe `SamsungSensorMonitor`, método `readingFlow()`. O listener `hrListener.onDataReceived(dataPoints: List<DataPoint>)` (`SamsungSensorMonitor.kt:94`) monta um `List<SensorReading>` por callback e emite via `trySend(batch)` (`:182`) dentro de um `callbackFlow`.
2. **Serviço em foreground** — `SensorStreamService.kt`, classe `SensorStreamService`. `onStartCommand` (`:51`) coleta o `Flow<List<SensorReading>>` de `SamsungSensorMonitor(applicationContext).readingFlow()` (`:64`), converte cada `SensorReading` em `SensorBundle` e monta um `SensorBatch` (`:72-85`).
3. **Envio Data Layer** — `WearDataSender.kt`, classe `WearDataSender`, método `send(context, batch)` (`:15`). Serializa o `SensorBatch` com Gson (`:23`) e envia via `Wearable.getMessageClient(context).sendMessage(node.id, MessagePaths.SENSOR_BATCH, payload)` (`:25-27`) para cada nó conectado obtido de `Wearable.getNodeClient(context).connectedNodes` (`:18`).
4. **Recepção no celular** — `WearableHeartRateListenerService.kt` (repo `pulselab-companion`), classe `WearableHeartRateListenerService extends WearableListenerService`. `onMessageReceived(event: MessageEvent)` filtra por `event.path == WearMessagePaths.SENSOR_BATCH` e publica o JSON bruto em `HeartRateEventBus.publish(...)`.
5. **Barramento interno Kotlin** — `HeartRateEventBus.kt` (não lido na íntegra nesta auditoria — apenas confirmada sua existência e uso como `Flow`/canal consumido em `WearBridgeModule`; ver `WearBridgeModule.kt:23-25`, `HeartRateEventBus.events.collect { ... }`).
6. **Módulo nativo React Native** — `WearBridgeModule.kt`, classe `WearBridgeModule : ReactContextBaseJavaModule`. `emitBatch(rawJson: String)` (`:42`) desempacota `{"samples":[...]}` e chama `emit(json: JSONObject)` (`:59`) para cada amostra, que monta um `WritableMap` e emite via `reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(EVENT_HEART_RATE, params)` (`:75-77`), evento nomeado `"onHeartRate"` (`:14`).
7. **Registro do módulo** — `WearBridgePackage.kt`, `ReactPackage.createNativeModules` retorna `listOf(WearBridgeModule(reactContext))`.
8. **Camada JS** — `src/wearable/WearBridge.ts`. `subscribeToHeartRate(onEvent)` (`:36-39`) usa `NativeEventEmitter(WearBridge)` (`:33`) e `emitter.addListener('onHeartRate', onEvent)`.
9. **Consumo por hook** — `src/wearable/useWearHeartRate.ts`, hook `useWearHeartRate()` (`:21`), chama `subscribeToHeartRate` dentro de um `useEffect` (`:24-33`) e expõe `{ latest, isLive }` para as telas.

**Abstração origem-vs-consumo:** não existe hoje. Confirmações:

- **`BaselineProvider` não é isso.** É uma interface do lado do celular, em TypeScript (`src/wearable/indices/baselineProvider.ts:16-25`, repo `pulselab-companion`), que abstrai *de onde vem o valor de repouso contra o qual os índices são medidos* (captura guiada hoje vs. média de 14 dias do backend na Fase 2) — não tem relação com a origem do sinal do sensor no relógio. O próprio comentário do arquivo deixa isso explícito (`baselineProvider.ts:4-9`).
- **Existiu uma abstração de fonte de sensor no relógio, e foi removida.** O histórico de commits mostra `607aaba feat: US-001 - Define shared SensorTracker interface` e `78bdeb0 feat: US-005 - Build DebugSensorRegistry aggregating all 9 trackers`, mas o commit `d7f6a5931bcea27454374a2a8c611b0a2985fecb` ("refactor: single-source relay — Samsung SDK only, auto-start") removeu esse caminho junto com as fontes `LIVE`/`SIMULATED`/`BACKGROUND` (arquivos deletados: `HeartRateRepository.kt`, `LiveMeasureMonitor.kt`, `PassiveDataService.kt`, `PassiveHeartRateStore.kt`, `PassiveMonitoringManager.kt`, `DataSource.kt` — ver `git show d7f6a593 --stat`). `grep` em `wear/src` confirma que nenhum arquivo `SensorTracker.kt`/`DebugSensorRegistry.kt` existe hoje na árvore de código-fonte; as únicas ocorrências restantes são artefatos de build antigos em `wear/build/intermediates/...` (bytecode `.class`/`.dex` de um build anterior, não fonte).
- Hoje, `SamsungSensorMonitor` é chamado diretamente por `SensorStreamService` (`SensorStreamService.kt:64`) — não há interface entre eles, é uma dependência concreta direta.

---

## 3.5 — `ValueKey` e enums do Samsung Health Sensor SDK

Confirmado por decompilação direta do `.aar` (`wear/libs/samsung-health-sensor-api-1.4.1.aar` → `classes.jar` → `javap`), não por suposição:

**`ValueKey.EdaSet`** (usado para `HealthTrackerType.EDA_CONTINUOUS`):
```
public static final ValueKey<Float> SKIN_CONDUCTANCE;
public static final ValueKey<Integer> STATUS;
```
Nomes exatos: `SKIN_CONDUCTANCE` e `STATUS`. Não há campo `EDA_CONTINUOUS` dentro de `ValueKey` — esse nome pertence ao enum `HealthTrackerType`, não a `ValueKey`.

**`ValueKey.AccelerometerSet`** (usado para `HealthTrackerType.ACCELEROMETER_CONTINUOUS`):
```
public static final ValueKey<Integer> ACCELEROMETER_X;
public static final ValueKey<Integer> ACCELEROMETER_Y;
public static final ValueKey<Integer> ACCELEROMETER_Z;
```
Idem: `ACCELEROMETER_CONTINUOUS` é um valor de `HealthTrackerType`, não de `ValueKey`.

**`HealthTrackerType`** (enum completo, decompilado):
```
PPG_GREEN, PPG_CONTINUOUS, PPG_RED, PPG_IR, PPG_ON_DEMAND,
HEART_RATE, HEART_RATE_CONTINUOUS,
ECG, ECG_ON_DEMAND,
ACCELEROMETER, ACCELEROMETER_CONTINUOUS,
BIA, BIA_ON_DEMAND,
SWEAT_LOSS,
SPO2, SPO2_ON_DEMAND,
SKIN_TEMPERATURE, SKIN_TEMPERATURE_ON_DEMAND, SKIN_TEMPERATURE_CONTINUOUS,
EDA_CONTINUOUS,
MF_BIA_ON_DEMAND
```
Confirma que `EDA_CONTINUOUS` e `ACCELEROMETER_CONTINUOUS` existem tal como usados em `SamsungSensorMonitor.kt` (`HealthTrackerType.ACCELEROMETER_CONTINUOUS` em `:267`; `EDA_CONTINUOUS` referenciado apenas em comentário, `:62`, já que o tracker de EDA foi removido do código ativo — ver 3.7).

**`ValueKey.HeartRateSet`** (usado no código ativo, para completude):
```
public static final ValueKey<Integer> HEART_RATE;
public static final ValueKey<List<Integer>> IBI_LIST;
public static final ValueKey<List<Integer>> IBI_STATUS_LIST;
public static final ValueKey<Integer> HEART_RATE_STATUS;
```
Confirma os nomes usados em `SamsungSensorMonitor.kt:102-105`.

**Falha `Invalid HealthTrackerType` (BIA):** confirmada no histórico e **ainda aberta**. Documentada em `SENSORS.md:101` e `SENSORS.md:368`:
> "BIA (composição corporal) — Sensor SDK `BIA_ON_DEMAND` — `BiaMeasurementManager`/`BiaScreen`/`BiaViewModel` | Código mantido na árvore, sem entrada na UI: o Galaxy Watch Ultra anuncia `BIA_ON_DEMAND` na capability mas `getHealthTracker` lança `Invalid HealthTrackerType`. Religar assim que isso for resolvido"

E no item de pendências (`SENSORS.md:368`):
> "BIA indisponível no Galaxy Watch Ultra via SDK: a capability lista `BIA_ON_DEMAND`, mas `getHealthTracker(BIA_ON_DEMAND)` lança `Invalid HealthTrackerType`. Código mantido na árvore, sem entrada na UI. Investigar: [...]"

Está listada como `- [ ]` (não marcada como resolvida) — é um problema aberto, não corrigido.

---

## 3.6 — Testes, benchmarks ou instrumentação de avaliação existentes

**Não existe nenhum script, teste automatizado ou ferramenta de software que meça latência, throughput ou perda de amostras entre relógio e celular.** Busquei por `latency`, `throughput`, `benchmark`, `packet loss`, "perda de amostra" em todo o código-fonte (`.kt`, `.ts`) de ambos os repositórios — nenhuma ocorrência fora de texto descritivo em `SENSORS.md`. Não há diretório de scripts, não há teste de instrumentação (`androidTest`) voltado a isso, e o único diretório `wear/src/test` contém apenas testes unitários de matemática pura: `BaevskyStressIndexTest.kt`, `EdaFeaturesTest.kt`, `HrvTest.kt`, `MotionGateTest.kt` — nenhum deles testa o transporte relógio→celular.

O que existe são **medições manuais, documentadas em prosa em `SENSORS.md`**, feitas via `Log.d`/`adb logcat` e contagem manual de um arquivo NDJSON gravado no celular (`SENSORS.md:267`, "expo-file-system", mencionado também em `SENSORS.md:376`), não por uma ferramenta reutilizável:

- `SENSORS.md` §4.1 ("Entrega em rajadas com a tela apagada") — tabela de rajadas observadas manualmente em 2026-07-27, com o campo instrumentado `lagMs` (calculado em `SamsungSensorMonitor.kt:119`, só logado via `Log.d`, não parte do payload).
- `SENSORS.md` §4.2 ("Envio em lote — validado ponta a ponta") — tabela de mensagens/lote/maior `lagMs` medida manualmente em 2026-07-28, com o app rodando e inspeção visual do card "Biometria do relógio".
- `SENSORS.md` §4.3 ("A série tem buracos periódicos de ~11 s por minuto") — análise de um arquivo NDJSON de 1530 amostras, contado e computado manualmente (não há script commitado que faça essa contagem).

Nenhuma dessas medições é reexecutável automaticamente — são registros pontuais de sessões manuais, sem harness de teste, sem CI, sem script parametrizável.

---

## 3.7 — Estado do Health Services e do provedor de replay

**Health Services: não há nenhuma implementação nem esqueleto.** Busquei por `HealthServices`, `health.services`, `PassiveMonitoringClient`, `MeasureClient` no código-fonte atual de `wear/src`. O único artefato relacionado é a **dependência da biblioteca** `androidx.health:health-services-client:1.0.0` presente no cache Gradle local (`~/.gradle/caches/.../health-services-client-1.0.0.aar`), mas isso é resíduo de um uso **anterior e já removido**: o commit `d7f6a5931bcea27454374a2a8c611b0a2985fecb` ("refactor: single-source relay — Samsung SDK only, auto-start") deletou `PassiveDataService.kt`, `PassiveHeartRateStore.kt` e `PassiveMonitoringManager.kt`, que eram os arquivos que usavam Health Services antes. A [[passive-monitoring-architecture]] anterior ao refactor não existe mais no código atual. Não há nenhum arquivo `.kt` no repositório hoje que importe `androidx.health.services.client`.

**Provedor de replay/sinal sintético: não existe.** Busquei por `replay`, `synthetic`, "sinal sintético", `fake feed`/`FAKE`/`SIMULATED` no código-fonte atual — o único resultado é histórico (git log), não código presente: a mesma refatoração `d7f6a593` removeu a fonte `SIMULATED` (fake feed) que existia antes junto com `LIVE`/`BACKGROUND`. Não há nenhum arquivo, classe ou esqueleto de "replay provider" ou gerador de sinal sintético na árvore atual de `wear/src` nem em `pulselab-companion`.

Confirmo explicitamente, portanto: **nenhuma implementação de Health Services e nenhuma implementação de provedor de replay existem hoje no repositório** — ambos foram, no máximo, código anterior já removido, não trabalho em andamento.
