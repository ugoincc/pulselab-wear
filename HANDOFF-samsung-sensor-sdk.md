# Handoff — Integração do Samsung Health Sensor SDK (watch-side)

> **Nota (nov/2026):** documento histórico, escrito antes da camada de provedores e do transporte em lote. As menções a `ChannelClient`, `PassiveMonitoringClient` e `/sensor/data` descrevem o código daquela época; a arquitetura atual está no `README.md` e em `ESPECIFICACAO-protocolo-payload-v1.md`.

> **Para:** Claude Code
> **Projeto:** PulseLab — coleta de dados biométricos para pesquisa (TCC)
> **Alvo:** Galaxy Watch Ultra (Wear OS powered by Samsung / One UI Watch 6)
> **Módulo afetado:** app Kotlin do relógio (Wear OS)
> **SDK:** Samsung Health Sensor SDK v1.4.1

---

## 1. Objetivo

O app do relógio já coleta **HR** via Jetpack Health Services (`PassiveMonitoringClient`) e
faz streaming para o telefone via `ChannelClient`. Esse caminho **não fornece** os dados
restantes do projeto.

Esta tarefa adiciona o **Samsung Health Sensor SDK** ao módulo do relógio para destravar:

| Métrica   | Tracker do SDK                     | Tipo        | Uso no projeto                          |
|-----------|------------------------------------|-------------|-----------------------------------------|
| HRV (IBI) | Heart Rate (com IBI)               | contínuo    | base do RMSSD → biomarcador de estresse |
| SpO2      | SpO2                               | on-demand   | métrica secundária                      |
| Skin Temp | Skin Temperature                   | contínuo    | métrica secundária                      |

> **Stress** continua sem API direta (proprietário Samsung). Será **derivado** do HRV no
> backend/análise — não é escopo desta tarefa.

**Importante:** o Sensor SDK **não** compartilha dados com o Samsung Health nem com o Health
Connect. Ele lê o sensor direto no relógio. Mantém a arquitetura de baixa latência já validada
(streaming direto via `ChannelClient`, sem passar pelo Health Connect).

---

## 2. Pré-requisitos MANUAIS (humano, não automatizável)

Estes passos **não podem ser feitos pelo Claude Code** e precisam estar concluídos pelo Hugo
antes da implementação compilar/rodar:

1. **Baixar o SDK** (`.aar`) em `https://developer.samsung.com/health/sensor/process.html`
   (seção *SDK download*, v1.4.1). Requer login com conta Samsung.
   → Salvar o `.aar` em `app/libs/` (ou no módulo do relógio equivalente).

2. **Ativar o developer mode da Health Platform no relógio** (distinto do dev mode do Wear OS,
   que já está ligado):
   - Relógio → **Settings** → **Apps** → **Health Platform**
   - Tocar **~10 vezes rapidamente** no título "Health Platform"
   - Confirmar que aparece **`[Dev mode]`** abaixo do título
   - Sem isso, o SDK retorna `SDK_POLICY_ERROR` (package/assinatura não registrados).

3. **Confirmar device físico conectado via ADB** — o SDK **não roda em emulador**.

> Quando estes três itens estiverem prontos, marcar no início da sessão para o Claude Code
> prosseguir com a parte de código.

---

## 3. Integração no projeto (Gradle + Manifest)

### 3.1 `build.gradle` (módulo do relógio)

```kotlin
dependencies {
    // Samsung Health Sensor SDK (.aar local em app/libs/)
    implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))
    // ajustar o nome exato do arquivo .aar baixado
}
```

Garantir que `flatDir`/`libs` esteja resolvível, ou usar `implementation(fileTree("libs"))`.

### 3.2 `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<!-- BODY_SENSORS_BACKGROUND se for rastrear com a tela apagada / em background -->
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />
```

`BODY_SENSORS` é runtime permission — precisa ser solicitada em tempo de execução (ver §5).

> **Gotcha conhecido do projeto:** permissões de runtime ausentes causam leitura vazia
> silenciosa, não exceção. Logar explicitamente o resultado do pedido de permissão.

---

## 4. Conexão com a Health Platform

Criar um `SensorConnectionManager` (Kotlin). Imports base do SDK:

```kotlin
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.HealthTrackerException
```

Esqueleto da conexão (traduzido do exemplo Java oficial para Kotlin):

```kotlin
class SensorConnectionManager(private val context: Context) {

    var healthTracking: HealthTrackingService? = null
        private set

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            // 1) checar capability  2) iniciar trackers
            onConnected?.invoke()
        }
        override fun onConnectionEnded() { /* conexão encerrada */ }
        override fun onConnectionFailed(e: HealthTrackerException) {
            if (e.hasResolution()) {
                // precisa de Activity para resolver (ex.: instalar/atualizar Health Platform)
                e.resolve(activityRef)
            }
            onError?.invoke(e)
        }
    }

    var onConnected: (() -> Unit)? = null
    var onError: ((HealthTrackerException) -> Unit)? = null

    fun connect() {
        healthTracking = HealthTrackingService(connectionListener, context)
        healthTracking?.connectService()
    }

    fun disconnect() {
        healthTracking?.disconnectService()
        healthTracking = null
    }
}
```

> `e.resolve()` exige um `Activity`. Como o app coleta em background, manter uma referência de
> Activity só para o fluxo de resolução inicial, ou disparar uma Activity de setup quando
> `hasResolution()` for true.

---

## 5. Capability check + permissões

**Antes** de iniciar qualquer tracker:

1. **Permissão runtime** `BODY_SENSORS` (e `BODY_SENSORS_BACKGROUND` se aplicável). Usar o
   fluxo padrão do Wear OS / `ActivityResultContracts.RequestPermission`.
2. **Capability check** do SDK — fonte da verdade sobre quais trackers existem **neste**
   relógio. Chamar a API de capability (`healthTracking.trackingCapability` →
   `getSupportHealthTrackerTypes()`) e confirmar que os tipos desejados estão na lista antes de
   instanciar o tracker.

> **Não confie nos nomes de enum da minha memória.** Os valores exatos de `HealthTrackerType`
> (ex.: `HEART_RATE_CONTINUOUS`, `PPG_CONTINUOUS`, `SPO2_ON_DEMAND`,
> `SKIN_TEMPERATURE_CONTINUOUS`) **variam por versão do SDK**. Abra a API Reference que vem no
> pacote `.aar` baixado e use exatamente os enums daquela versão. O capability check em runtime
> deve ser o gate definitivo.

---

## 6. Tracking de dados

Padrão geral (do guia oficial), por tracker:

```kotlin
private var hrTracker: HealthTracker? = null

private val trackerListener = object : HealthTracker.TrackerEventListener {
    override fun onDataReceived(dataPoints: List<DataPoint>) {
        for (dp in dataPoints) {
            // extrair valores via ValueKey (ver §6.1)
        }
    }
    override fun onError(error: HealthTracker.TrackerError) { /* logar */ }
    override fun onFlushCompleted() { /* batch entregue */ }
}

fun startHeartRate() {
    hrTracker = healthTracking?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
    hrTracker?.setEventListener(trackerListener)
}

fun stopHeartRate() {
    hrTracker?.unsetEventListener()  // unsetting = stop tracking
    hrTracker = null
}
```

- **Iniciar:** `getHealthTracker(tipo)` + `setEventListener(listener)`.
- **Parar:** `unsetEventListener()`.
- **Tela apagada:** Health Platform entrega dados em **batch**. Para receber imediatamente,
  chamar `tracker.flush()` (dispara `onFlushCompleted()`).
- O intervalo entre timestamps dos `DataPoint` **pode variar** mesmo com frequência fixa —
  processar em ordem respeitando o timestamp.

### 6.1 Extração de IBI para HRV (o ponto-chave do projeto)

O tracker de Heart Rate fornece **IBI (inter-beat interval)** além do BPM — é isso que destrava
o HRV. No `DataPoint`, ler:

- valor de HR (BPM)
- **lista de IBI** + **lista de status do IBI** (cada IBI tem um status de qualidade; descartar
  os marcados como inválidos)

> Confirmar os `ValueKey` exatos na API Reference do `.aar` (algo como
> `ValueKey.HeartRateSet.HEART_RATE`, `...HEART_RATE_IBI_LIST`, `...HEART_RATE_IBI_STATUS_LIST`).
> Os nomes mudam entre versões — **não hardcodar sem verificar**.

Filtrar IBI por status antes de usar: IBIs com status diferente de "válido" devem ser
descartados, senão contaminam o RMSSD.

---

## 7. Cálculo de HRV (RMSSD)

RMSSD é a métrica que a fundamentação teórica do TCC aponta como principal marcador
(Jayasinghe & Torres, 2023). Acumular IBIs válidos em janelas (ex.: 60s ou 5min) e calcular:

```kotlin
/** RMSSD a partir de uma lista de intervalos IBI válidos (em ms). */
fun rmssd(ibiMs: List<Double>): Double? {
    if (ibiMs.size < 2) return null
    var sumSq = 0.0
    for (i in 1 until ibiMs.size) {
        val diff = ibiMs[i] - ibiMs[i - 1]
        sumSq += diff * diff
    }
    return sqrt(sumSq / (ibiMs.size - 1))
}
```

> Decisão de design a registrar na monografia: derivar o RMSSD você mesmo (em vez de usar um
> "stress score" caixa-preta da Samsung) é **vantagem acadêmica** — o algoritmo fica documentado
> e justificável. A janela de cálculo (60s vs 5min) e o critério de descarte de IBI devem ser
> parâmetros configuráveis.

---

## 8. Wiring no streaming existente (`ChannelClient` → telefone)

Não criar um caminho novo de transporte. As leituras dos novos trackers devem entrar no **mesmo
fluxo `ChannelClient`** já usado para HR. Sugestão de payload por amostra:

```json
{
  "ts": 1719500000000,
  "type": "hrv" | "spo2" | "skin_temp" | "hr",
  "value": 42.7,
  "meta": { "ibiCount": 12, "window": "60s" }
}
```

- Manter um único serializer/sender para todos os tipos.
- HR já existente continua via Jetpack; **não duplicar** — decidir se o HR passa a vir do
  Sensor SDK (que também dá BPM) ou se mantém o Jetpack para HR e usa o Sensor SDK só para
  IBI/SpO2/SkinTemp. **Recomendação:** usar o Sensor SDK como fonte única do par HR+IBI (evita
  dois trackers de HR competindo) e manter Jetpack apenas se houver razão de fallback.

---

## 9. Ciclo de vida

- `connectService()` no início da coleta; `disconnectService()` em `onDestroy`.
- `unsetEventListener()` em todos os trackers antes de desconectar.
- SpO2 é **on-demand**: só 1 tracker on-demand por vez. Não rodar SpO2 e ECG/BIA simultaneamente.
  Trackers contínuos (HR, SkinTemp) podem rodar em paralelo entre si e com 1 on-demand.
- Tratar `STATUS = -3` (relógio fora do pulso) para pausar/marcar dados como inválidos.

---

## 10. Checklist de validação

- [ ] `.aar` v1.4.1 em `libs/` e resolvido pelo Gradle (`compileDebugKotlin` limpo).
- [ ] `[Dev mode]` confirmado na Health Platform do relógio.
- [ ] `BODY_SENSORS` concedida em runtime (logar o resultado, não assumir).
- [ ] `onConnectionSuccess` disparando (sem `SDK_POLICY_ERROR`).
- [ ] Capability check lista HR, SpO2 e SkinTemp neste device.
- [ ] `onDataReceived` recebendo IBI não-vazio com status válido.
- [ ] RMSSD calculado > 0 em repouso e variando com respiração controlada (teste manual).
- [ ] Dados dos 3 novos tipos chegando ao telefone pelo `ChannelClient`.
- [ ] `flush()` funcionando com a tela apagada.
- [ ] `disconnectService()` + `unsetEventListener()` sem leak ao fechar.

---

## 11. Gotchas (do projeto + do SDK)

- **`SDK_POLICY_ERROR`** = dev mode off **ou** package/assinatura não registrados. Em dev é
  sempre o dev mode. A política do SDK pode demorar a baixar na primeira conexão — pode aparecer
  transitoriamente; tentar reconectar.
- **Leitura vazia silenciosa** quando falta permissão runtime (padrão já conhecido no projeto).
- **Enums de `HealthTrackerType` e `ValueKey` variam por versão** — sempre conferir na API
  Reference do `.aar` baixado, nunca hardcodar de memória.
- **Sem emulador.** Toda iteração precisa do Ultra via ADB.
- **IBI sujo:** filtrar por status antes do RMSSD, senão o valor explode.
- **Distribuição futura** (fora do escopo do TCC): exigirá partner request em
  `developer.samsung.com/health/sensor/process.html` e registro de package + SHA-256 da
  **release key** — não da debug key.

---

## 12. Ordem de implementação sugerida

1. Gradle + manifest + permissão runtime.
2. `SensorConnectionManager` (conectar/desconectar + listener).
3. Capability check logado.
4. HR+IBI tracker → log cru de IBI no Logcat (validar que IBI chega).
5. Função `rmssd()` + acumulação em janela.
6. SpO2 on-demand + SkinTemp contínuo.
7. Plugar tudo no sender `ChannelClient` existente.
8. Rodar checklist da §10.
