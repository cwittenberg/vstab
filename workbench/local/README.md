# Workbench build tools

This directory keeps the locked esbuild dependency used by the first-party
Android controls extension and the hash-pinned Code OSS embedding patch.
Local projects use the Android-native Node backend; SSH projects use the matching
Linux backend. Both use the bundled Code OSS workbench and scoped native controls.

Install build dependencies and build the controls bundle:

```sh
npm ci --prefix workbench/local --ignore-scripts --no-audit --no-fund
npm run build --prefix workbench/local
```

`patch-workbench.py` consumes the original compiled workbench from the pinned
code-server archive. It installs the controls MessagePort hook and Android file
transfer adaptations. Unknown inputs and altered patched outputs fail closed;
applying the patch to its expected output is idempotent.
`scripts/package-workbench.py` applies this patch while preparing APK assets.

Run the controls checks with:

```sh
npm test --prefix workbench/local
```
