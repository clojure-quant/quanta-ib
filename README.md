# Interactive Brokers (TWS + quanta-ib)

This repo packages Interactive Brokers TWS for NixOS and provides
`quanta-ib`, a Clojure blotter/quote adapter for
[quanta-blotter](https://github.com/clojure-quant/quanta-blotter) using the
TWS socket API (V100 framing + protobuf via [protojure](https://protojure.readthedocs.io/)).

## Layout

- Nix TWS package: `flake.nix`, `package.nix` (`nix run .`)
- `quanta-ib/` — Clojure library (`io.github.clojure-quant/quanta-ib`)
- `demo/` — quote print + blotter trade demos against paper TWS

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

## quanta-ib

### Quotes (`:ib-quote`)

Subscribe/unsubscribe for:

- `EURUSD` → IDEALPRO CASH EUR/USD
- `USDJPY` → IDEALPRO CASH USD/JPY
- `AMZN` / `MSFT` → SMART STK USD (US equities; use delayed market-data-type on paper)

Account settings are EDN-driven (no hardcoded host/port defaults), e.g.
[`demo/ib-accounts-quote.edn`](demo/ib-accounts-quote.edn) (client-id `1`).

### Blotter (`:ib-trade`)

Order routing for `:limit`, `:market`, and `:stop` (trigger in `:limit` → IB `auxPrice`):

- outbound: `placeOrder` / `cancelOrder` (modify = second `placeOrder` with same IB order id)
- inbound: `OrderStatus` → confirmed/canceled; `ExecutionDetails` → fills; order-id errors → rejects
- blotter string order ids are stored in IB `orderRef`; IB integer ids come from `NextValidId`

Blotter account: [`demo/ib-accounts-blotter.edn`](demo/ib-accounts-blotter.edn)
(client-id `2` — must differ from the quote client).

Protobufs are a curated quote+trade subset (not all ~203 IB protos). `Order.proto` is
slimmed to the fields we encode/decode — full Order exceeds the JVM method-size limit
under `protoc-gen-clojure`. Regenerate with `quanta-ib/scripts/gen-protos.sh`.

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

### Trade demo (paper TWS)

Uses far-from-market AMZN/MSFT limits so you can observe confirm + cancel without fills:

```bash
cd demo && clojure -X:blotter-trade
```

## References

- https://github.com/ib-controller/ib-controller
- https://interactivebrokers.github.io/
- https://protojure.readthedocs.io/
