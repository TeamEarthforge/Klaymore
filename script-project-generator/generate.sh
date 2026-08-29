#!/bin/bash
cd "$(dirname "$0")"
if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
else
    echo "[错误] 未找到 Python 3.7+，请先安装 Python"
    exit 1
fi
echo "使用 Python: $($PY --version)"
exec "$PY" "$(dirname "$0")/generate_project.py" "$@"
