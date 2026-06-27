#!/usr/bin/env bash
# check-cited-paths.sh —— 核验文档里 ```cited-paths 块的每条路径在 vendors/ignite 下真实存在(防幻觉门)。
# 路径优先按 core 源码根解释,再按 vendors/ignite 根解释(兼容 modules/indexing 等)。
# 用法: scripts/check-cited-paths.sh <doc.md> [<doc.md> ...]
# 退出码:0 全部存在;1 有缺失。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENDORS="$ROOT/vendors/ignite"
CORE="$VENDORS/modules/core/src/main/java/org/apache/ignite"

total_miss=0
for DOC in "$@"; do
    paths="$(awk '
        /^```cited-paths/ { f=1; next }
        /^```/           { if (f) f=0; next }
        f                { print }
    ' "$DOC" | sed 's/[[:space:]]*$//' | grep -v '^$' || true)"

    if [ -z "$paths" ]; then
        echo "WARN  $DOC : 无 cited-paths 块(跳过)"
        continue
    fi

    cnt=0
    miss=0
    while IFS= read -r p; do
        cnt=$((cnt + 1))
        if [ -e "$CORE/$p" ] || [ -e "$VENDORS/$p" ]; then
            :
        else
            echo "  MISS  $p"
            miss=$((miss + 1))
        fi
    done <<< "$paths"

    if [ "$miss" -gt 0 ]; then
        echo "FAIL  $DOC : $miss/$cnt missing"
        total_miss=$((total_miss + miss))
    else
        echo "OK    $DOC : $cnt/$cnt"
    fi
done

if [ "$total_miss" -gt 0 ]; then
    echo "总计缺失 $total_miss 条引用路径" >&2
    exit 1
fi
