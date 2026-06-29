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
