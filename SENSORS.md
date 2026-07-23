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
| Frequência cardíaca | Sensor SDK `HEART_RATE_CONTINUOUS` (~1 Hz); alternativas: Health Services live/passivo | Driver da cadência de envio | ✅ validado no relógio |
| HRV (RMSSD) | Calculado localmente do IBI (`IBI_LIST` do Sensor SDK), janela deslizante — `Hrv.kt` | Não existe HRV pronto em nenhum SDK | ✅ validado |
| SpO2 | Sensor SDK `SPO2_ON_DEMAND`, rearmado a cada 60 s | Cada medição leva ~30 s | ✅ validado |
| Temperatura de pele | Sensor SDK `SKIN_TEMPERATURE_CONTINUOUS` | Status `0` = válido (confirmado em device) | ✅ validado |
| **Leitura em background** | `SensorStreamService` (foreground service tipo `health`) | Mantém o stream com tela apagada; notificação persistente | ✅ implementado (validar consumo de bateria) |

### Implementado, aguardando validação no relógio

| Métrica | Fonte | Observações |
|---|---|---|
| **BIA (composição corporal)** | Sensor SDK `BIA_ON_DEMAND` — fluxo guiado em `BiaScreen` | Retorna: % e massa de gordura, % e massa de músculo esquelético, água corporal, TMB, massa livre de gordura, impedância bruta (Ω). **Semântica dos ints de `STATUS`/`PROGRESS` ainda não confirmada em device** (padrão do SDK: cada tracker usa códigos diferentes). Medição exige 2 dedos nos botões por ~15 s — nunca é passiva |

### Disponíveis no registry de debug (não integrados ao fluxo principal)

| Métrica | Tracker | Nota |
|---|---|---|
| MF-BIA | `MF_BIA_ON_DEMAND` | BIA multifrequência — magnitude + fase por frequência |
| ECG | `ECG_ON_DEMAND` | Dado bruto do eletrodo. Interpretação clínica é regulada (o app Samsung Health Monitor tem aprovação regulatória; nós recebemos só o sinal) |
| EDA | `EDA_CONTINUOUS` | Atividade eletrodérmica (relacionada a estresse/suor) |
| PPG bruto | `PPG_GREEN/RED/IR` | Fotopletismografia crua (para pesquisa/algoritmos próprios) |
| Perda de suor | `SWEAT_LOSS` | Estimativa pós-exercício |
| Acelerômetro | `ACCELEROMETER_CONTINUOUS` | Movimento bruto |

### Não disponíveis

| Métrica | Motivo | Alternativa |
|---|---|---|
| Índice de estresse | Proprietário Samsung (derivado de HRV), sem API pública em nenhum SDK | Calcular nosso próprio índice a partir do RMSSD (base já existe em `Hrv.kt`) |

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

```
RELÓGIO                                          CELULAR
┌───────────────────────────────┐    Data Layer   ┌──────────────────────────┐
│ SensorStreamService (FGS)     │  /sensor/data   │ WearableHeartRate-       │
│  └ SamsungSensorMonitor       │ ───────────────▶│ ListenerService          │
│     HR+IBI→HRV, SpO2, temp    │  (JSON 1/s)     │  └ WearBridge → JS       │
│ PassiveDataService (fallback) │                 │     WatchLiveCard (Home) │
│ BiaMeasurementManager (sob    │                 └──────────────────────────┘
│  demanda, fluxo guiado)       │
└───────────────────────────────┘
```

- Package name e assinatura **precisam ser idênticos** nos dois apps (`br.unioeste.pulselab` + debug.keystore do RN) — requisito do Data Layer.
- O receptor no celular precisa do `<data android:scheme="wear" .../>` no intent-filter, senão o GMS descarta a mensagem silenciosamente.

## 5. Pendências conhecidas

- [ ] Validar em device os códigos de `STATUS`/`PROGRESS` do BIA (primeira medição real).
- [ ] Enviar resultado do BIA ao celular (novo path `/sensor/bia` + tipo no `WearBridge` — toca o repo do colega, fazer na branch `hugo/wear-sync`).
- [ ] Medir consumo de bateria do `SensorStreamService` em sessão longa.
- [ ] Decidir cadência de envio (1/s é caro em Bluetooth; considerar lote a cada 5–10 s).
- [ ] Iniciar processo de parceria Samsung antes de qualquer distribuição.
