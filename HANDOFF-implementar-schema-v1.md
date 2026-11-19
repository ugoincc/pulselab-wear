# Handoff: implementar schema v1 + SamsungSensorProvider

## Diferente dos handoffs anteriores

Os handoffs anteriores pediram só auditoria — "não implemente nada". **Este
pede implementação.** A especificação anexa (`ESPECIFICACAO-protocolo-payload-v1.md`)
é a fonte de verdade — implemente contra ela, não contra o que você acha
que faria mais sentido. Se algo na especificação parecer ambíguo ou
incompleto pra implementar, pare e pergunte em vez de decidir sozinho.

## Escopo desta iteração (não passe disso)

**Fazer:**
1. Criar a interface `SensorProvider` e `CapabilitySet` exatamente como na
   Seção 6.2/6.3 da especificação, em `wear/src/main/java/br/unioeste/pulselab/wear/data/provider/`.
2. Estender `SensorReading`/`SensorBundle` com os campos novos da Seção 4:
   `schemaVersion`, `sequenceNumber`, `spo2MeasuredAt`, `skinTempMeasuredAt`,
   `providerId`. (`phoneReceivedAt` é do lado do celular — item 5.)
3. Implementar `sequenceNumber`: monotônico, incrementado por `SensorReading`
   emitida (não por lote), reiniciando em `0` a cada reconexão do
   `MessageClient`/`NodeClient` (Seção 5 da especificação).
4. Capturar `spo2MeasuredAt`/`skinTempMeasuredAt` no momento em que
   `spo2Listener`/`skinTempListener` atualizam `latestSpo2`/`latestSkinTemp`
   em `SamsungSensorMonitor.kt` — não no momento em que a emissão de HR
   acontece.
5. No lado do celular, capturar `phoneReceivedAt` dentro de
   `WearableHeartRateListenerService.onMessageReceived`, no momento em que
   a mensagem é processada.
6. Decidir e documentar (na própria implementação, um comentário basta) como
   `providerId` e `capabilities` são transmitidos — a especificação diz que
   `capabilities` vai uma vez por conexão, não por amostra, mas não fixa o
   mecanismo. Sugestão: um segundo `MessagePath` (ex. `/sensor/capabilities`)
   enviado uma vez ao conectar, separado de `/sensor/batch`. Se preferir
   outra abordagem, justifique brevemente.
7. Criar `SamsungSensorProvider : SensorProvider`, envolvendo
   `SamsungSensorMonitor` como está — sem alterar `SensorConnectionManager`.
   `capabilities()` retorna tudo `true` exceto `providesEda` (Seção 6.4).
8. Atualizar `WearBridge.ts` (tipo TypeScript) com os campos novos.

**Não fazer nesta iteração:**
- Não implementar `HealthServicesProvider` nem `ReplayProvider` — próxima
  rodada, handoff separado.
- Não mexer em nenhum arquivo `.tex` da monografia — fora do seu escopo.
- Não remover nem renomear campos existentes (`bpm`, `hrv`, `spo2`, etc.) —
  só adicionar os novos.
- Não adicionar timestamp próprio para `motionFlag` — decisão já tomada de
  não fazer isso (Seção 4 da especificação, nota final).

## Antes de considerar terminado

- Compila (`./gradlew build` nos dois repositórios, se aplicável).
- Nenhum campo novo quebra o consumo existente do lado do celular (campos
  novos devem ser aditivos — quem já lia o payload antigo não deveria
  quebrar só por causa dos campos novos).
- Devolva um resumo do que foi feito, arquivo por arquivo, igual ao padrão
  dos relatórios de auditoria anteriores — não preciso do diff inteiro, mas
  preciso saber exatamente o que mudou e onde, para eu conferir contra a
  especificação antes de fecharmos essa rodada.
