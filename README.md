# PulseLab — módulo do relógio (Wear OS)

Implementação de referência, do lado do relógio, do protocolo de comunicação e do
formato de *payload* propostos no Trabalho de Conclusão de Curso:

> CORDEIRO, Hugo Gustavo. **Especificação de um Protocolo de Comunicação e Payload
> Adaptável para Coleta de Dados Biométricos entre Smartwatches Wear OS e Aplicações
> Móveis.** Trabalho de Conclusão de Curso (Bacharelado em Ciência da Computação) —
> Universidade Estadual do Oeste do Paraná (Unioeste), Foz do Iguaçu, 2026.

O aplicativo roda num Galaxy Watch (Wear OS), adquire sinais fisiológicos por meio
de provedores de capacidades assimétricas, calcula no próprio relógio as métricas
de variabilidade da frequência cardíaca e transmite as amostras, em lotes, ao
aplicativo *companion* no celular pela Wear OS Data Layer API.

- Especificação normativa do contrato: [`ESPECIFICACAO-protocolo-payload-v1.md`](ESPECIFICACAO-protocolo-payload-v1.md)
- Aplicativo *companion* (receptor no celular): [`ugoincc/pulselab-companion`](https://github.com/ugoincc/pulselab-companion)
- Notas de campo sobre sensores e plataforma: [`SENSORS.md`](SENSORS.md)

## Arquitetura

```
RELÓGIO                                                  CELULAR
┌─────────────────────────────────────────┐  Data Layer  ┌───────────────────────────┐
│ SensorStreamService (foreground)        │ MessageClient│ WearableHeartRate-        │
│  └ SensorProvider (escolhido em runtime)│              │ ListenerService           │
│     ├ SamsungSensorProvider             │ /sensor/     │  └ carimba phoneReceivedAt│
│     ├ HealthServicesProvider            │  capabilities│  └ WearBridge → JS        │
│     └ ReplayProvider                    │ ────────────▶│     (desempacota o lote)  │
│  └ WearDataSender: 1 mensagem por lote  │ /sensor/batch│                           │
└─────────────────────────────────────────┘              └───────────────────────────┘
```

| Camada | Arquivos (`wear/src/main/java/br/unioeste/pulselab/wear/`) |
|---|---|
| Contrato do provedor e declaração de capacidades | `data/provider/SensorProvider.kt`, `data/provider/CapabilitySet.kt` |
| Provedores | `data/provider/SamsungSensorProvider.kt`, `HealthServicesProvider.kt`, `ReplayProvider.kt`, `ReplaySeries.kt` |
| Seleção do provedor em runtime (persistida) | `data/provider/ProviderSelection.kt` |
| Aquisição pelo SDK Samsung | `data/health/SensorConnectionManager.kt`, `SamsungSensorMonitor.kt`, `BiaMeasurementManager.kt` |
| Métricas calculadas no relógio | `data/health/Hrv.kt` (RMSSD), `BaevskyStressIndex.kt`, `MotionGate.kt`, `EdaFeatures.kt` |
| Serviço de coleta | `data/health/SensorStreamService.kt`, `SensorStreamStore.kt` |
| Payload e caminhos de mensagem | `model/SensorBundle.kt` (`SensorBundle`, `SensorBatch`), `model/MessagePaths.kt` |
| Transporte | `data/wearable/WearDataSender.kt` |
| Registro de emissão (avaliação) | `data/provider/EmissionLog.kt` |
| Interface (Compose for Wear OS) | `presentation/` |

### Camada de provedores

Toda fonte de dados implementa a mesma interface `SensorProvider`
(`providerId`, `capabilities()`, `readingFlow(): Flow<List<SensorBundle>>`,
`disconnect()`). O consumidor — o serviço de coleta e, do outro lado, o celular —
não conhece a origem do dado além do campo `providerId`.

| Provedor | `providerId` | FC | IBI → RMSSD / Baevsky | SpO₂ | Temp. pele | Movimento | EDA |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|
| Samsung Health Sensor SDK 1.4.1 | `samsung-shs-sdk` | ✓ | ✓ | ✓ | ✓ | ✓ | — ¹ |
| Jetpack Health Services | `health-services` | ✓ | — | — | — | ✓ ² | — |
| Replay determinístico | `replay` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

¹ O Galaxy Watch Ultra não expõe `EDA_CONTINUOUS`; o campo existe no contrato para
compatibilidade futura e chega sempre `null`.
² Lido do acelerômetro pela `SensorManager`, quando o aparelho o tem.

As capacidades são declaradas **uma vez por conexão** em `/sensor/capabilities`,
e não a cada amostra. Isso permite ao celular distinguir "valor ausente nesta
amostra" de "o provedor não fornece este canal". O vocabulário do SDK Samsung fica
contido nas três classes de aquisição (`SensorConnectionManager`,
`SamsungSensorMonitor`, `BiaMeasurementManager`): nenhuma classe da camada de
provedores referencia tipos do SDK — propriedade medida em `tools/metrics/`.

O `ReplayProvider` não é um sensor: reproduz uma série sintética determinística
(semente fixa) e grava o conjunto emitido, servindo de instrumento para a
avaliação de perda e latência.

### Contrato de dados (`schemaVersion = 1`)

Cada elemento de `SensorBatch.samples` é um `SensorBundle`, serializado em JSON:

| Campo | Tipo | Semântica resumida |
|---|---|---|
| `schemaVersion` | `Int` | Versão do contrato (§2 da especificação) |
| `sequenceNumber` | `Long` | Monotônico por amostra; reinicia em 0 a cada coleta do provedor (§5). Lacuna = perda; valor menor = reconexão |
| `providerId` | `String` | Proveniência da amostra |
| `timestamp` | `Long` (epoch ms) | Instante de aquisição, no relógio, de `bpm`, `hrv` e `baevskySI` |
| `bpm` | `Int` | Frequência cardíaca |
| `hrv` | `Int?` (ms) | RMSSD sobre janela deslizante de 60 s de IBI filtrado |
| `baevskySI` | `Double?` | Índice de estresse de Baevsky, escala bruta (sem `sqrt`) |
| `spo2`, `spo2MeasuredAt` | `Int?`, `Long?` | SpO₂ sob demanda (rearmado a cada 5 min) e o instante real da medição |
| `skinTemp`, `skinTempMeasuredAt` | `Double?`, `Long?` | Temperatura de pele e o instante real da medição |
| `motionFlag` | `Boolean` | Contaminação por movimento (pico a pico do acelerômetro, janela de 1 s) |
| `edaTonic`, `edaScrPerMin` | `Double?`, `Int?` | Atividade eletrodérmica (reservado; `null` no hardware atual) |

O celular acrescenta `phoneReceivedAt` no recebimento. A especificação completa —
unidades, nulidade, regras de versionamento e a justificativa de cada decisão —
está em [`ESPECIFICACAO-protocolo-payload-v1.md`](ESPECIFICACAO-protocolo-payload-v1.md).

### Transporte em lote

Com a tela apagada a plataforma retém as leituras e as entrega em rajadas (até 66
amostras de uma vez, medido — ver `SENSORS.md` §4.1). Por isso o relógio envia
**uma mensagem por rajada** (`/sensor/batch`, `MessageClient`), com as amostras em
ordem e cada uma com seu instante real de aquisição: um lote atrasado continua
sendo uma série fiel a 1 Hz. O relógio não impõe cadência própria — envia o que
chegou, quando chegou.

**Requisito da Data Layer:** as mensagens só são roteadas entre aplicativos com o
mesmo `applicationId` e o mesmo certificado nos dois aparelhos. Por isso este
módulo usa `applicationId = "br.unioeste.pulselab"` e a keystore de debug
versionada em `wear/debug.keystore` (a keystore de debug padrão do React Native,
senha `android`). O *companion* usa os mesmos valores.

## Compilação e instalação

Requisitos: Android Studio (JDK 17) e um Galaxy Watch **físico** com Wear OS
(`minSdk 30`) e depuração ADB habilitada — o SDK Samsung não roda em emulador.

```bash
./gradlew :wear:installDebug
./gradlew :wear:testDebugUnitTest   # RMSSD, Baevsky, MotionGate, EDA, replay e registro de emissão
```

No relógio:

1. Ative o modo desenvolvedor da Health Platform: Configurações → Aplicativos →
   Health Platform → toque ~10× no título até aparecer `[Dev mode]`. Sem isso o SDK
   retorna `SDK_POLICY_ERROR`.
2. Abra o app, inicie a sessão e conceda a permissão de sensores corporais
   ("Permitir o tempo todo" para coleta com a tela apagada).
3. O botão de fonte alterna entre Samsung → Health Services → Replay; a troca é
   feita em runtime, sem reiniciar a sessão, e a escolha é persistida.

**Por que `targetSdk = 35`:** no Wear OS 6 (API 36), `BODY_SENSORS` foi depreciada e
apps que miram a API 36 deixam de recebê-la como permissão de runtime (sintoma: a
tela de permissões do app fica vazia). Mirar a 35 mantém o fluxo clássico;
`compileSdk` permanece em 36. A migração para as permissões granulares
(`android.permission.health.READ_HEART_RATE`) fica como trabalho futuro.

## Avaliação reprodutível

| Pasta | Conteúdo |
|---|---|
| [`tools/reconcile.py`](tools/reconcile.py) | Concilia o conjunto emitido (relógio) com o recebido (celular): perda, duplicação, reordenamento, divergência de conteúdo, latência |
| [`tools/sample-run/`](tools/sample-run/) | Execução de exemplo ponta a ponta (Replay, tela acesa, 0% de perda) com os registros brutos |
| [`tools/factorial-run/`](tools/factorial-run/) | Varredura conectividade × cadência (seis execuções, incluindo interrupção deliberada do Bluetooth) |
| [`tools/metrics/`](tools/metrics/) | Métricas estáticas: complexidade ciclomática (detekt), CBO e RFC (`ck_metrics.py`) |

Para repetir uma execução:

1. Selecione o provedor **Replay** no relógio. Cadência e tamanho de lote vêm de
   `ProviderSelection.configureReplay` (padrão: 1 amostra/s, lote de 1).
2. Com o *companion* aberto no celular, inicie a sessão; ao final, recolha os dois
   registros:
   ```bash
   adb -s <relogio> pull /sdcard/Android/data/br.unioeste.pulselab/files/eval/ relogio/
   adb -s <celular> pull /sdcard/Android/data/br.unioeste.pulselab/files/eval/ celular/
   ```
3. Concilie:
   ```bash
   python3 tools/reconcile.py relogio/eval/emitted-replay-<data>.ndjson celular/eval/received-<data>.ndjson
   ```

As métricas estáticas são regeneradas com
`JAVA_HOME=<jdk17> bash tools/metrics/run.sh`; os CSVs em
`tools/metrics/resultados/` são a fonte das tabelas do capítulo de resultados.

## Documentos de projeto

Os arquivos `HANDOFF-*.md`, `AUDITORIA-*.md` e `RECUPERACAO-*.md` registram o
histórico das decisões de projeto ao longo do desenvolvimento. Descrevem o código
na época em que foram escritos; a referência atual é este README e a
especificação.

## Licenças de terceiros

- Samsung Health Sensor SDK (`wear/libs/`): sujeito aos termos de licença da Samsung.
- Fonte Space Grotesk: SIL Open Font License (`licenses/SPACE_GROTESK_OFL.txt`).
