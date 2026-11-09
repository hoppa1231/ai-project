#!/usr/bin/env bash
set -eu
app_source=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app_target="${XDG_DATA_HOME:-$HOME/.local/share}/chspch"
if [ ! -f "$app_source/bin/Chspch" ] || [ ! -d "$app_source/lib/runtime" ]; then
    printf 'Неполный пакет ЧСПЧ. Распакуйте Linux-архив и запустите install-linux.sh из папки Chspch.\n' >&2
    exit 1
fi
mkdir -p -- "$(dirname -- "$app_target")"
if [ "$app_source" != "$app_target" ]; then
    if [ -e "$app_target" ] && [ ! -f "$app_target/bin/Chspch" ]; then
        printf 'Каталог установки занят другими файлами: %s\n' "$app_target" >&2
        exit 1
    fi
    stage=$(mktemp -d "${app_target}.new.XXXXXX")
    cleanup_stage() {
        if [ -n "$stage" ] && [ -d "$stage" ]; then chmod -R u+w -- "$stage"; rm -rf -- "$stage"; fi
    }
    trap cleanup_stage EXIT
    cp -a -- "$app_source/." "$stage/"
    printf 'chspch-user-install-v1\n' > "$stage/CHSPCH-INSTALL"
    previous="${app_target}.previous"
    if [ -e "$previous" ]; then
        if [ "$(cat "$previous/CHSPCH-INSTALL" 2>/dev/null || true)" != 'chspch-user-install-v1' ]; then
            printf 'Каталог отката занят: %s\n' "$previous" >&2
            exit 1
        fi
        chmod -R u+w -- "$previous"
        rm -rf -- "$previous"
    fi
    if [ -d "$app_target" ]; then
        printf 'chspch-user-install-v1\n' > "$app_target/CHSPCH-INSTALL"
        mv -- "$app_target" "$previous"
    fi
    if ! mv -- "$stage" "$app_target"; then
        if [ -d "$previous" ]; then mv -- "$previous" "$app_target"; fi
        exit 1
    fi
    stage=''
    trap - EXIT
fi
chmod +x -- "$app_target/bin/Chspch" "$app_target/lib/app/resources/core/sing-box" "$app_target/lib/app/resources/helper/chspch-tun-helper"
launcher="$app_target/bin/Chspch"
launcher="${launcher//%/%%}"
launcher="${launcher//\\/\\\\}"
launcher="${launcher//\"/\\\"}"
launcher="${launcher//\$/\\\$}"
launcher="${launcher//\`/\\\`}"
menu="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p -- "$menu"
cat > "$menu/chspch.desktop" <<ENTRY
[Desktop Entry]
Type=Application
Name=ЧСПЧ
Comment=ВПН трудящихся
Exec="$launcher"
Icon=$app_target/icon.png
Terminal=false
Categories=Network;
StartupNotify=true
ENTRY
printf 'ЧСПЧ установлен. Запуск: %s/bin/Chspch\n' "$app_target"
