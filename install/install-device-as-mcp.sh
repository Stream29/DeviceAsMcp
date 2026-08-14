#!/bin/sh

set -eu

REPOSITORY="${DEVICE_AS_MCP_GITHUB_REPOSITORY:-Stream29/DeviceAsMcp}"
RELEASE_BASE="${DEVICE_AS_MCP_RELEASE_BASE_URL:-https://github.com/$REPOSITORY/releases/latest/download}"
SERVER_URL=""
TOKEN=""
DEVICE_NAME="$(hostname 2>/dev/null || printf '%s' my-device)"
EXPECTED_PLATFORM=""

usage() {
    cat <<'EOF'
Usage: install-device-as-mcp.sh --server URL --token TOKEN [--name NAME] [--platform PLATFORM]
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --server)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            SERVER_URL="$2"
            shift 2
            ;;
        --token)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            TOKEN="$2"
            shift 2
            ;;
        --name)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            DEVICE_NAME="$2"
            shift 2
            ;;
        --platform)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            EXPECTED_PLATFORM="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[ -n "$SERVER_URL" ] || { printf '%s\n' '--server is required' >&2; exit 2; }
[ -n "$TOKEN" ] || { printf '%s\n' '--token is required' >&2; exit 2; }
[ -n "$DEVICE_NAME" ] || { printf '%s\n' '--name cannot be empty' >&2; exit 2; }
command -v curl >/dev/null 2>&1 || {
    printf '%s\n' 'curl is required' >&2
    exit 1
}

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS:$ARCH" in
    Linux:x86_64|Linux:amd64)
        PLATFORM="linux-x64"
        ASSET="device-as-mcp-linux-x64"
        ;;
    Linux:aarch64|Linux:arm64)
        PLATFORM="linux-arm64"
        ASSET="device-as-mcp-linux-arm64"
        ;;
    Darwin:arm64)
        PLATFORM="macos-arm64"
        ASSET="device-as-mcp-macos-arm64"
        ;;
    *)
        printf 'Unsupported platform: %s %s\n' "$OS" "$ARCH" >&2
        exit 1
        ;;
esac
[ -z "$EXPECTED_PLATFORM" ] || [ "$EXPECTED_PLATFORM" = "$PLATFORM" ] || {
    printf 'This command is for %s, but this device is %s.\n' "$EXPECTED_PLATFORM" "$PLATFORM" >&2
    exit 1
}

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/device-as-mcp.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

printf 'Downloading %s from GitHub...\n' "$ASSET"
curl --proto '=https' --tlsv1.2 --fail --show-error --location \
    "$RELEASE_BASE/$ASSET" \
    --output "$TEMP_DIR/$ASSET"
curl --proto '=https' --tlsv1.2 --fail --show-error --location \
    "$RELEASE_BASE/SHA256SUMS" \
    --output "$TEMP_DIR/SHA256SUMS"

EXPECTED_SHA256="$(
    awk -v asset="$ASSET" '
        $2 == asset || $2 == "*" asset {
            print tolower($1)
            exit
        }
    ' "$TEMP_DIR/SHA256SUMS"
)"
[ -n "$EXPECTED_SHA256" ] || {
    printf 'No checksum found for %s\n' "$ASSET" >&2
    exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$TEMP_DIR/$ASSET" | awk '{ print tolower($1) }')"
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$TEMP_DIR/$ASSET" | awk '{ print tolower($1) }')"
else
    printf '%s\n' 'sha256sum or shasum is required' >&2
    exit 1
fi

[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
    printf 'Checksum verification failed for %s\n' "$ASSET" >&2
    exit 1
}

INSTALL_DIR="$HOME/.local/bin"
INSTALL_PATH="$INSTALL_DIR/device-as-mcp"
mkdir -p "$INSTALL_DIR"
chmod 700 "$INSTALL_DIR"

case "$OS" in
    Linux)
        systemctl --user stop device-as-mcp.service >/dev/null 2>&1 || true
        ;;
    Darwin)
        launchctl bootout "gui/$(id -u)/io.github.stream29.device-as-mcp" \
            >/dev/null 2>&1 || true
        ;;
esac

install -m 700 "$TEMP_DIR/$ASSET" "$INSTALL_PATH.new"
mv -f "$INSTALL_PATH.new" "$INSTALL_PATH"

printf '%s\n' 'Enrolling this device...'
"$INSTALL_PATH" enroll \
    --server "$SERVER_URL" \
    --token "$TOKEN" \
    --name "$DEVICE_NAME" \
    --no-run

start_linux_service() {
    UNIT_DIR="$HOME/.config/systemd/user"
    UNIT_PATH="$UNIT_DIR/device-as-mcp.service"
    mkdir -p "$UNIT_DIR"
    cat >"$UNIT_PATH" <<'EOF'
[Unit]
Description=DeviceAsMcp daemon
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/device-as-mcp run
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
    chmod 600 "$UNIT_PATH"

    if systemctl --user daemon-reload >/dev/null 2>&1 &&
        systemctl --user enable --now device-as-mcp.service >/dev/null 2>&1; then
        printf '%s\n' 'DeviceAsMcp is running as a systemd user service.'
        return
    fi

    LOG_DIR="$HOME/.device-as-mcp"
    mkdir -p "$LOG_DIR"
    chmod 700 "$LOG_DIR"
    nohup "$INSTALL_PATH" run >>"$LOG_DIR/daemon.log" 2>&1 </dev/null &
    printf '%s\n' \
        'DeviceAsMcp is running in the background; the systemd user service will start at the next login.'
}

xml_escape() {
    printf '%s' "$1" |
        sed \
            -e 's/&/\&amp;/g' \
            -e 's/</\&lt;/g' \
            -e 's/>/\&gt;/g' \
            -e 's/"/\&quot;/g'
}

start_macos_service() {
    LABEL="io.github.stream29.device-as-mcp"
    AGENT_DIR="$HOME/Library/LaunchAgents"
    LOG_DIR="$HOME/Library/Logs/DeviceAsMcp"
    PLIST_PATH="$AGENT_DIR/$LABEL.plist"
    mkdir -p "$AGENT_DIR" "$LOG_DIR"

    ESCAPED_BINARY="$(xml_escape "$INSTALL_PATH")"
    ESCAPED_STDOUT="$(xml_escape "$LOG_DIR/daemon.log")"
    ESCAPED_STDERR="$(xml_escape "$LOG_DIR/daemon-error.log")"
    cat >"$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$ESCAPED_BINARY</string>
        <string>run</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$ESCAPED_STDOUT</string>
    <key>StandardErrorPath</key>
    <string>$ESCAPED_STDERR</string>
</dict>
</plist>
EOF
    chmod 600 "$PLIST_PATH"

    if launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1; then
        printf '%s\n' 'DeviceAsMcp is running as a macOS LaunchAgent.'
        return
    fi
    if launchctl load -w "$PLIST_PATH" >/dev/null 2>&1; then
        printf '%s\n' 'DeviceAsMcp is running as a macOS LaunchAgent.'
        return
    fi

    nohup "$INSTALL_PATH" run >>"$LOG_DIR/daemon.log" 2>>"$LOG_DIR/daemon-error.log" </dev/null &
    printf '%s\n' \
        'DeviceAsMcp is running in the background; the LaunchAgent will retry at the next login.'
}

case "$OS" in
    Linux) start_linux_service ;;
    Darwin) start_macos_service ;;
esac

printf 'Installed %s\n' "$INSTALL_PATH"
