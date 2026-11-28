#!/data/data/com.termux/files/usr/bin/bash
# Generic Termux VM host. The Android caller prepends base64-only assignments.
# Never source/eval the downloaded spec, and never log the control credential.
set -euo pipefail
umask 077
export LC_ALL=C

decode() { printf '%s' "$1" | base64 --decode; }
encode() { base64 | tr -d '\n'; }
result() {
    printf 'VSTAB_TERMUX_RESULT\t%s\t%s\t%s\t%s\n' "$1" \
        "$(printf '%s' "$2" | encode)" "$(printf '%s' "${3:-}" | encode)" "${4:-}"
}
fail() { result error "$1"; exit 1; }

# Lifecycle control never signals a process or replaces an existing disk. An
# owned guest receives a data-only shutdown request through its readonly share.
process_identity() {
    local raw fields
    raw=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    read -r -a fields <<<"${raw##*) }"
    ((${#fields[@]} > 19)) || return 1
    [[ "${fields[0]}" != Z && "${fields[0]}" != X ]] || return 1
    printf '%s\n' "${fields[19]}"
}
owned_guest() {
    local pid='' expected='' tick='' argument next has_pid=0 has_disk=0
    if [[ -f "$vstab_root/qemu.pid" ]]; then read -r pid <"$vstab_root/qemu.pid" || true; fi
    if [[ -f "$vstab_root/qemu-owner" ]]; then
        local recorded
        read -r recorded expected <"$vstab_root/qemu-owner" || true
        if [[ -z "$pid" ]]; then pid=$recorded; elif [[ "$pid" != "$recorded" ]]; then return 2; fi
    fi
    [[ -n "$pid" ]] || return 1
    [[ "$pid" =~ ^[0-9]+$ ]] || return 2
    kill -0 "$pid" 2>/dev/null || return 1
    tick=$(process_identity "$pid") || {
        [[ -r "/proc/$pid/stat" ]] && return 1
        return 2
    }
    [[ -z "$expected" || "$expected" == "$tick" ]] || return 2
    [[ "$(stat -c %u "/proc/$pid" 2>/dev/null)" == "$vstab_owner_uid" ]] || return 2
    local -a arguments=()
    mapfile -d '' -t arguments <"/proc/$pid/cmdline" || return 2
    for ((argument=0; argument+1<${#arguments[@]}; argument++)); do
        next=${arguments[argument+1]}
        [[ "${arguments[argument]}" == -pidfile && "$next" == "$vstab_root/qemu.pid" ]] && has_pid=1
        [[ "${arguments[argument]}" == -drive && "$next" == "file=$vstab_root/data.ext4,"* ]] && has_disk=1
    done
    ((has_pid && has_disk)) || return 2
    vstab_existing_pid=$pid; vstab_existing_tick=$tick
}
request_guest_stop() {
    local temporary="$vstab_root/boot/.stop-request.$BASHPID.part"
    printf '%s' "$vstab_environment" >"$temporary"
    mv "$temporary" "$vstab_root/boot/stop-request"
}
stop_existing_guest() {
    local state=0 deadline=$((SECONDS + 35))
    vstab_existing_pid=''; vstab_existing_tick=''
    owned_guest || state=$?
    ((state != 2)) || fail 'The stored Termux VM identity does not match its process. No process was stopped and its disk was preserved.'
    if [[ -z "$vstab_existing_pid" ]] && ((vstab_has_lease)); then
        rm -f "$vstab_root/qemu.pid" "$vstab_root/qemu-owner"
        return
    fi
    request_guest_stop
    while ((SECONDS < deadline)); do
        if (( ! vstab_has_lease )) && flock --nonblock 9; then vstab_has_lease=1; fi
        if [[ -n "$vstab_existing_pid" ]]; then
            local current
            current=$(process_identity "$vstab_existing_pid") || current=''
            [[ "$current" == "$vstab_existing_tick" ]] || vstab_existing_pid=''
        elif (( ! vstab_has_lease )); then
            # An older launcher may still be finishing startup. Adopt only its
            # validated VM identity, never an arbitrary reused PID.
            state=0; owned_guest || state=$?
            ((state != 2)) || fail 'The running Termux job could not be identified safely. Its disk was preserved.'
        fi
        if [[ -z "$vstab_existing_pid" ]] && ((vstab_has_lease)); then
            rm -f "$vstab_root/qemu.pid" "$vstab_root/qemu-owner"
            return
        fi
        sleep 0.2
    done
    fail 'The Termux Linux guest did not shut down cleanly. Its running process and disk were preserved; reconnect or retry Stop before restarting.'
}
read_guest_spec() {
    local filename=$1 key value line
    local -n target=$2
    target=()
    while IFS= read -r line || [[ -n "${line:-}" ]]; do
        [[ -n "$line" ]] || continue
        [[ "$line" == *=* ]] || fail 'The Linux setup manifest contains an invalid line.'
        key=${line%%=*}; value=${line#*=}
        case "$key" in
            SCHEMA|CODE_VERSION|SERVER_VERSION|KERNEL_SHA256|INITRD_SHA256|DISK_SHA256|DISK_UNPACKED_SHA256|DISK_COMPRESSION|DISK_GROWTH|LOGICAL_DISK_BYTES|KERNEL_ARGS_B64|QEMU_MEMORY_MIB|QEMU_SMP) ;;
            *) fail 'The Linux setup manifest contains an unsupported field.' ;;
        esac
        [[ -z "${target[$key]+present}" ]] || fail 'The Linux setup manifest repeats a field.'
        target[$key]=$(decode "$value") || fail 'The Linux setup manifest contains invalid encoding.'
    done <"$filename"
    [[ "${target[SCHEMA]:-}" == 1 ]] || fail 'The Linux setup manifest version is unsupported.'
}
pin_installed_guest() {
    vstab_using_installed=0
    [[ -f "$vstab_root/data.ext4" ]] || return 0
    [[ -f "$vstab_root/data-base.sha256" ]] || fail 'This existing Linux disk needs recovery. Its disk and kernel were preserved.'
    local base key
    base=$(cat "$vstab_root/data-base.sha256")
    [[ "$base" =~ ^[a-f0-9]{64}$ ]] || fail 'The saved Linux disk identity is invalid. Its disk and kernel were preserved.'
    if [[ -f "$vstab_root/installed-guest" ]]; then
        local -A installed
        read_guest_spec "$vstab_root/installed-guest" installed
        [[ "${installed[DISK_UNPACKED_SHA256]:-}" == "$base" ]] || fail 'The saved Linux runtime does not match its disk. Its disk and kernel were preserved.'
        for key in CODE_VERSION SERVER_VERSION; do
            if [[ -n "${installed[$key]:-}" && -n "${vstab_spec[$key]:-}" && "${installed[$key]}" != "${vstab_spec[$key]}" ]]; then
                fail 'The saved Termux environment needs migration before this editor version. Its disk and paired kernel were preserved.'
            fi
        done
        if [[ "$base" != "${vstab_spec[DISK_UNPACKED_SHA256]}" ]]; then
            [[ -n "${installed[CODE_VERSION]:-}" && -n "${installed[SERVER_VERSION]:-}" &&
                "${installed[CODE_VERSION]}" == "${vstab_spec[CODE_VERSION]:-}" &&
                "${installed[SERVER_VERSION]}" == "${vstab_spec[SERVER_VERSION]:-}" ]] ||
                fail 'The saved Termux environment needs migration before this editor version. Its disk and paired kernel were preserved.'
        fi
        for key in KERNEL_SHA256 INITRD_SHA256 DISK_UNPACKED_SHA256 DISK_GROWTH LOGICAL_DISK_BYTES KERNEL_ARGS_B64 CODE_VERSION SERVER_VERSION; do
            vstab_spec[$key]=${installed[$key]:-}
        done
        vstab_using_installed=1
    else
        # A base hash cannot prove an older command line. Preserve all bytes
        # and require explicit recovery rather than inventing a pairing.
        fail 'The saved Termux environment has no paired runtime record. Recover or migrate it before restarting; its disk and kernel were preserved.'
    fi
    [[ "${vstab_spec[KERNEL_SHA256]:-}" =~ ^[a-f0-9]{64}$ && -f "$vstab_root/kernel" &&
        "$(sha256sum "$vstab_root/kernel" | cut -d' ' -f1)" == "${vstab_spec[KERNEL_SHA256]}" ]] ||
        fail 'The saved Linux kernel is missing or changed. Restore its matching runtime; the disk was preserved.'
    if [[ -n "${vstab_spec[INITRD_SHA256]:-}" ]]; then
        [[ "${vstab_spec[INITRD_SHA256]}" =~ ^[a-f0-9]{64}$ && -f "$vstab_root/initrd" &&
            "$(sha256sum "$vstab_root/initrd" | cut -d' ' -f1)" == "${vstab_spec[INITRD_SHA256]}" ]] ||
            fail 'The saved Linux initial filesystem is missing or changed. Its disk was preserved.'
    fi
}
record_installed_guest() {
    local key temporary="$vstab_root/installed-guest.part"
    : >"$temporary"
    for key in "${!vstab_spec[@]}"; do
        printf '%s=%s\n' "$key" "$(printf '%s' "${vstab_spec[$key]}" | encode)" >>"$temporary"
    done
    mv "$temporary" "$vstab_root/installed-guest"
}
grow_guest_disk() {
    # Capability belongs to the INSTALLED runtime paired with this disk. A new
    # APK must never enlarge an older guest that has no filesystem resize step.
    [[ "${vstab_spec[DISK_GROWTH]:-false}" == true ]] || return 0
    local requested=${vstab_spec[LOGICAL_DISK_BYTES]:-0} before after blocks block_size available
    [[ "$requested" =~ ^[0-9]{1,13}$ ]] || fail 'The Linux logical disk capacity is invalid.'
    requested=$((10#$requested))
    ((requested > 0 && requested % 512 == 0)) || fail 'The Linux logical disk capacity is invalid.'
    [[ -f "$vstab_root/data.ext4" && ! -L "$vstab_root/data.ext4" ]] || fail 'The existing Linux disk is missing or redirected; it was not replaced.'
    before=$(stat -c %s "$vstab_root/data.ext4")
    ((before > 0)) || fail 'The Linux disk is empty and needs recovery.'
    ((before < requested)) || return 0
    read -r blocks block_size < <(stat -f -c '%a %S' "$vstab_root") || fail 'Android free storage could not be checked before disk expansion.'
    [[ "$blocks" =~ ^[0-9]+$ && "$block_size" =~ ^[0-9]+$ ]] || fail 'Android free storage could not be checked before disk expansion.'
    available=$((blocks * block_size))
    ((available >= 2147483648)) || fail 'Linux needs at least 2 GiB of free Android storage before expanding its disk.'
    [[ "$(stat -c %s "$vstab_root/data.ext4")" == "$before" ]] || fail 'The Linux disk changed during expansion preparation; its current capacity was preserved.'
    truncate --no-create --size "$requested" "$vstab_root/data.ext4" || fail 'The Linux disk could not be expanded; its saved bytes were preserved.'
    after=$(stat -c %s "$vstab_root/data.ext4")
    ((after >= requested)) || fail 'Linux disk expansion did not complete. Retry before starting the environment.'
    sync "$vstab_root/data.ext4"
}

vstab_mode=$(decode "${VSTAB_MODE_B64:-c3RhcnQ=}")
case "$vstab_mode" in start|stop|refresh|check|preview|install) ;; *) fail 'Unsupported Linux host operation.' ;; esac
vstab_arch=$(decode "${VSTAB_ARCH_B64:?}")
case "$vstab_arch" in
    arm64-v8a|aarch64) vstab_qemu_name=qemu-system-aarch64; vstab_qemu_package=qemu-system-aarch64-headless ;;
    x86_64) vstab_qemu_name=qemu-system-x86_64; vstab_qemu_package=qemu-system-x86-64-headless ;;
    *) fail 'This tablet architecture is not supported by the Linux runtime.' ;;
esac
vstab_required_packages=("$vstab_qemu_package" coreutils curl gzip xz-utils util-linux)

if [[ "$vstab_mode" == refresh ]]; then
    command -v apt-get >/dev/null || fail 'This Termux variant has no supported APT package manager. Choose built-in Linux.'
    # An explicit setup action refreshes signed repository metadata only.
    # It never installs, upgrades or removes a package.
    if apt-get update; then
        result refreshed 'Termux package lists were refreshed. Review the required packages next.'
        exit 0
    fi
    fail 'Termux package lists could not be refreshed. Check its network and repository configuration, or choose built-in Linux.'
fi

available_qemu() {
    vstab_qemu=$(command -v "$vstab_qemu_name" || true)
    [[ -n "$vstab_qemu" ]] || return 1
    vstab_qemu_version=$("$vstab_qemu" --version 2>/dev/null) || return 1
    local major
    major=$(printf '%s\n' "$vstab_qemu_version" | sed -n '1s/.*version \([0-9][0-9]*\)\..*/\1/p')
    [[ "$major" =~ ^[0-9]+$ ]] && ((major >= 8)) || return 1
    local devices networks
    devices=$("$vstab_qemu" -device help 2>/dev/null) || return 1
    networks=$("$vstab_qemu" -netdev help 2>/dev/null) || return 1
    grep -q 'virtio-9p-pci' <<<"$devices" || return 1
    grep -Eq '(^|[[:space:]])user([[:space:]]|$)' <<<"$networks" || return 1
    for vstab_tool in curl base64 sha256sum gzip xz dd flock; do
        command -v "$vstab_tool" >/dev/null || return 1
    done
}

preview_packages() {
    command -v apt-get >/dev/null || fail 'This Termux variant has no supported APT package manager. Choose built-in Linux.'
    vstab_preview=$(mktemp)
    # Keep already installed requested tools, omit recommendations, and forbid
    # removals. Required dependency upgrades remain visible in the exact plan
    # and require approval; this never runs a global package upgrade.
    if ! apt-get -o Debug::NoLocking=1 --simulate --no-install-recommends --no-upgrade --no-remove \
        install "${vstab_required_packages[@]}" >"$vstab_preview" 2>&1; then
        result needs-package 'Termux cannot resolve the required packages without removals. Review its package setup or choose built-in Linux.' \
            "$(cat "$vstab_preview")"
        rm -f "$vstab_preview"
        exit 0
    fi
    if grep -Eq '^Remv ' "$vstab_preview"; then
        result needs-package 'QEMU setup would remove installed packages. Choose built-in Linux to preserve this Termux environment.' \
            "$(cat "$vstab_preview")"
        rm -f "$vstab_preview"
        exit 0
    fi
    if ! apt-get -o Debug::NoLocking=1 --print-uris --assume-yes --download-only \
        --no-install-recommends --no-upgrade --no-remove install "${vstab_required_packages[@]}" >>"$vstab_preview" 2>&1; then
        result needs-package 'Termux package download sizes could not be determined. Refresh its package lists explicitly or choose built-in Linux.' \
            "$(cat "$vstab_preview")"
        rm -f "$vstab_preview"
        exit 0
    fi
    vstab_transaction_sha=$(sha256sum "$vstab_preview" | cut -d' ' -f1)
}

if [[ "$vstab_mode" == check ]]; then
    if available_qemu; then
        result available 'Termux has a compatible QEMU host and the required setup tools.' "${vstab_qemu_version%%$'\n'*}"
        exit 0
    fi
    preview_packages
    result needs-package 'Termux needs the listed Linux host packages. Review the download before installing.' \
        "$(cat "$vstab_preview")" "$vstab_transaction_sha"
    rm -f "$vstab_preview"
    exit 0
fi

if [[ "$vstab_mode" == preview || "$vstab_mode" == install ]]; then
    preview_packages
    if [[ "$vstab_mode" == preview ]]; then
        result needs-package 'Install the listed required packages and dependency changes in Termux?' \
            "$(cat "$vstab_preview")" "$vstab_transaction_sha"
        rm -f "$vstab_preview"
        exit 0
    fi
    vstab_approved=$(decode "${VSTAB_APPROVED_TRANSACTION_B64:-}")
    if [[ "$vstab_approved" != "$vstab_transaction_sha" ]]; then
        result needs-package 'The Termux package transaction changed. Review the current download and dependencies before installing.' \
            "$(cat "$vstab_preview")" "$vstab_transaction_sha"
        rm -f "$vstab_preview"
        exit 0
    fi
    rm -f "$vstab_preview"
    if apt-get --assume-yes --no-install-recommends --no-upgrade --no-remove install "${vstab_required_packages[@]}"; then
        result installed 'The required Termux QEMU package is installed.'
        exit 0
    fi
    fail 'Termux QEMU installation failed. Existing projects and VM data were preserved.'
fi

vstab_environment=$(decode "${VSTAB_ENVIRONMENT_ID_B64:?}")
[[ "$vstab_environment" =~ ^[a-zA-Z0-9_-]{1,80}$ ]] || fail 'Invalid Linux environment identity.'
vstab_root="$HOME/.local/share/vstab/linux/$vstab_environment"
if [[ "$vstab_mode" == stop && ! -d "$vstab_root" ]]; then
    result stopped 'The Termux Linux environment is already stopped.'
    exit 0
fi
mkdir -p "$vstab_root/boot"
chmod 700 "$vstab_root" "$vstab_root/boot"
vstab_owner_uid=$(id -u)
exec 8>"$vstab_root/operation.lock"
flock --wait 5 8 || fail 'Another Termux Linux setup or lifecycle operation is in progress. Retry after it finishes.'
exec 9>"$vstab_root/supervisor.lock"
vstab_has_lease=0
if flock --nonblock 9; then vstab_has_lease=1; fi
if [[ "$vstab_mode" == stop ]]; then
    stop_existing_guest
    result stopped 'The Termux Linux environment is stopped; its saved disk was preserved.'
    exit 0
fi

for vstab_tool in curl base64 sha256sum gzip xz dd flock; do
    command -v "$vstab_tool" >/dev/null || fail "Termux is missing the required generic tool: $vstab_tool."
done
if ! available_qemu; then
    preview_packages
    result needs-package 'QEMU is not installed in Termux. Review this package transaction before installing.' \
        "$(cat "$vstab_preview")" "$vstab_transaction_sha"
    rm -f "$vstab_preview"
    exit 0
fi

vstab_control=$(decode "${VSTAB_CONTROL_URL_B64:?}")
vstab_token=$(decode "${VSTAB_CONTROL_TOKEN_B64:?}")
vstab_ssh_port=$(decode "${VSTAB_SSH_PORT_B64:?}")
vstab_editor_port=$(decode "${VSTAB_EDITOR_PORT_B64:?}")
[[ "$vstab_control" =~ ^http://127\.0\.0\.1:[0-9]+$ ]] || fail 'Invalid Linux setup endpoint.'
[[ "$vstab_token" =~ ^[a-zA-Z0-9_-]+$ ]] && (( ${#vstab_token} >= 32 && ${#vstab_token} <= 256 )) || fail 'Invalid Linux setup credential.'
for vstab_port in "$vstab_ssh_port" "$vstab_editor_port"; do
    [[ "$vstab_port" =~ ^[0-9]{1,5}$ ]] && ((vstab_port > 0 && vstab_port <= 65535)) || fail 'Invalid Linux connection port.'
done
vstab_auth=$(mktemp "$vstab_root/.control-header.XXXXXXXX")
printf 'Authorization: Bearer %s\n' "$vstab_token" >"$vstab_auth"
unset vstab_token VSTAB_CONTROL_TOKEN_B64
vstab_qemu_pid=''
cleanup() {
    rm -f "$vstab_auth"
    # Cancellation requests the guest's own sync/poweroff path. A stuck guest
    # remains running; neither normal Stop nor reconnect force-kills it.
    if [[ -n "$vstab_qemu_pid" ]] && kill -0 "$vstab_qemu_pid" 2>/dev/null; then
        request_guest_stop
        local deadline=$((SECONDS + 35)) tick
        while ((SECONDS < deadline)); do
            tick=$(process_identity "$vstab_qemu_pid") || break
            sleep 0.2
        done
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
post_console() {
    curl --fail --silent --show-error --max-time 10 --header "@$vstab_auth" \
        --data-binary @- "$vstab_control/console" >/dev/null
}
get_asset() {
    curl --fail --silent --show-error --retry 2 --max-time 900 --header "@$vstab_auth" \
        "$vstab_control/assets/$1" --output "$2"
}
get_asset termux-spec "$vstab_root/spec.part" || fail 'The Android Linux setup connection was interrupted.'
declare -A vstab_spec
read_guest_spec "$vstab_root/spec.part" vstab_spec
rm -f "$vstab_root/spec.part"
for vstab_key in KERNEL_SHA256 DISK_SHA256 DISK_UNPACKED_SHA256; do
    [[ "${vstab_spec[$vstab_key]:-}" =~ ^[a-f0-9]{64}$ ]] || fail 'The Linux setup manifest is missing a file digest.'
done
pin_installed_guest
if ((vstab_using_installed)); then
    printf 'Using the saved Termux Linux runtime and its paired kernel to preserve your environment.\n' | post_console || fail 'The Android Linux control connection closed.'
fi
vstab_initrd_sha=${vstab_spec[INITRD_SHA256]:-}
[[ -z "$vstab_initrd_sha" || "$vstab_initrd_sha" =~ ^[a-f0-9]{64}$ ]] || fail 'The Linux initial filesystem digest is invalid.'
case "${vstab_spec[DISK_COMPRESSION]:-gzip}" in
    gzip) vstab_decompressor=gzip ;;
    xz) vstab_decompressor=xz ;;
    *) fail 'The Linux disk compression format is unsupported.' ;;
esac
vstab_memory=${vstab_spec[QEMU_MEMORY_MIB]:-1024}
vstab_cpus=${vstab_spec[QEMU_SMP]:-2}
[[ "$vstab_memory" =~ ^[0-9]{3,5}$ ]] && ((vstab_memory >= 256 && vstab_memory <= 32768)) || fail 'Invalid Linux memory setting.'
[[ "$vstab_cpus" =~ ^[0-9]{1,2}$ ]] && ((vstab_cpus >= 1 && vstab_cpus <= 16)) || fail 'Invalid Linux CPU setting.'
vstab_kernel_args=$(decode "${vstab_spec[KERNEL_ARGS_B64]:-}") || fail 'The Linux kernel command line is invalid.'
[[ -n "$vstab_kernel_args" && "$vstab_kernel_args" != *$'\n'* ]] || fail 'The Linux kernel command line is empty or invalid.'

verified_asset() {
    local asset_id=$1 expected_sha=$2 destination=$3
    if [[ -f "$destination" ]] && [[ "$(sha256sum "$destination" | cut -d' ' -f1)" == "$expected_sha" ]]; then return; fi
    get_asset "$asset_id" "$destination.part" || fail "The Linux $asset_id download was interrupted."
    [[ "$(sha256sum "$destination.part" | cut -d' ' -f1)" == "$expected_sha" ]] || fail "The Linux $asset_id digest did not match."
    mv "$destination.part" "$destination"
}
verified_asset kernel "${vstab_spec[KERNEL_SHA256]}" "$vstab_root/kernel"
vstab_initrd_arguments=()
if [[ -n "$vstab_initrd_sha" ]]; then
    verified_asset initrd "$vstab_initrd_sha" "$vstab_root/initrd"
    vstab_initrd_arguments=(-initrd "$vstab_root/initrd")
fi
# Fresh credentials/broker endpoints require a clean boot. Keep the same disk;
# a failed shutdown must never become an implicit replacement environment.
stop_existing_guest
if [[ -f "$vstab_root/data.ext4" ]]; then
    [[ -f "$vstab_root/data-base.sha256" ]] || fail 'This existing Linux disk needs recovery before it can be reused.'
    [[ "$(cat "$vstab_root/data-base.sha256")" == "${vstab_spec[DISK_UNPACKED_SHA256]}" ]] || fail 'The Linux base changed. Migrate this environment before upgrading; its existing disk was preserved.'
else
    verified_asset disk "${vstab_spec[DISK_SHA256]}" "$vstab_root/disk.compressed"
    "$vstab_decompressor" -dc "$vstab_root/disk.compressed" | dd of="$vstab_root/data.ext4.part" bs=1M conv=sparse status=none \
        || fail 'The Linux disk could not be unpacked. Check free space in Termux.'
    [[ "$(sha256sum "$vstab_root/data.ext4.part" | cut -d' ' -f1)" == "${vstab_spec[DISK_UNPACKED_SHA256]}" ]] || fail 'The unpacked Linux disk digest did not match.'
    record_installed_guest
    printf '%s\n' "${vstab_spec[DISK_UNPACKED_SHA256]}" >"$vstab_root/data-base.sha256.part"
    mv "$vstab_root/data-base.sha256.part" "$vstab_root/data-base.sha256"
    # Commit metadata before exposing the final disk name. A crash must not
    # make an existing user disk look like an unprovisioned blank environment.
    sync "$vstab_root/installed-guest" "$vstab_root/data-base.sha256" "$vstab_root/kernel" "$vstab_root/data.ext4.part"
    if [[ -n "$vstab_initrd_sha" ]]; then sync "$vstab_root/initrd"; fi
    sync "$vstab_root"
    mv "$vstab_root/data.ext4.part" "$vstab_root/data.ext4"
    sync "$vstab_root"
    rm -f "$vstab_root/disk.compressed"
fi
grow_guest_disk
get_asset bootstrap "$vstab_root/boot/bootstrap.env.part" || fail 'The Linux pairing configuration could not be loaded.'
mv "$vstab_root/boot/bootstrap.env.part" "$vstab_root/boot/bootstrap.env"
rm -f "$vstab_root/boot/stop-request"
vstab_machine_arguments=()
if [[ "$vstab_arch" == x86_64 ]]; then
    vstab_machine_arguments=(-machine q35 -cpu max)
else
    vstab_machine_arguments=(-machine virt -cpu cortex-a72)
fi
vstab_qemu_data="${PREFIX:-/data/data/com.termux/files/usr}/share/qemu"
[[ -d "$vstab_qemu_data" ]] || fail 'Termux QEMU boot resources are missing. Repair its official QEMU package or choose built-in Linux.'
rm -f "$vstab_root/qmp.sock"
printf 'Starting the shared Linux guest through Termux.\n' | post_console || fail 'The Android Linux control connection closed.'
"$vstab_qemu" "${vstab_machine_arguments[@]}" -accel tcg -no-user-config -nodefaults \
    -display none -L "$vstab_qemu_data" -m "$vstab_memory" -smp "$vstab_cpus" \
    -kernel "$vstab_root/kernel" "${vstab_initrd_arguments[@]}" -append "$vstab_kernel_args" \
    -drive "file=$vstab_root/data.ext4,format=raw,if=virtio,cache=writeback" \
    -netdev "user,id=vstabnet,hostfwd=tcp:127.0.0.1:$vstab_ssh_port-:22,hostfwd=tcp:127.0.0.1:$vstab_editor_port-:3000" \
    -device virtio-net-pci,netdev=vstabnet,romfile= \
    -fsdev "local,id=vstabboot,path=$vstab_root/boot,security_model=none,readonly=on" \
    -device virtio-9p-pci,fsdev=vstabboot,mount_tag=vstab-bootstrap \
    -device virtio-rng-pci -qmp "unix:$vstab_root/qmp.sock,server=on,wait=off" \
    -pidfile "$vstab_root/qemu.pid" -serial stdio -monitor none \
    8>&- > >(exec 8>&-; while IFS= read -r vstab_line || [[ -n "$vstab_line" ]]; do
        printf '%s\n' "${vstab_line:0:16000}" | post_console || true
    done) 2>&1 &
vstab_qemu_pid=$!
vstab_tick=$(process_identity "$vstab_qemu_pid") || vstab_tick=''
if [[ -n "$vstab_tick" ]]; then
    printf '%s %s\n' "$vstab_qemu_pid" "$vstab_tick" >"$vstab_root/qemu-owner.part"
    mv "$vstab_root/qemu-owner.part" "$vstab_root/qemu-owner"
fi
# Lifetime lease9 follows the supervisor/VM; release only the short lifecycle
# transaction lease so a later Stop can reach this running instance.
exec 8>&-
set +e
wait "$vstab_qemu_pid"
vstab_exit=$?
set -e
vstab_qemu_pid=''
if ((vstab_exit == 0)); then
    result stopped 'The Termux Linux guest stopped.'
else
    result error "The Termux Linux guest stopped unexpectedly (exit $vstab_exit)."
fi
exit "$vstab_exit"
