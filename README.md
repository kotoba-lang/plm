# kotoba PLM

Open-source PLM workbench as EDN data + portable CLJC lifecycle engine.

This repository follows the kotoba industrial-app pattern:

- `resources/plm/domain.edn` is the data registry.
- `src/kotoba/plm/core.cljc` is the pure portable domain engine.
- `src/kotoba/plm/runner.clj` is a conservative host dry-run runner.
- `docs/index.html` is the GitHub Pages workbench.

Pages: https://kotoba-lang.github.io/plm/

## Scope

This is an OSS workbench skeleton for 製品ライフサイクル管理. It does not claim proprietary compatibility with commercial systems. It focuses on open artifact registries, policy-gated runners, coverage/maturity scoring, and EDN handoff.

## Verify

```sh
clojure -M -e '(load-file "src/kotoba/plm/core.cljc") (println :ok)'
python3 -m http.server 8765 --directory docs
```

## kotoba.plm.* — PLM × ERP thread domain (merged from cloud-itonami kyber-plm)

The pure PLM/ERP/MRP domain formerly living in `gftdcojp/cloud-itonami` as
`kyber-plm.*` (ADR-2606171400 lineage; merged per the ADR-2607020100 addendum
— コードは kotoba-lang、商売は cloud-itonami):

- `kotoba.plm.item` — item master / BOM edges / change orders (pure
  constructors + queries; was `kyber-plm.plm`)
- `kotoba.plm.schema` — the three-layer graph schema (plm.* / erp.* / ocel.*)
- `kotoba.plm.erp` — chart of accounts, balanced GL journals, rolled-cost
  snapshots, OCEL events
- `kotoba.plm.cost` — standard-cost roll-up over the released MBOM
- `kotoba.plm.mrp` — MBOM demand explosion → net requirements → auto POs
- `kotoba.plm.production` — production completion (backflush) into WIP/GL
- `kotoba.plm.thread` — the PLM→ERP reactive thread (one logical commit per fn)
- `kotoba.plm.db` / `kotoba.plm.store` — Store protocol + kotoba-datomic XRPC
  backend (transport injected); `kotoba.plm.store-datomic` is the Datomic
  Local dev/test backend, **opt-in via `:datomic` / `:test` alias** so the
  core stays dependency-free
- `kotoba.plm.demo` — end-to-end PLM→ERP demo

Business-side projections (kotobase `kg.ingest` payloads, live ops CLI)
remain in `gftdcojp/cloud-itonami` (`cloud-itonami.kotobase-kg` /
`cloud-itonami.plm-export`).

```bash
clojure -M:test   # 9 tests (phase2 + thread) against Datomic Local
```
