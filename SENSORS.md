# Guia de Sensores, SDKs e Licenciamento — PulseLab Wear

> Atualizado em 2026-07-23. Dispositivo de referência: Galaxy Watch Ultra (SM-L705F, Wear OS powered by Samsung).
> Celular de teste: Galaxy A10s. App mobile: `pulselab-companion` (Expo/React Native).

## 1. As quatro camadas de acesso a dados de saúde

| Camada | Onde roda | O que fornece | Licenciamento |
|---|---|---|---|
| **Health Services (Jetpack `androidx.health.services`)** | Relógio | FC em tempo real (`MeasureClient`) e em background (`PassiveMonitoringClient`) | Nenhum — API pública do Wear OS. Só permissão de runtime `BODY_SENSORS` (+ `BODY_SENSORS_BACKGROUND` para passivo) |
| **Samsung Health Sensor SDK** (v1.4.1, `.aar` em `wear/libs/`) | Relógio | Acesso direto aos sensores: FC+IBI, SpO2, temperatura de pele, **BIA**, **MF-BIA**, ECG, EDA, PPG bruto, suor, acelerômetro | Download livre. **Desenvolvimento**: exige *developer mode* do Health Platform no relógio. **Produção/distribuição**: exige aprovação de parceria da Samsung (partner request no developer site) |
| **Samsung Health Data SDK** | Celular | Lê o banco do app Samsung Health: sono, exercícios, passos, pressão, glicose, **composição corporal (resultado de BIA)** etc. | **Leitura em desenvolvimento**: livre com *developer mode* do Samsung Health. **Escrita ou produção**: parceria Samsung obrigatória |
| **Health Connect (Android)** | Celular | Dados que o Samsung Health sincroniza (passos, FC, sono, composição corporal), com consentimento do usuário | Nenhum licenciamento Samsung — só permissões do Health Connect. Alternativa de produção sem parceria |

**Regra prática**: sensor *ao vivo* = relógio (Sensor SDK / Health Services). Dado *histórico/consolidado* = celular (Data SDK ou Health Connect).

## 2. Sensores no nosso app hoje

### Funcionais (fluxo principal, enviados ao celular)

| Métrica | Fonte | Como funciona | Status |
|---|---|---|---|
| Frequência cardíaca | Sensor SDK `HEART_RATE_CONTINUOUS` (~1 Hz) | Driver da cadência de envio. (Fontes alternativas via Health Services foram removidas na simplificação para relay — histórico no git) | ✅ validado no relógio |
| HRV (RMSSD) | Calculado localmente do IBI (`IBI_LIST` do Sensor SDK), janela deslizante — `Hrv.kt` | Não existe HRV pronto em nenhum SDK | ✅ validado |
| SpO2 | Sensor SDK `SPO2_ON_DEMAND`, rearmado a cada 60 s | Cada medição leva ~30 s | ✅ validado |
| Temperatura de pele | Sensor SDK `SKIN_TEMPERATURE_CONTINUOUS` | Status `0` = válido (confirmado em device) | ✅ validado |
| **SI de Baevsky** | Calculado do mesmo `IBI_LIST` que alimenta o RMSSD — `BaevskyStressIndex.kt` | `SI = AMo / (2·Mo·MxDMn)`, com detrend por média móvel centrada e transformação `sqrt` (convenção Kubios). Mais sensível a dominância simpática que o RMSSD isolado | ⚠️ implementado, aguardando validação em device |
| **EDA (tônico + SCR)** | Sensor SDK `EDA_CONTINUOUS` → `EdaFeatures.kt` | SCL por média da janela, SCR por picos acima de 0,02 µS. Canal mais específico para ativação simpática pura (sem confundidor vagal). **Código de `STATUS` válido ainda é hipótese (`0`)** — todo sample é logado para confirmar | ⚠️ implementado, aguardando validação em device |
| **Gate de movimento** | Sensor SDK `ACCELEROMETER_CONTINUOUS` → `MotionGate.kt` | Não é feature de estresse: marca `motionFlag` por janela de 1 s via pico-a-pico da magnitude do vetor. O dado segue sendo enviado e armazenado — o flag só diz ao celular quais janelas excluir do índice | ⚠️ limiar precisa de calibração em repouso real |
| **Leitura em background** | `SensorStreamService` (foreground service tipo `health`) | Mantém o stream com tela apagada; notificação persistente | ✅ implementado (validar consumo de bateria) |

Por que o índice é composto e não um score de caixa-preta: cada feature acima é
transparente e citável, e o índice final é uma soma ponderada de z-scores (pesos
iguais como ponto de partida), calculada **no celular** — que é onde vive a
baseline por participante. O relógio só produz features.

### Fora do fluxo, para retomar depois

| Métrica | Fonte | Situação |
|---|---|---|
| **BIA (composição corporal)** | Sensor SDK `BIA_ON_DEMAND` — `BiaMeasurementManager`/`BiaScreen`/`BiaViewModel` | Código mantido na árvore, **sem entrada na UI**: o Galaxy Watch Ultra anuncia `BIA_ON_DEMAND` na capability mas `getHealthTracker` lança `Invalid HealthTrackerType`. Religar assim que isso for resolvido |

Trackers que o SDK expõe e que **não** usamos (MF-BIA, ECG, PPG bruto, perda de
suor): o registry de debug que os listava foi removido junto com a simplificação
para dados reais. Estão no histórico do git se voltarem a ser necessários.

### Não disponíveis

| Métrica | Motivo | Alternativa |
|---|---|---|
| Índice de estresse | Proprietário Samsung (derivado de HRV), sem API pública em nenhum SDK | **Resolvido**: índice composto próprio (RMSSD + SI de Baevsky + EDA), transparente e citável — ver acima |

## 3. Licenciamento — o que precisa de quê

**Hoje (desenvolvimento)** — nada de parceria:
- Relógio com *developer mode* do Health Platform ativado (sem isso o Sensor SDK retorna `SDK_POLICY_ERROR`).
- Celular: se formos ler o Samsung Health via Data SDK, ativar *developer mode* do Samsung Health.

**Para publicar (produção)**:
1. **Sensor SDK (relógio)** → partner request na Samsung (developer.samsung.com → Health → Sensor SDK → App creation process).
2. **Data SDK (celular)** → partner request separado, obrigatório até para funcionar sem developer mode.
3. **Rota sem parceria**: Health Connect no celular cobre os dados consolidados; o Sensor SDK continua exigindo parceria para o app do relógio distribuído.
4. BIA/ECG podem estar **indisponíveis por região** (restrição regulatória da Samsung por país, independente da parceria).

## 4. Arquitetura de coleta atual

O relógio produz **features**; o celular produz o **índice**. Essa divisão é
deliberada: o índice composto depende de uma baseline por participante (5–10 min
de repouso), que é estado com ciclo de vida e persistência — coisa de celular, não
de foreground service sob pressão de bateria.

```
RELÓGIO (relay, fonte única)                      CELULAR
┌────────────────────────────────┐   Data Layer   ┌───────────────────────────┐
│ SensorStreamService (FGS)      │  /sensor/data  │ WearableHeartRate-        │
│  └ SamsungSensorMonitor        │ ──────────────▶│ ListenerService           │
│     HR+IBI → RMSSD + SI        │   (JSON 1/s)   │  └ WearBridge → JS        │
│     SpO2, temp. de pele        │                │     índice composto       │
│     EDA → SCL + SCR/min        │                │     (z-score vs baseline) │
│     Accel → motionFlag         │                │     + storage local       │
│  (auto-inicia ao abrir o app)  │                └───────────────────────────┘
└────────────────────────────────┘
```

Payload em `/sensor/data` (`SensorBundle`, serializado por Gson):

```json
{
  "bpm": 78, "hrv": 42, "spo2": 97, "skinTemp": 33.1,
  "baevskySI": 61.4, "edaTonic": 4.82, "edaScrPerMin": 3,
  "motionFlag": false, "timestamp": 1732650000000
}
```

- Os nomes dos campos são o contrato com o `WearBridge` do app do celular —
  renomear um é breaking change entre repositórios.
- Package name e assinatura **precisam ser idênticos** nos dois apps (`br.unioeste.pulselab` + debug.keystore do RN) — requisito do Data Layer.
- O receptor no celular precisa do `<data android:scheme="wear" .../>` no intent-filter, senão o GMS descarta a mensagem silenciosamente.

## 5. Pendências conhecidas

### Validação em device (bloqueiam confiar nos dados novos)

- [ ] **Código de `STATUS` válido do EDA**: assumido `0` por analogia com a
      temperatura de pele. Todo sample é logado (`EDA status=… skinConductance=…`)
      justamente para confirmar isso na primeira rodada — foi assim que se
      descobriu que a temperatura usa `0` e não `1`.
- [ ] **Confirmar que `EDA_CONTINUOUS` de fato instancia** no Galaxy Watch Ultra.
      A capability listar o tracker não garante nada: o BIA lista e mesmo assim
      lança `Invalid HealthTrackerType`. O código já trata a falha graciosamente.
- [ ] **Calibrar `MotionGate.DEFAULT_THRESHOLD`** (hoje `200.0`, provisório) contra
      repouso real — day trader sentado numa mesa. O pico-a-pico observado é
      logado a cada emissão para permitir isso por medição, não por chute.
- [ ] Validar faixas do SI de Baevsky em device (referência usual: 50–150 baixo
      estresse, >500 forte dominância simpática, sobre o valor já transformado).

### Operacional

- [ ] Medir consumo de bateria com EDA + acelerômetro contínuos rodando junto de HR/IBI/SpO2/temperatura.
- [ ] Decidir cadência de envio (1/s é caro em Bluetooth; considerar lote a cada 5–10 s — agora com 9 campos por payload em vez de 6).
- [ ] Iniciar processo de parceria Samsung antes de qualquer distribuição (vale para EDA e acelerômetro também).

### Retomar depois

- [ ] **BIA indisponível no Galaxy Watch Ultra via SDK**: a capability lista `BIA_ON_DEMAND`, mas `getHealthTracker(BIA_ON_DEMAND)` lança `Invalid HealthTrackerType`. Código mantido na árvore, sem entrada na UI. Investigar: pode exigir outra forma de instanciar, versão de Health Platform, ou ser recorte do modelo/região.
- [ ] Enviar resultado do BIA ao celular (novo path `/sensor/bia` + tipo no `WearBridge` — toca o repo do colega, fazer na branch `hugo/wear-sync`).

### Lado do celular (Parte 2 — repo `pulselab-companion`)

- [ ] Estender `HeartRateEvent` no `WearBridge.ts` com os campos novos — **não** criar um segundo evento/listener.
- [ ] UI de captura de baseline (5–10 min de repouso) + cálculo do índice composto.
- [ ] Escolher storage local (o app hoje não tem SQLite nem WatermelonDB — é dependência nova em repo de terceiro, decidir com o dono antes).
