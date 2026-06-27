#!/usr/bin/env bash
# check-milestone-report.sh —— 里程碑基准门:核验报告存在 + 含必要章节 + 自检全勾。
# 里程碑 ☑ 的前置(见 roadmap §3)。用法: scripts/check-milestone-report.sh <M?-report.md>
# 退出码:0 通过;1 未达标。
set -euo pipefail

DOC="${1:-}"
if [ -z "$DOC" ] || [ ! -f "$DOC" ]; then
    echo "FAIL: 报告不存在或未指定:$DOC(里程碑对比未完成)" >&2
    echo "用法: $0 <specs/benchmarks/M?-<名>-report.md>" >&2
    exit 1
fi

fail=0
check_section() {
    if grep -qE "^## $1\." "$DOC"; then
        echo "  OK   §$1 $2"
    else
        echo "  MISS §$1 $2"
        fail=1
    fi
}

check_section 1 环境
check_section 2 功能一致性
check_section 3 性能基准
check_section 4 差距分析
check_section 5 自检

# 自检:不应有未勾选的 "- [ ]"
if grep -qE '^-[[:space:]]*\[[[:space:]]\]' "$DOC"; then
    echo '  MISS §5 自检仍有未勾选项 "- [ ]"'
    fail=1
else
    echo '  OK   §5 自检全勾'
fi

# perf 表至少 3 行数据(§3 之后、§4 之前,统计 | 分隔的非表头行)
perf_rows=$(awk '/^## 3\. /{f=1;next} /^## 4\. /{f=0} f && /^\| [^|]/ && !/^\| workload/' "$DOC" | wc -l | tr -d ' ')
if [ "${perf_rows:-0}" -ge 3 ]; then
    echo "  OK   §3 perf 表 ≥3 行($perf_rows)"
else
    echo "  MISS §3 perf 表 <3 行($perf_rows)"
    fail=1
fi

if [ "$fail" -gt 0 ]; then
    echo "FAIL: $DOC 未达里程碑基准门" >&2
    exit 1
fi
echo "OK: $DOC 通过里程碑基准门"
