#!/usr/bin/env bash
# 模擬「alice 在 Copilot 按下確認」：用 copilot-web 登入的 token，向舊系統提單。
# Copilot 還沒做好之前，用這支腳本在 demo 時讓申請單出現在舊系統畫面上。
#   ./scripts/demo-submit.sh redirect   → 物流系統出現改寄申請（到 http://localhost:5175 用 wang 登入）
#   ./scripts/demo-submit.sh comp       → 工單系統出現 200 元補償待簽核（到 http://localhost:5176 用 carol 登入）
set -euo pipefail
cd "$(dirname "$0")"

KIND="${1:-redirect}"
TOKEN=$(./token.sh alice copilot-web)
REF="CF-DEMO-$(date +%s)"

post() { # post <url> <json>
  printf '%s' "$2" | curl -s -X POST "$1" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json; charset=utf-8' --data-binary @- -w '\nHTTP %{http_code}\n'
}

case "$KIND" in
  redirect)
    post http://localhost:8082/api/redirect-requests \
      "{\"shipmentId\":\"S-3001\",\"newAddress\":\"台北市信義區松仁路 100 號 12 樓\",\"reason\":\"客人下週出差，改寄公司（T-1001）\",\"channel\":\"AI_COPILOT\",\"externalRef\":\"$REF\"}" ;;
  comp)
    post http://localhost:8083/api/tickets/T-1001/compensations \
      "{\"type\":\"COUPON\",\"amount\":200,\"reason\":\"物流延誤造成客人不便，補償折價券\",\"channel\":\"AI_COPILOT\",\"externalRef\":\"$REF\"}" ;;
  *) echo "用法：$0 redirect|comp" >&2; exit 1 ;;
esac
