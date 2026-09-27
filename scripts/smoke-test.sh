#!/usr/bin/env bash
# 驗證 M0：三個舊系統的 JWT 驗證與權限規則是否如預期
# 需先 `cd infra && docker compose up -d --build`
set -uo pipefail
cd "$(dirname "$0")"

ORDER=http://localhost:8081
LOGISTICS=http://localhost:8082
TICKET=http://localhost:8083

pass=0; fail=0

# check <說明> <預期 HTTP 狀態> <curl 參數...>
check() {
  local name="$1" expected="$2"; shift 2
  local actual
  actual=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS  $name ($actual)"; pass=$((pass + 1))
  else
    echo "  FAIL  $name（預期 $expected，實際 $actual）"; fail=$((fail + 1))
  fi
}

echo "取得 token..."
ALICE=$(./token.sh alice) || exit 1
CAROL=$(./token.sh carol) || exit 1
DAVE=$(./token.sh dave)   || exit 1
json=(-H 'Content-Type: application/json')

echo "[認證]"
check "未帶 token → 401"                       401 "$ORDER/api/orders/O-2001"
check "sre 角色不能看訂單 → 403"               403 -H "Authorization: Bearer $DAVE" "$ORDER/api/orders/O-2001"

echo "[跨系統查詢：alice 處理 T-1001]"
check "alice 查自己的工單 T-1001"              200 -H "Authorization: Bearer $ALICE" "$TICKET/api/tickets/T-1001"
check "alice 查訂單 O-2001"                    200 -H "Authorization: Bearer $ALICE" "$ORDER/api/orders/O-2001"
check "alice 查貨件 S-3001"                    200 -H "Authorization: Bearer $ALICE" "$LOGISTICS/api/shipments/S-3001"

echo "[越權]"
check "alice 查 bob 的工單 T-1002 → 403"       403 -H "Authorization: Bearer $ALICE" "$TICKET/api/tickets/T-1002"
check "carol（主管）可查 T-1002"               200 -H "Authorization: Bearer $CAROL" "$TICKET/api/tickets/T-1002"

echo "[寫入規則]"
check "alice 補償 150 元超過上限 → 403"        403 -X POST "${json[@]}" -H "Authorization: Bearer $ALICE" \
  -d '{"type":"SHIPPING_FEE_REFUND","amount":150,"reason":"物流延誤"}' "$TICKET/api/tickets/T-1001/compensations"
check "alice 補償 60 元運費 → 201"             201 -X POST "${json[@]}" -H "Authorization: Bearer $ALICE" \
  -d '{"type":"SHIPPING_FEE_REFUND","amount":60,"reason":"物流延誤，補償改寄運費"}' "$TICKET/api/tickets/T-1001/compensations"
check "carol 補償 150 元 → 201"                201 -X POST "${json[@]}" -H "Authorization: Bearer $CAROL" \
  -d '{"type":"COUPON","amount":150,"reason":"主管核准加發折價券"}' "$TICKET/api/tickets/T-1001/compensations"
check "已在配送中的 S-3003 不能改寄 → 409"     409 -X POST "${json[@]}" -H "Authorization: Bearer $CAROL" \
  -d '{"newAddress":"台中市南屯區公益路二段 1 號"}' "$LOGISTICS/api/shipments/S-3003/redirect"

echo
echo "結果：$pass 通過，$fail 失敗"
[[ $fail -eq 0 ]]
