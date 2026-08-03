# Handoff: Auditoria do estado atual do protocolo Wear OS → app companion (para o TCC)

## Seu papel neste documento

Você tem acesso direto ao repositório e ao histórico de commits/conversas do projeto PulseLab. Este documento **não pede implementação de código novo**. Ele pede uma auditoria: leia o que já existe e me devolva um relatório objetivo respondendo às perguntas da Seção 3, com trechos de código citados (arquivo + linha) sempre que possível.

Não infira, não complete lacunas com suposições, e não "corrija" nada que pareça incompleto. Se algo não existir, a resposta certa é "não existe" — não é inventar uma versão provável dele. Isso é importante porque o Hugo e o Claude (chat) vão usar seu relatório para decidir juntos os próximos passos de projeto; qualquer suposição sua vira uma decisão de projeto tomada sem que ninguém tenha decidido.

---

## 1. Contexto: o que é o PulseLab e o que é o TCC

O **PulseLab** é um sistema de monitoramento biométrico passivo de day traders durante sessões de negociação, para pesquisa em psicologia do estresse. A arquitetura completa envolve: Galaxy Watch Ultra → Samsung Health Sensor SDK → app Kotlin no relógio → `ChannelClient` → app React Native no celular → backend (Railway/Express/Prisma/PostgreSQL) → dashboard. Esse projeto continua em desenvolvimento e não está sendo pausado.

O **TCC do Hugo**, no entanto, teve seu escopo formalmente reduzido. Isso é uma decisão já tomada (informalmente aprovada pelo orientador, Prof. Renato Bobsin Machado; formalização por escrito em andamento). O objeto de estudo da monografia passou a ser **apenas** a camada de comunicação entre o relógio e o app companion — não o PulseLab inteiro.

Se você encontrar no repositório outros documentos de handoff (ex: `GUIA-indices-risco-psicologico-trader.md`, `HANDOFF-indices-instantaneos-fase1.md`, `HANDOFF-indice-estresse-composto.md`), eles descrevem trabalho do PulseLab como projeto — mas **não são o objeto do TCC**. Cite-os no seu relatório se forem relevantes para entender o payload atual, mas não os trate como escopo a ser avaliado.

## 2. O que foi decidido para o TCC (resumo das decisões já tomadas)

Estas decisões já foram tomadas em conversas entre Hugo e o Claude (chat). Não são perguntas em aberto — são o direcionamento atual:

- **Fronteira do escopo:** o objeto termina no momento em que o payload chega à **camada JavaScript** do app React Native (via `ChannelClient` → módulo nativo → `NativeEventEmitter` → JS). A travessia da ponte nativa faz parte do que precisa ser medido, não é um corte anterior a ela.
- **Fora do escopo do TCC** (mas ainda desenvolvido como parte do PulseLab): persistência em SQLite/backend, cálculo dos índices psicológicos de risco (IRPT, CompositeStress etc.), e qualquer aplicação cliente downstream que consuma esse payload para calcular índices e utilizá-los em contexto próprio.
- **Abstração de provedores decidida:** o protocolo deve ser genérico o suficiente para funcionar sobre múltiplos provedores de sensores, não apenas o Samsung Health Sensor SDK. Três provedores foram planejados:
  1. **Samsung Health Sensor SDK** — provedor principal, sinal bruto (PPG, IBI, EDA, acelerômetro, temperatura de pele). É o que já está em desenvolvimento.
  2. **Health Services** (API nativa Android/Wear OS) — provedor secundário, com capacidades bem mais limitadas (dados agregados, sem IBI bruto nem EDA). Ainda **não existe** no projeto — planejado, não implementado.
  3. **Provedor de replay/sinal sintético** — injeta sinal previamente conhecido no lugar do sensor real, para servir de instrumento de avaliação (verdade fundamental conhecida para medir perda/latência/deriva). Ainda **não existe** — planejado, não implementado.
- **Health Connect foi excluído** como provedor de aquisição (decisão final, não em aberto). Motivo: opera em outra camada arquitetural (agregador/repositório de dados já processados, não captura de sinal em tempo real), e tem latência de sincronização incompatível com o cenário. Pode aparecer como possível destino de exportação futura, mas não como fonte de dado.
- **Instrumentação necessária, ainda não implementada:** número de sequência monotônico por amostra e timestamp de origem no relógio, separados do timestamp de recepção no celular. Confirmado por Hugo que **nenhum dos dois existe hoje**.
- **Especificação do payload:** hoje existe apenas de forma **informal**, em algum documento de handoff (Hugo mencionou ter algo assim, mas não especificou qual arquivo). Uma das coisas que preciso que você localize é exatamente esse documento.

## 3. O que preciso que você responda

Organize sua resposta seguindo esta numeração, para eu conseguir referenciar cada resposta de volta.

### 3.1 — Localização e conteúdo do schema/payload atual
- Onde exatamente o formato do payload está definido hoje? Liste todos os arquivos relevantes (data classes Kotlin, interfaces/types TypeScript, e qualquer `.md` de handoff que descreva o formato).
- Copie (não resuma) as definições de tipo/estrutura atuais do payload, com caminho de arquivo e número de linha.
- Existe algum documento de handoff `.md` no repositório que já descreva esse payload de forma mais formal/legível do que o código? Se sim, qual arquivo, e cole o conteúdo relevante.

### 3.2 — Metadados de proveniência e qualidade já existentes
- Confirme se o indicador de erro no IBI (mencionado como -1 para erro, 0 para normal) existe de fato no código, e onde.
- Existe algum outro campo de qualidade/proveniência (flag de confiança, indicador de interpolação, etc.) em qualquer sensor? Liste todos, com localização.

### 3.3 — Instrumentação temporal e de sequência
- Existe, em qualquer lugar do pipeline relógio → celular, um número de sequência por amostra? Se sim, onde.
- Existe um timestamp de origem gerado no relógio (não confundir com o timestamp de recepção no celular)? Se sim, onde, e qual é a fonte do timestamp (`System.currentTimeMillis()`, `SensorEvent.timestamp`, outro)?
- Se nenhum dos dois existir, confirme explicitamente isso.

### 3.4 — Estrutura da travessia relógio → celular
- Descreva o caminho exato dos dados desde o callback do Samsung Health Sensor SDK até o ponto em que chegam à camada JS, com arquivos e nomes de função/classe em cada etapa (ex: callback do SDK → `ChannelClient` → módulo nativo Kotlin → `NativeEventEmitter` → listener JS).
- Existe hoje algum ponto de abstração/interface já definido que separe "origem do dado" (provedor) de "consumo do dado" (o resto do app)? Se sim, descreva a interface e onde ela está definida. Se não existir nenhuma abstração desse tipo (mesmo que o `BaselineProvider` exista para outra finalidade — cálculo de baseline, não origem de sensor —, deixe isso explícito e não confunda os dois).

### 3.5 — `ValueKey` e enums do Samsung Health Sensor SDK
- Este é um item já sinalizado como pendente antes: confirme os valores exatos de `ValueKey` para `EDA_CONTINUOUS` e `ACCELEROMETER_CONTINUOUS` (e quaisquer outros `ValueKey` já em uso) diretamente contra o `.aar` do SDK, não por suposição. Cole os nomes exatos como aparecem decompilados ou na documentação embarcada do `.aar`.
- Confirme se houve, no histórico do projeto, alguma falha do tipo `Invalid HealthTrackerType` (mencionada anteriormente em relação a BIA) e se isso já foi resolvido ou permanece um problema aberto.

### 3.6 — Testes, benchmarks ou instrumentação de avaliação existentes
- Existe qualquer script, teste automatizado ou ferramenta que já meça latência, throughput ou perda de amostras entre relógio e celular? Se sim, onde, e o que ele mede hoje.
- Se não existir nada, confirme isso explicitamente.

### 3.7 — Estado do Health Services e do provedor de replay
- Confirme que não há, hoje, nenhuma implementação (nem esqueleto) usando Health Services no repositório.
- Confirme que não há, hoje, nenhuma implementação (nem esqueleto) de um provedor de replay/sinal sintético.

---

## 4. Formato esperado da sua resposta

- Markdown, seguindo a numeração das perguntas acima.
- Toda afirmação sobre existência de algo deve vir com caminho de arquivo (e linha, quando aplicável).
- Toda afirmação sobre inexistência de algo deve ser explícita ("não encontrado em nenhum arquivo do repositório"), não silenciosa.
- Não proponha soluções, não sugira código novo, não refatore nada. Este documento é só para eu (e o Claude no chat) termos uma fotografia precisa do estado atual antes de decidirmos os próximos passos juntos.
