# Execução de exemplo — 2026-08-03

Primeira execução real da avaliação com `ReplayProvider` + `EvaluationLog`
ponta a ponta (Galaxy Watch Ultra SM-L705F ↔ Galaxy A10s SM-A107M, ambos em
Wi-Fi doméstico, tela do relógio acesa, aparelhos próximos).

- `emitted-replay-20260803-151326.ndjson` — conjunto `S`, gravado no relógio.
- `received-20260803-151327.ndjson` — conjunto `R`, gravado no celular.
- `reconcile-output.txt` — saída de `python3 tools/reconcile.py <emitted> <received>`.

## Contexto

Esta é a **segunda** tentativa do dia. A primeira (192 amostras, não incluída
aqui) acusou 0,52% de perda — exatamente a amostra `sequenceNumber=0` — que se
mostrou ser um artefato do coletor: `armEvaluationCollector` detecta a
primeira amostra do replay e dispara `startEvaluationCollection()` de forma
assíncrona, mas essa função só se inscreve para eventos *futuros* depois de
abrir o arquivo nativo, de modo que a própria amostra que dispara o armamento
nunca era capturada. Corrigido em `evaluationCollector.ts` passando essa
amostra como `seed`, carimbada no instante em que a detecção de armamento a
viu (antes da abertura assíncrona do arquivo). Esta execução, após a
correção, tem 0% de perda.

## O que esta execução mostra

- **Perda: 0%** — nenhuma amostra emitida deixou de ser recebida.
- **Divergência de conteúdo: 0** — todo campo determinístico bateu.
- **Travessia nativo→JS: mediana 9 ms** — a única métrica sem qualquer
  dependência de correção de relógio, por usar dois carimbos do lado do
  celular.
- **Latência fim a fim (não corrigida): mediana 120 ms**, mas com um padrão
  periódico chamativo: picos de ~300–590 ms recorrendo a cada ~6 amostras
  (~6 s), voltando à linha de base em 2–3 amostras. `batchSize` do
  `ReplayProvider` era 1 nesta execução — o padrão **não** vem de
  agrupamento no relógio. Não foi isolada a causa (candidatos: alguma tarefa
  periódica do lado do celular competindo pela thread JS, ou comportamento
  do enlace BLE/Wi-Fi da Data Layer). Registrado como observação para
  investigação futura, não como conclusão.

## Como reproduzir a análise

```
python3 tools/reconcile.py tools/sample-run/emitted-replay-20260803-151326.ndjson \
                            tools/sample-run/received-20260803-151327.ndjson
```
