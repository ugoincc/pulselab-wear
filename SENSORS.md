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
5. **RMSSD não pode atravessar vãos.** É a raiz da média dos quadrados das
   diferenças *sucessivas*, então cada par somado precisa ser dois batimentos que
   de fato se seguiram. Um vão na série — artefato filtrado, ou o silêncio
   periódico do sensor (§4.3) — deixa os batimentos dos dois lados adjacentes na
   lista sem serem adjacentes no coração, e o par inventa uma diferença que nunca
   foi observada. `IbiWindow.currentRuns()` quebra a janela em trechos de
   batimentos realmente consecutivos (`atMs[i] − atMs[i−1] ≈ ibiMs[i]`, folga de
   `SUCCESSION_SLACK_MS`) e `rmssdOverRuns` agrupa as diferenças de todos eles —
   uma por par legítimo, nenhuma atravessando buraco. Agrupar em vez de tirar
   média por trecho mantém todo par com o mesmo peso, senão um trecho de dois
   batimentos pesaria tanto quanto um de cinquenta. Tratamento padrão de edição de
   artefatos (Task Force, 1996). O SI não precisa disso: sai de um histograma de
   comprimentos, que não depende da ordem dos batimentos.

   **A primeira versão da quebra estava errada** e vale registrar por quê. Ela
   testava a sucessão exatamente, perguntando se `atMs[i] − atMs[i−1]` batia com
   `ibiMs[i]`. Em dados reais isso desaba: o SDK entrega **um intervalo por
   datapoint em 95 % das vezes** (247 de 261 medidos), e os datapoints são
   carimbados de 1000 em 1000 ms enquanto os batimentos distam ~750 ms. O
   `ibiEndTimes` ancora cada lote no timestamp do datapoint, que é quando ele foi
   *entregue*, não quando o último batimento terminou — então a conta nunca
   encadeia entre lotes. Cada batimento virou seu próprio trecho e o RMSSD
   congelou. A regra agora é grossa de propósito: quebra quem sabe que perdeu
   batimento (os filtros, e o `noteDropped()` para os que o SDK marca inválidos) e
   um silêncio maior que `RUN_BREAK_GAP_MS`, que é o dropout de ~11 s.

   Medido depois da correção (relógio vestido, repouso, 5 min, amostrado uma vez
   por segundo de relógio de parede): n = 487, p25 = 51, p50 = 57, p75 = 57,
   máx = 61 ms, 50 valores distintos. Faixa fisiológica e distribuição saudável.

   **Armadilha de medição, de novo:** as duas primeiras leituras deram "um único
   valor" e pareciam degeneradas. Não eram — eu agregava um *burst* de entrega, em
   que centenas de linhas saem no mesmo instante com a mesma janela. Contar linhas
   de log não é contar tempo. Amostrar por segundo de relógio de parede resolve.

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
| **EDA (atividade eletrodérmica)** | **Exige Galaxy Watch8 ou mais novo.** O Ultra (2024, SM-L705F) é geração Watch7 — o sensor não existe nele. Confirmado nos dois sentidos: `EDA_CONTINUOUS` não aparece na capability do aparelho, e o codelab oficial exige "Galaxy Watch8 or newer" | Nenhuma neste hardware. Ver a ressalva metodológica abaixo |
| Índice de estresse | Proprietário Samsung (derivado de HRV), sem API pública em nenhum SDK | Índice composto próprio (RMSSD + SI de Baevsky), transparente e citável — ver acima |

> **Ressalva metodológica — importante para a monografia.** Sem o EDA, o índice
> composto fica reduzido a RMSSD e SI de Baevsky, e **as duas são derivadas do
> mesmo sinal de IBI**. Elas não são canais independentes: são dois recortes da
> mesma variabilidade cardíaca. O EDA era justamente o canal que traria ativação
> simpática sem o confundidor vagal que a HRV carrega (é esse o argumento de
> Healey & Picard, 2005, para fusão multimodal). Chamar o resultado atual de
> "multimodal" seria incorreto — o correto é descrevê-lo como um índice composto
> **no domínio da HRV**, com duas estatísticas complementares (uma de dispersão
> de curto prazo, outra de concentração do histograma) sobre o mesmo sinal.
>
> Decisão tomada em 2026-07-27: **aceitar como limitação declarada**, sem trocar
> de hardware. O código do EDA permanece na árvore, dormente e ligado ponta a
> ponta (`EdaWindow`, tracker, campos `edaTonic`/`edaScrPerMin` no payload,
> feature no app do celular) — o que falta é sensor, não código. Num Watch8 a
> feature volta sozinha, e o celular já renormaliza os pesos conforme as features
> presentes.

## 2.1 Faixas de interpretação

Referência rápida para leitura em campo. Duas advertências antes de usar:

- **O que interessa é o desvio da própria baseline, não o valor absoluto.** As
  faixas abaixo são de literatura populacional e variam muito entre pessoas
  (idade, condicionamento, medicação). O índice composto existe justamente por
  isso: ele compara cada participante com o próprio repouso.
- Valores fora da faixa **não são diagnóstico**. Este app não tem — nem busca —
  qualquer aprovação regulatória.

| Métrica | Repouso típico | Anormal / atenção | Como ler |
|---|---|---|---|
| **FC** | 60–100 bpm | <50 ou >120 sem esforço físico | Sobe com estresse agudo, mas é o sinal menos específico de todos: café, postura e conversa mexem nela |
| **HRV (RMSSD)** | 20–90 ms (adulto saudável, janela de 60 s) | Queda >30% da baseline pessoal sustentada | **Cai** sob dominância simpática — por isso entra invertida no índice. Valores >150 ms quase sempre são artefato, não relaxamento |
| **SI de Baevsky** | 50–150 (escala bruta) | >500 = forte dominância simpática; <25 pode indicar janela ruidosa demais | Sobe rápido sob estresse. Muito mais sensível que o RMSSD isolado, e por isso mais sujeito a falso positivo com artefato |
| **SpO2** | 95–100% | <90% de forma sustentada | Não é métrica de estresse; entra como contexto/qualidade de sinal. Medição de pulso é sensível a ajuste da pulseira |
| **Temp. da pele** | 30–35 °C no pulso | Saltos >2 °C em minutos | Bem abaixo da temperatura central. Responde mais ao ambiente que ao estado emocional — ler como contexto, não como estresse |
| **Índice composto** | ~0 (igual à baseline) | >+0,5 elevado, <-0,5 baixo | Em desvios-padrão da própria baseline. Os cortes em ±0,5 são **convenção de leitura, não resultado validado** |
| **motionFlag** | falso a maior parte do tempo | true persistente | Não é medida: janela contaminada por movimento. Se estiver quase sempre true, recalibrar o limiar antes de confiar em qualquer outra métrica |

**Taxa de rejeição de IBI** é a métrica de qualidade que não aparece na tela mas
sai no Logcat (`N descartados`). Até ~20% é normal em PPG de pulso; acima de 40%
sustentado indica pulseira frouxa ou posição ruim, e as features de HRV daquela
sessão devem ser tratadas com desconfiança.

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

### 4.1 Entrega em rajadas com a tela apagada — medido e resolvido

Com a tela apagada (`mWakefulness=Dozing`) a plataforma **não entrega em tempo
real**: ela acumula e descarrega em rajadas. Medição de 2026-07-27, relógio
vestido, ciclo de 30 s aceso / 95 s apagado:

| Rajada | Amostras | Idade da mais antiga |
|---|---|---|
| 20:19:41 | 38 | 17,6 s |
| 20:20:41 | 66 | 43,6 s |
| 20:21:09 | 50 | 25,6 s |
| 20:21:42 | 14 | 27,5 s |

O `PARTIAL_WAKE_LOCK` do foreground service não impede — quem enfileira é a
plataforma, não o nosso processo.

**Os dados não são corrompidos.** A pergunta decisiva era se o timestamp de cada
amostra é o instante real de aquisição ou o da entrega. Instrumentamos `lagMs`
(distância entre o timestamp da amostra e o relógio de parede) e dentro de cada
rajada ele decresce em passos de **exatamente 1000 ms** (27549 → 26550 → 25550 →
… → 552 → 542). Ou seja: o sensor amostra continuamente a 1 Hz e carimba cada
amostra com o instante verdadeiro; só a *entrega* é adiada.

Consequências práticas:

- **Para o tempo real: sim.** O índice no celular pode ficar desatualizado
  enquanto a tela está apagada — o maior atraso já medido é de **61,8 s**
  (2026-07-28). É limite de plataforma, não bug.
- **Para o transporte: corrigido em 2026-07-28.** Uma mensagem Data Layer por
  amostra virava uma rajada de ~66 mensagens em menos de 1 s. Agora sai uma
  mensagem por rajada — ver §4.2.

### 4.2 Envio em lote — validado ponta a ponta (2026-07-28)

`SamsungSensorMonitor.readingFlow()` emite `List<SensorReading>` por callback do
SDK, e o callback **é** a fronteira da rajada: preservá-la é deixar a plataforma
ditar a cadência em vez de inventar um intervalo por cima de um atraso que não
controlamos. Uma mensagem `/sensor/batch` por rajada, com `{"samples":[...]}`.

Medido no relógio vestido, com o celular pareado:

| Situação | Mensagens | Maior lote | Maior `lagMs` |
|---|---|---|---|
| Tela acesa | 1/s | 1 amostra (147 B) | −0,2 s |
| Uso misto, 5 min | 7 | 55 amostras (7548 B) | 61,8 s |
| Uso misto, 6 min | 23 | 20 amostras | 18,8 s |

No celular, `WearBridgeModule.emitBatch()` desempacota o lote em eventos
`onHeartRate` individuais, então nada em TypeScript precisou mudar. Confirmado
com os dois apps rodando: o card "Biometria do relógio" mostrou BPM 79, HRV 45,
SI 119, SpO2 94, Temp 35,0 °C com o indicador "Ao vivo".

### 4.3 A série tem buracos periódicos de ~11 s por minuto — e não é a tela

A série entregue **não** é 1 Hz contínuo. Mas a causa não é nenhuma das duas que
se suspeitou primeiro (nem a tela dormente, nem o transporte), e vale registrar o
caminho todo porque cada etapa errada tinha uma aparência convincente.

**O instrumento de medida errado.** As primeiras contagens saíram de
`adb logcat -d` depois de esperar N minutos. O buffer `main` do relógio tem 1 MiB
e vive saturado pelo próprio sistema: um dump de 8 minutos devolveu só o último
minuto. Toda taxa calculada assim ("52 callbacks em 360 s", "0,14 Hz") media
rotação de buffer, não fisiologia. **Para contar amostras, ou se transmite o
logcat para arquivo (`adb logcat ... > arquivo`, sem `-d`), ou se conta no NDJSON
do celular** — que é o registro real e o que interessa para a pesquisa.

**O que os dados de verdade dizem** (NDJSON de 2026-07-28, 1530 amostras,
09:58→10:41, e um streaming contínuo de 444 s):

| Medida | Valor |
|---|---|
| Callbacks do SDK | 446 em 444 s — **1,00 Hz, sem falhar** |
| Destes, com `HR_STATUS_VALID` | 373 (84 %) |
| Os outros 73 | `HR=0 status=0`, lista de IBI vazia |
| Intervalos de 1 s exato entre amostras entregues | 1471 de 1523 |
| Buracos de 5–30 s | 40, mediana **11,0 s**, espaçados por mediana de **60 s** |
| Cobertura na janela limpa da baseline | 244/300 = 81 % |

O sensor **nunca para de amostrar** e o `lagMs` fica em −215 ms o tempo todo
durante os buracos: não há atraso, não há rajada, não há nada preso no
transporte. O que acontece é que, por ~11 s a cada minuto, o tracker de FC
responde `status=0` com IBI vazio — está medindo e não produz resultado válido. E
o nosso código, corretamente, só emite `SensorReading` quando o status é válido.
O buraco é do sensor, não nosso.

**Suspeito principal: o tracker de temperatura de pele.** Atravessando um buraco,
a temperatura mudou de valor em **32 %** dos casos, contra uma taxa base de
**1,4 %** entre amostras consecutivas de 1 s — enriquecimento de ~23×. O SpO2
também aparece (20 % contra 8,1 % de base), bem mais fraco. A leitura mais
provável é que a medição de temperatura preempta o PPG: os sensores compartilham
o mesmo caminho óptico/térmico e o SDK serializa.

Isto é correlação, não prova. **O teste que decide** é desligar o tracker de
temperatura e medir de novo: se o buraco periódico sumir, está resolvido, e a
escolha passa a ser explícita — temperatura a 1/min ou HRV sem furos, não os dois.

Consequência para a pesquisa, hoje: as séries perdem ~18 % de cada minuto de
forma **regular e previsível**, não aleatória. Qualquer estatística por janela
precisa contar amostras em vez de assumir `n = duração × 1 Hz` (a baseline de
5 min juntou 244 amostras, não 300). Os dados que existem são verdadeiros e bem
carimbados; o que cai é a premissa de amostragem uniforme.

Sobre a §4.1: a conclusão de 2026-07-27 de que os timestamps são instantes reais
de aquisição **continua válida** e foi confirmada. O que ela não sustentava era a
frase seguinte, "nada se perde com a tela apagada" — perde-se, só que por um
motivo que não tem a ver com a tela.

### 4.4 Requisitos do Data Layer

- Package name e assinatura **precisam ser idênticos** nos dois apps (`br.unioeste.pulselab` + debug.keystore do RN) — requisito do Data Layer.
- O receptor no celular precisa do `<data android:scheme="wear" .../>` no intent-filter, senão o GMS descarta a mensagem silenciosamente.
- O `pathPrefix="/sensor"` do intent-filter já cobre `/sensor/batch`; o path novo
  (em vez de mudar o formato em `/sensor/data`) faz um app desatualizado deixar de
  receber, em vez de ler um array como se fosse uma amostra. Falhar em silêncio é
  melhor que falhar errado.

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
      Primeira amostra no pulso (2026-07-28, braço parado, n = 39): p50 = 34,
      p90 = 38, máx = 155 — o piso realmente não é zero, e o limiar de 200
      classificou tudo como parado, como devia. Falta o outro lado da bimodal:
      uma sessão com movimento deliberado (digitar, caminhar, gesticular) para
      saber onde fica o vale entre as duas modas. Só então o 200 deixa de ser
      chute.
- [x] ~~Taxa de rejeição de IBI de ~40%~~ — **o número não existia**. O log
      mostrava `over 40 IBIs (38 descartados)`, mas `descartados` é acumulado
      desde o início da sessão e `40` é a janela deslizante de 60 s: dividir um
      pelo outro não é uma taxa de nada. O log agora imprime
      `descarte X% acumulado: r/(a+r)`, comparando iguais com iguais. Taxa real
      medida em 2026-07-28, relógio vestido e parado: **12 % (15/123)** — normal
      para PPG de pulso. O limiar de Malik fica como está.
- [ ] `SensorStreamService` registrou um `JobCancellationException` seguido de
      reinício automático numa das rodadas. Não se repetiu; vigiar em sessão longa.
- [x] ~~Enviar em lote ao celular~~ — uma mensagem por rajada, validada ponta a
      ponta em 2026-07-28 (§4.2). Maior lote observado: 55 amostras em 7548 B.
- [x] ~~Lacunas na série~~ — não é a tela nem o transporte: o tracker de FC
      responde `status=0` por ~11 s a cada minuto (§4.3).
- [ ] **Confirmar que o tracker de temperatura causa o buraco periódico**:
      desligá-lo e remedir. **Adiado por decisão do orientando (2026-07-28)**: a
      temperatura não entra no índice composto. Vale registrar a ressalva de que a
      pergunta não era sobre o valor da temperatura como medida, e sim sobre o
      preço do tracker dela — ~18 % da série de HRV, que entra no índice. Se em
      algum momento a cobertura da HRV virar um problema, este é o primeiro lugar
      para olhar.
- [ ] **Baseline capturada em 2026-07-28** (RMSSD 50,6 ± 8,5; SI 74,1 ± 13,4;
      n = 231): é de uma sessão só e de um participante, e foi capturada **antes**
      da correção do RMSSD nos vãos. Os valores de RMSSD dela saíram de uma
      janela que ainda somava pares atravessando buraco, então a média está
      levemente inflada. Recapturar depois de confirmar a correção em device.
- [x] ~~Confirmar a correção do RMSSD em device~~ — p50 = 57 ms em repouso, 50
      valores distintos, distribuição saudável (§2.1, item 5).

### Operacional

- [ ] Medir consumo de bateria com EDA + acelerômetro contínuos rodando junto de HR/IBI/SpO2/temperatura.
- [ ] Decidir cadência de envio (1/s é caro em Bluetooth; considerar lote a cada 5–10 s — agora com 9 campos por payload em vez de 6).
- [ ] Iniciar processo de parceria Samsung antes de qualquer distribuição (vale para EDA e acelerômetro também).

### Retomar depois

- [ ] **BIA indisponível no Galaxy Watch Ultra via SDK**: a capability lista `BIA_ON_DEMAND`, mas `getHealthTracker(BIA_ON_DEMAND)` lança `Invalid HealthTrackerType`. Código mantido na árvore, sem entrada na UI. Investigar: pode exigir outra forma de instanciar, versão de Health Platform, ou ser recorte do modelo/região.
- [ ] Enviar resultado do BIA ao celular (novo path `/sensor/bia` + tipo no `WearBridge` — toca o repo do colega, fazer na branch `hugo/wear-sync`).

### Lado do celular (Parte 2 — repo `pulselab-companion`)

- [x] ~~Estender `HeartRateEvent`~~ — campos novos no evento existente, sem segundo listener.
- [x] ~~UI de captura de baseline + índice composto~~ — modal de 5 min, z-score com
      renormalização de peso sobre as features disponíveis.
- [x] ~~Storage local~~ — NDJSON append-only via `expo-file-system`, já presente no
      projeto. Nenhuma dependência nova no repo do colega.
- [ ] **Rodar a captura de baseline de verdade.** O fluxo está instalado e
      recebendo dados ao vivo, mas os 5 min de repouso nunca foram gravados — sem
      isso o índice composto não tem referência e os cortes de faixa (±0,5)
      seguem provisórios.
