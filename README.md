# Interactive Brokers (TWS + quanta-ib)

This repo packages Interactive Brokers TWS for NixOS and provides
`quanta-ib`, a Clojure blotter/quote adapter for
[quanta-blotter](https://github.com/clojure-quant/quanta-blotter) using the
TWS socket API (V100 framing + protobuf via [protojure](https://protojure.readthedocs.io/)).

## Layout

- Nix TWS package: `flake.nix`, `package.nix` (`nix run .`)
- `quanta-ib/` — Clojure library (`io.github.clojure-quant/quanta-ib`)
- `demo/` — quote print demo against paper TWS

## TWS (Nix)

Uses IBKR's Offline Stable standalone installer:

```text
https://download2.interactivebrokers.com/installers/tws/stable-standalone/tws-stable-standalone-linux-x64.sh
```

```console
nix run .
```

Writable TWS config lives in `~/.tws`. Enable the API in TWS
(Configure → API → Settings): enable socket clients, set the paper port
(default `7497`), and allow the connections you need.

See [TWS-NIX.md](TWS-NIX.md) for packaging details.

## quanta-ib (phase 1: quotes)

Phase 1 implements quote subscribe/unsubscribe for:

- `EURUSD` → IDEALPRO CASH EUR/USD
- `USDJPY` → IDEALPRO CASH USD/JPY
- `AMZN` / `MSFT` → SMART STK USD (US equities; use delayed market-data-type on paper)

Account settings are EDN-driven (no hardcoded host/port defaults), e.g.
[`demo/ib-accounts-quote.edn`](demo/ib-accounts-quote.edn). Blotter account
settings live in [`demo/ib-accounts-blotter.edn`](demo/ib-accounts-blotter.edn)
(trade messaging not wired yet).

### Test

```bash
cd quanta-ib && clojure -X:test
```

### Quote demo (paper TWS)

1. Start paper TWS (`nix run .`) with API socket enabled on the port in the edn file
2. Run:

```bash
cd demo && clojure -X:quote-print
```

## References

- https://github.com/ib-controller/ib-controller
- https://interactivebrokers.github.io/
- https://protojure.readthedocs.io/
