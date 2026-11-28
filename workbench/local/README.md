# Workbench tools

Run from the repository root:

```sh
npm ci --prefix workbench/local --ignore-scripts --no-audit --no-fund
npm run build --prefix workbench/local
npm test --prefix workbench/local
```

`patch-workbench.py` is applied by `scripts/package-workbench.py`; its input hashes are pinned.
