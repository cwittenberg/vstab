# Source pins and generated reports

The source repository retains these build inputs:

- `server-lock.json`: matched remote server artifact URLs and hashes.
- `android-runtime.lock.json`: verified Termux package versions and hashes.

Other pins live in `workbench/bundle-spec.json`,
`native-runtime/server-patches/pins.json` and the native-addon builder's source.
Update them deliberately when changing dependencies.

The runtime and workbench packaging tools also write build inventories, patch
manifests and qualification reports here. Those generated files are ignored by
Git, including `android-addons.lock.json` (an output inventory despite its name).
They describe a particular local build and are not source dependencies. APKs,
recordings and test captures belong under ignored `dist/` or `artifacts/`.
