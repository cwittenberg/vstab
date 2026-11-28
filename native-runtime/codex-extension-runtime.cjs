'use strict';

// The public extension's JavaScript supports Android, but its bundled Linux
// engine does not. Redirect only that engine to the APK's Bionic build while
// retaining its arguments, environment, stdio and process lifecycle.
const path = require('node:path');
const fs = require('node:fs');
const childProcess = require('node:child_process');
const BUNDLED_CODEX = /[\\/]openai\.chatgpt-\d[^\\/]*[\\/]bin[\\/]linux-(?:x86_64|aarch64)[\\/]codex$/;
const INSTALLED = Symbol.for('vstab.codex-extension-runtime');

function install({platform = process.platform, environment = process.env,
                  child = childProcess, exists = fs.existsSync, mkdir = fs.mkdirSync} = {}) {
  if (platform !== 'android' || child[INSTALLED]) return false;
  const executable = environment.VSTAB_CODEX_EXECUTABLE;
  if (!executable || !path.isAbsolute(executable) || !exists(executable)) return false;
  const originalSpawn = child.spawn;
  child.spawn = function (file, ...rest) {
    const managed = typeof file === 'string' && BUNDLED_CODEX.test(file);
    const target = managed ? executable : file;
    if (managed) {
      const options = Array.isArray(rest[0]) ? rest[1] : rest[0];
      const childEnvironment = options?.env || environment;
      const home = childEnvironment.HOME;
      const codexHome = childEnvironment.CODEX_HOME;
      // The extension explicitly sets its default CODEX_HOME. The engine
      // requires an explicitly selected home to exist, even on first use.
      // Custom locations keep the publisher's ordinary validation behavior.
      if (home && codexHome && path.isAbsolute(home) && path.isAbsolute(codexHome) &&
          path.resolve(codexHome) === path.join(home, '.codex')) {
        mkdir(codexHome, {recursive: true, mode: 0o700});
      }
    }
    return Reflect.apply(originalSpawn, this, [target, ...rest]);
  };
  Object.defineProperty(child, INSTALLED, {value: true});
  if (child === childProcess) require('node:module').syncBuiltinESMExports();
  return true;
}

install();
module.exports = {install};
