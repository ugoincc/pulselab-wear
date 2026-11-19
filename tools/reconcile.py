#!/usr/bin/env python3
"""
Concilia o registro de emissão do relógio com o de recepção do celular e
calcula as métricas da avaliação (cap6-avaliacao.tex §6.2).

Uso:
    python3 tools/reconcile.py emitted-replay-*.ndjson received-*.ndjson
    python3 tools/reconcile.py S.ndjson R.ndjson --skew-ms -120

Roda fora dos dispositivos, de propósito: processamento estatístico competindo
por recursos com o sistema sob medição contamina o que se está medindo.

Sobre o desvio de relógio (--skew-ms): a diferença entre o carimbo de recepção
e o de aquisição contém a latência real E a deriva entre dois relógios
independentes. Sem uma estimativa do desvio, a latência absoluta reportada aqui
não é latência — por isso ela sai marcada como não corrigida. Perda e jitter
não dependem disso e saem sempre válidas.
"""

import argparse
import json
import statistics
import sys

# Campos que compõem a série determinística do ReplayProvider. Os de relógio de
# parede (timestamp, *MeasuredAt, emittedAt) ficam de fora: não são
# reprodutíveis entre execuções, e compará-los acusaria divergência em 100% das
# amostras — ver ReplaySeries.kt.
CONTENT_FIELDS = [
    "bpm", "hrv", "baevskySI", "spo2", "skinTemp",
    "edaTonic", "edaScrPerMin", "motionFlag",
    "schemaVersion", "providerId",
]


def load(path):
    """Carrega NDJSON, ignorando linha final truncada por processo morto."""
    out = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"aviso: {path}:{n} ilegível, ignorada", file=sys.stderr)
    return out


def key(sample):
    """Identidade da amostra: provedor + número de sequência (§5 da spec)."""
    return (sample.get("providerId"), sample.get("sequenceNumber"))


def reconcile(emitted, received, skew_ms=None):
    by_seq_emitted = {key(s): s for s in emitted}
    by_seq_received = {key(s): s for s in received}

    emitted_keys = set(by_seq_emitted)
    received_keys = set(by_seq_received)
    delivered = emitted_keys & received_keys
    lost = emitted_keys - received_keys
    # Recebido sem ter sido emitido: não deveria acontecer; se acontecer é
    # arquivo de outra execução misturado, não fenômeno de transporte.
    spurious = received_keys - emitted_keys

    # --- Latência fim a fim -------------------------------------------------
    latencies = []
    for k in sorted(delivered, key=lambda x: x[1]):
        r = by_seq_received[k]
        acquired = r.get("timestamp")
        arrived = r.get("jsReceivedAt")
        if acquired is None or arrived is None:
            continue
        value = arrived - acquired
        if skew_ms is not None:
            value -= skew_ms
        latencies.append((k[1], value))

    # --- Variação da latência (RFC 3393): diferenças sucessivas -------------
    jitter = [b[1] - a[1] for a, b in zip(latencies, latencies[1:])]

    # --- Travessia nativo→JS, isolada ---------------------------------------
    native_to_js = [
        r["jsReceivedAt"] - r["phoneReceivedAt"]
        for r in received
        if r.get("jsReceivedAt") is not None and r.get("phoneReceivedAt") is not None
    ]

    # --- Reordenamento -------------------------------------------------------
    seqs = [s.get("sequenceNumber") for s in received]
    reordered = sum(1 for a, b in zip(seqs, seqs[1:]) if b is not None and a is not None and b < a)

    # --- Divergência de conteúdo --------------------------------------------
    divergent = []
    for k in delivered:
        e, r = by_seq_emitted[k], by_seq_received[k]
        diff = {f: (e.get(f), r.get(f)) for f in CONTENT_FIELDS if e.get(f) != r.get(f)}
        if diff:
            divergent.append((k[1], diff))

    return {
        "emitted": len(emitted_keys),
        "received": len(received_keys),
        "delivered": len(delivered),
        "lost": sorted(s for _, s in lost),
        "spurious": sorted(s for _, s in spurious),
        "latencies": latencies,
        "jitter": jitter,
        "native_to_js": native_to_js,
        "reordered": reordered,
        "divergent": divergent,
    }


def summarise(m, skew_ms):
    def stats(values, unit="ms"):
        if not values:
            return "sem dados"
        return (f"n={len(values)} min={min(values)}{unit} "
                f"mediana={statistics.median(values):.1f}{unit} "
                f"max={max(values)}{unit}")

    print("=" * 66)
    print("CONCILIAÇÃO DA EXECUÇÃO")
    print("=" * 66)
    print(f"emitidas (S):  {m['emitted']}")
    print(f"recebidas (R): {m['received']}")
    print(f"entregues:     {m['delivered']}")

    loss = len(m["lost"]) / m["emitted"] if m["emitted"] else 0
    print(f"\nTaxa de perda: {loss:.2%}  ({len(m['lost'])} amostras)")
    if m["lost"]:
        print(f"  sequências perdidas: {m['lost'][:20]}"
              + (" ..." if len(m["lost"]) > 20 else ""))
    if m["spurious"]:
        print(f"  ATENÇÃO recebidas sem emissão correspondente: {m['spurious'][:20]}")
        print("  (registros de execuções diferentes? não é perda nem transporte)")

    print(f"\nReordenamento: {m['reordered']} ocorrência(s)")

    marca = "corrigida" if skew_ms is not None else "NÃO CORRIGIDA — contém deriva de relógio"
    print(f"\nLatência fim a fim ({marca}):")
    print(f"  {stats([v for _, v in m['latencies']])}")

    print("\nVariação da latência (diferenças sucessivas, RFC 3393):")
    print(f"  {stats(m['jitter'])}")

    print("\nTravessia nativo→JS (imune a deriva: mesmo relógio nas duas pontas):")
    print(f"  {stats(m['native_to_js'])}")

    print(f"\nDivergência de conteúdo: {len(m['divergent'])} amostra(s)")
    for seq, diff in m["divergent"][:5]:
        print(f"  seq {seq}: {diff}")
    print("=" * 66)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("emitted", help="NDJSON de emissão, do relógio (conjunto S)")
    ap.add_argument("received", help="NDJSON de recepção, do celular (conjunto R)")
    ap.add_argument("--skew-ms", type=float, default=None,
                    help="desvio estimado do relógio do celular em relação ao do "
                         "relógio, em ms; sem isto a latência absoluta não é "
                         "interpretável")
    args = ap.parse_args()

    emitted, received = load(args.emitted), load(args.received)
    if not emitted:
        sys.exit("registro de emissão vazio — nada a conciliar")

    summarise(reconcile(emitted, received, args.skew_ms), args.skew_ms)


if __name__ == "__main__":
    main()
