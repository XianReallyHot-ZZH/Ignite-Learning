#!/usr/bin/env bash
# check-handouts.sh —— 讲义门:对每个 specs/sessions/SNN-*.md(非模板),核验 docs-learn/<同名>.md 存在且非空壳。
# 讲义必写(见 roadmap §3)。用法: scripts/check-handouts.sh
# 退出码:0 全部齐;1 有缺失/过薄。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SPEC_DIR="$ROOT/specs/sessions"
HANDOUT_DIR="$ROOT/docs-learn"

fail=0
for spec in "$SPEC_DIR"/S[0-9][0-9]-*.md; do
    [ -e "$spec" ] || continue
    base="$(basename "$spec")"
    handout="$HANDOUT_DIR/$base"
    if [ ! -e "$handout" ]; then
        echo "MISS  讲义缺失:$base → docs-learn/$base"
        fail=$((fail + 1))
        continue
    fi
    n="$(grep -cE '^## ' "$handout" || true)"
    if [ "${n:-0}" -lt 3 ]; then
        echo "THIN  讲义过薄($n 节):$base"
        fail=$((fail + 1))
    else
        echo "OK    $base : 讲义 $n 节"
    fi
done

if [ "$fail" -gt 0 ]; then
    echo "FAIL: $fail 个 session 讲义缺失/过薄(讲义必写,见 roadmap §3)" >&2
    exit 1
fi
