#!/bin/sh
# vstab protocol 1: run over SSH as `sh -s --`, with this file on stdin.
# stdout is exactly one JSON response and contains a secret; do not log it.
set -eu
umask 077

vstab_version=4.146.0
vstab_code_version=1.146.0
vstab_root="$HOME/.local/share/vstab"
vstab_install="$vstab_root/server/$vstab_version"
vstab_state="$vstab_root/state"
vstab_cache="$HOME/.cache/vstab"
vstab_lock="$vstab_root/install.lock"
vstab_have_lock=0
vstab_stage=

vstab_error() {
  printf 'vstab: %s\n' "$1" >&2
  printf '{"protocol":1,"status":"error","code":"%s"}\n' "$2"
  exit 1
}

vstab_cleanup() {
  if [ -n "$vstab_stage" ] && [ -d "$vstab_stage" ]; then rm -rf -- "$vstab_stage"; fi
  if [ "$vstab_have_lock" = 1 ]; then rmdir "$vstab_lock" 2>/dev/null || true; fi
}
trap vstab_cleanup EXIT HUP INT TERM

[ "$(uname -s)" = Linux ] || vstab_error 'The first vstab release supports Linux SSH hosts.' unsupported_os
case "$(uname -m)" in
  x86_64|amd64)
    vstab_arch=amd64
    vstab_sha=cf7e82ca5fe1df664d43a43d900c3c9710f8f3403a5e8d0e18f6a12d10d7401b
    ;;
  aarch64|arm64)
    vstab_arch=arm64
    vstab_sha=3df88f7f1feecce3583ab0109c5f6bf14f90ae3a44939b4257ccd9850ef37a6e
    ;;
  *) vstab_error 'This remote CPU is not supported; use Linux x86_64 or ARM64.' unsupported_arch ;;
esac

for vstab_command in curl tar sha256sum base64 cp od tr mkdir chmod nohup; do
  command -v "$vstab_command" >/dev/null 2>&1 || vstab_error "Missing remote prerequisite: $vstab_command" missing_prerequisite
done
mkdir -p "$vstab_root/server" "$vstab_state" "$vstab_cache"
chmod 700 "$vstab_root" "$vstab_state" "$vstab_cache"

vstab_attempt=0
until mkdir "$vstab_lock" 2>/dev/null; do
  vstab_attempt=$((vstab_attempt + 1))
  [ "$vstab_attempt" -lt 120 ] || vstab_error 'Another installation is in progress. Retry after it finishes.' install_locked
  sleep 1
done
vstab_have_lock=1

if [ ! -x "$vstab_install/bin/code-server" ]; then
  vstab_archive="code-server-$vstab_version-linux-$vstab_arch.tar.gz"
  vstab_download="$vstab_cache/$vstab_archive"
  if ! printf '%s  %s\n' "$vstab_sha" "$vstab_download" | sha256sum -c - >/dev/null 2>&1; then
    printf 'vstab: Downloading pinned remote server %s for %s.\n' "$vstab_version" "$vstab_arch" >&2
    curl --fail --location --proto '=https' --tlsv1.2 --retry 2 --connect-timeout 20 \
      --output "$vstab_download.partial" \
      "https://github.com/coder/code-server/releases/download/v$vstab_version/$vstab_archive" >&2 \
      || vstab_error 'Remote server download failed. Check host internet access or preload the archive cache.' download_failed
    printf '%s  %s\n' "$vstab_sha" "$vstab_download.partial" | sha256sum -c - >/dev/null 2>&1 \
      || vstab_error 'Remote server checksum did not match the pinned release.' checksum_mismatch
    mv "$vstab_download.partial" "$vstab_download"
  fi
  printf 'vstab: Installing verified remote server.\n' >&2
  vstab_stage=$(mktemp -d "$vstab_root/server/.stage.XXXXXX")
  # The trusted release digest authenticates the complete archive. Also reject
  # absolute/traversing member names before extraction.
  tar -tzf "$vstab_download" | awk '/^\// || /(^|\/)\.\.(\/|$)/ { bad=1 } END { exit bad }' \
    || vstab_error 'The server archive contains an unsafe path.' unsafe_archive
  tar -xzf "$vstab_download" -C "$vstab_stage" --no-same-owner \
    || vstab_error 'Could not extract the server. Check disk space.' extraction_failed
  [ -x "$vstab_stage/code-server-$vstab_version-linux-$vstab_arch/bin/code-server" ] \
    || vstab_error 'The server archive has an unexpected layout.' invalid_archive
  mv "$vstab_stage/code-server-$vstab_version-linux-$vstab_arch" "$vstab_install"
  rmdir "$vstab_stage"
  vstab_stage=
fi

vstab_node="$vstab_install/lib/node"
[ -x "$vstab_node" ] || vstab_error 'The remote Node runtime is missing.' missing_node
"$vstab_node" -e 'process.exit(0)' >/dev/null 2>&1 \
  || vstab_error 'The remote runtime cannot start. Check glibc compatibility and executable permissions.' runtime_unavailable

# @VSTAB_FIRST_PARTY_EXTENSIONS@

if [ ! -s "$vstab_state/password" ]; then
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$vstab_state/password"
fi
chmod 600 "$vstab_state/password"
vstab_password=$(cat "$vstab_state/password")

# Reuse a live healthy instance. A fixed saved port preserves origin/state.
vstab_port=
if [ -r "$vstab_state/port" ]; then
  vstab_port=$(cat "$vstab_state/port")
  case "$vstab_port" in ''|*[!0-9]*) vstab_port= ;; esac
fi
vstab_running=0
if [ -n "$vstab_port" ] && [ -r "$vstab_state/pid" ]; then
  vstab_pid=$(cat "$vstab_state/pid")
  case "$vstab_pid" in
    ''|*[!0-9]*) ;;
    *)
      if kill -0 "$vstab_pid" 2>/dev/null && curl --fail --silent --max-time 2 "http://127.0.0.1:$vstab_port/healthz" >/dev/null; then
        vstab_running=1
      fi
      ;;
  esac
fi

if [ "$vstab_running" = 0 ]; then
  vstab_port=$("$vstab_node" -e 'const n=require("net").createServer();n.listen(0,"127.0.0.1",()=>{console.log(n.address().port);n.close()})')
  printf '%s\n' "$vstab_port" > "$vstab_state/port"
  {
    printf 'bind-addr: 127.0.0.1:%s\n' "$vstab_port"
    printf 'auth: password\npassword: %s\ncert: false\ndisable-telemetry: true\ndisable-update-check: true\n' "$vstab_password"
  } > "$vstab_state/config.yaml"
  chmod 600 "$vstab_state/config.yaml"
  # All streams are redirected so the daemon does not keep the SSH exec channel
  # open. setsid additionally survives session teardown where available.
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$vstab_install/bin/code-server" --config "$vstab_state/config.yaml" \
      --user-data-dir "$vstab_root/user-data" --extensions-dir "$vstab_root/extensions" \
      > "$vstab_state/server.log" 2>&1 < /dev/null &
  else
    nohup "$vstab_install/bin/code-server" --config "$vstab_state/config.yaml" \
      --user-data-dir "$vstab_root/user-data" --extensions-dir "$vstab_root/extensions" \
      > "$vstab_state/server.log" 2>&1 < /dev/null &
  fi
  printf '%s\n' "$!" > "$vstab_state/pid"
fi

vstab_attempt=0
until curl --fail --silent --max-time 2 "http://127.0.0.1:$vstab_port/healthz" >/dev/null; do
  vstab_attempt=$((vstab_attempt + 1))
  [ "$vstab_attempt" -lt 60 ] || vstab_error 'The server did not become ready. Inspect ~/.local/share/vstab/state/server.log.' startup_failed
  sleep 1
done

# JSON encoding is delegated to the bundled runtime; paths need not be shell-safe
# or ASCII. Secrets are emitted only on the authenticated SSH exec stdout stream.
VSTAB_RESPONSE_PORT="$vstab_port" VSTAB_RESPONSE_PASSWORD="$vstab_password" \
VSTAB_RESPONSE_WORKSPACE="$HOME" VSTAB_RESPONSE_VERSION="$vstab_version" \
VSTAB_RESPONSE_CODE_VERSION="$vstab_code_version" \
"$vstab_node" -e 'process.stdout.write(JSON.stringify({protocol:1,status:"ready",port:Number(process.env.VSTAB_RESPONSE_PORT),password:process.env.VSTAB_RESPONSE_PASSWORD,workspace:process.env.VSTAB_RESPONSE_WORKSPACE,home:process.env.VSTAB_RESPONSE_WORKSPACE,serverVersion:process.env.VSTAB_RESPONSE_VERSION,codeVersion:process.env.VSTAB_RESPONSE_CODE_VERSION})+"\n")'
