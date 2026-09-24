#!/usr/bin/env bash
set -euo pipefail

cmd="${1:-}"
loss="${2:-2%}"
delay="${3:-100ms}"

[[ "$loss" =~ %$ ]] || loss="${loss}%"
[[ "$delay" =~ ms$ ]] || delay="${delay}ms"

iface=$(ip route show default 2>/dev/null | awk '/default/ {print $5}' | head -n1)
if [[ -z "$iface" ]]; then
    echo "Error: Could not determine default network interface" >&2
    exit 1
fi

case "$cmd" in
    on)
        echo "Enabling netem on $iface: loss $loss, delay $delay"
        sudo tc qdisc replace dev "$iface" root netem loss "$loss" delay "$delay"
        tc qdisc show dev "$iface"
        ;;
    off)
        echo "Disabling netem on $iface"
        sudo tc qdisc del dev "$iface" root 2>/dev/null || true
        tc qdisc show dev "$iface"
        ;;
    *)
        echo "Usage: $0 on [lossPct] [delayMs] | off" >&2
        exit 1
        ;;
esac
