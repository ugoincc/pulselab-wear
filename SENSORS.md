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
| **SI de Baevsky** | Calculado do mesmo `IBI_LIST` que alimenta o RMSSD — `BaevskyStressIndex.kt` | `SI = AMo / (2·Mo·MxDMn)`, com detrend por média móvel centrada. **Escala bruta, sem `sqrt`** — as faixas de referência de Baevsky (50–150 repouso) descrevem o índice bruto | ✅ validado: 55,5 em repouso |
| **Gate de movimento** | Sensor SDK `ACCELEROMETER_CONTINUOUS` → `MotionGate.kt` | Não é feature de estresse: marca `motionFlag` por janela de 1 s via pico-a-pico da magnitude do vetor. O dado segue sendo enviado e armazenado — o flag só diz ao celular quais janelas excluir do índice. **Exige `ACTIVITY_RECOGNITION`** | ✅ funcional; limiar ainda precisa de calibração com o relógio vestido |
| **Leitura em background** | `SensorStreamService` (foreground service tipo `health`) | Mantém o stream com tela apagada; notificação persistente | ✅ implementado (validar consumo de bateria) |

Por que o índice é composto e não um score de caixa-preta: cada feature acima é
transparente e citável, e o índice final é uma soma ponderada de z-scores (pesos
iguais como ponto de partida), calculada **no celular** — que é onde vive a
baseline por participante. O relógio só produz features.

### Lições da validação em device (SM-L705F, 2026-07-27)

Quatro coisas que só apareceram com o relógio no pulso, todas já corrigidas:

1. **`IBI_STATUS`: `0` = válido, `-1` = inválido** (confirmado; só esses dois
   valores aparecem). Mas o flag do SDK **não basta**: foi observado um intervalo
   de 1300 ms marcado como válido ao lado de vizinhos de ~665 ms — um batimento
   perdido reportado como intervalo dobrado. Como o RMSSD eleva ao quadrado as
   diferenças sucessivas, um único artefato desses levava o RMSSD a 300 ms com FC
   de 100 bpm. Por isso `IbiWindow` aplica, por cima do flag, dois filtros
   clássicos e citáveis: plausibilidade absoluta (300–2000 ms) e regra de Malik
   (Task Force, 1996 — rejeita variação >20% do intervalo anterior aceito).
2. **IBI não chega a 1 Hz.** Chega em lotes esparsos e grandes: 172 de 180
   datapoints vieram com lista vazia e um único trouxe 165 intervalos. O SDK
   carimba o lote inteiro com um só timestamp, então era preciso reconstruir o
   instante de cada intervalo (`ibiEndTimes`) — sem isso o lote entrava e saía da
   janela deslizante em bloco, e a janela pulava de 115 amostras para 4.
3. **O acelerômetro exige `ACTIVITY_RECOGNITION`.** Com só `BODY_SENSORS`, o
   `ACCELEROMETER_CONTINUOUS` aparece na capability mas o tracker falha com
   `PERMISSION_ERROR`. A tela pede a permissão junto da de sensores, mas **não**
   trava nela: sem ela o gate de movimento apenas nunca marca nada.
4. **O `sqrt` do SI era erro de escala.** As faixas publicadas por Baevsky
   descrevem o índice bruto; com a transformação, um repouso de ~44 aparecia como
   6,6 e parecia quebrado. O `sqrt` pertence ao pipeline estatístico offline, onde
   normalidade importa — não ao valor exibido.

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
| **EDA (atividade eletrodérmica)** | `EDA_CONTINUOUS` **não aparece na capability do Galaxy Watch Ultra** (confirmado 2026-07-27). Não é código de status errado nem falha de instanciação: o tracker simplesmente não existe neste hardware | Nenhuma no relógio. O índice composto roda com as features restantes — ver a ressalva abaixo |
| Índice de estresse | Proprietário Samsung (derivado de HRV), sem API pública em nenhum SDK | Índice composto próprio (RMSSD + SI de Baevsky), transparente e citável — ver acima |

> **Ressalva metodológica — importante para a monografia.** Sem o EDA, o índice
> composto fica reduzido a RMSSD e SI de Baevsky, e **as duas são derivadas do
> mesmo sinal de IBI**. Elas não são canais independentes: são dois recortes da
> mesma variabilidade cardíaca. O EDA era justamente o canal que traria ativação
> simpática sem o confundidor vagal que a HRV carrega (é esse o argumento de
> Healey & Picard, 2005, para fusão multimodal). Chamar o resultado atual de
> "multimodal" seria incorreto. Ou se aceita isso explicitamente como limitação,
> ou se busca EDA em outro hardware antes da coleta definitiva.

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

### Validação em device

- [x] ~~Semântica do `IBI_STATUS`~~ — `0` válido, `-1` inválido. Insuficiente
      sozinho; filtros próprios adicionados.
- [x] ~~`EDA_CONTINUOUS` instancia?~~ — não existe na capability deste hardware.
- [x] ~~Acelerômetro~~ — funcional após `ACTIVITY_RECOGNITION`.
- [x] ~~Faixas do SI~~ — 55,5 em repouso, dentro de 50–150 na escala bruta.
- [ ] **Recalibrar `MotionGate.DEFAULT_THRESHOLD`** com o relógio *vestido*. A
      medição de 2026-07-27 saiu bimodal (p50 = 0 parado, ~400 manipulado) com o
      relógio apoiado numa mesa; um pulso real tem piso diferente de zero.
- [ ] Investigar a taxa de rejeição de IBI (~40% na primeira sessão). Pode ser
      qualidade normal de PPG de pulso ou ajuste da pulseira — comparar entre
      sessões antes de mexer no limiar de Malik.
- [ ] `SensorStreamService` registrou um `JobCancellationException` seguido de
      reinício automático numa das rodadas. Não se repetiu; vigiar em sessão longa.

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
