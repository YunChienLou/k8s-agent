#!/usr/bin/env bash
# 驗證 M0.5：SSO token 各系統互通、profile API 權限、申請單／簽核流程
# 需先 `cd infra && docker compose up -d --build`
# 資料存在記憶體：重跑前先 `docker compose restart order-service logistics-service ticket-service` 還原假資料
set -uo pipefail
cd "$(dirname "$0")"

ORDER=http://localhost:8081
LOGISTICS=http://localhost:8082
TICKET=http://localhost:8083
PROFILE=http://localhost:8084
RUN_ID=$(date +%s)

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }

# call <method> <url> <token|-> [json] → 設定 CODE 與 BODY
call() {
  local method="$1" url="$2" token="$3" data="${4:-}" out
  local args=(-s -X "$method" -w $'\n%{http_code}')
  [[ "$token" != "-" ]] && args+=(-H "Authorization: Bearer $token")
  if [[ -n "$data" ]]; then
    # 經 stdin 送出 UTF-8 位元組：Windows 上把中文當命令列參數傳給 curl 會被轉碼
    args+=(-H 'Content-Type: application/json; charset=utf-8' --data-binary @-)
    out=$(printf '%s' "$data" | curl "${args[@]}" "$url")
  else
    out=$(curl "${args[@]}" "$url")
  fi
  CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

# expect <說明> <預期狀態碼> [body 需包含的字串]
expect() {
  local name="$1" code="$2" contains="${3:-}"
  if [[ "$CODE" != "$code" ]]; then bad "$name（預期 $code，實際 $CODE）$([[ -n "$BODY" ]] && echo " ${BODY:0:160}")"; return; fi
  if [[ -n "$contains" && "$BODY" != *"$contains"* ]]; then bad "$name（回應未包含 $contains）"; return; fi
  ok "$name ($CODE)"
}

echo "取得 token（模擬從各系統前端登入）..."
ALICE_TICKET=$(./token.sh alice ticket-web)        || exit 1
ALICE_LOGI=$(./token.sh alice logistics-web)       || exit 1
ALICE_COPILOT=$(./token.sh alice copilot-web)      || exit 1
CAROL_TICKET=$(./token.sh carol ticket-web)        || exit 1
WANG_LOGI=$(./token.sh wang logistics-web)         || exit 1
DAVE_ORDER=$(./token.sh dave order-web)            || exit 1

echo "[SSO 互通與 profile API]"
call GET "$ORDER/api/orders/O-2001" -;                         expect "未帶 token → 401" 401
call GET "$ORDER/api/orders/O-2001" "not-a-jwt";               expect "偽造的 token → 401" 401
call GET "$PROFILE/api/me" "$ALICE_TICKET";                    expect "profile API 回傳 alice 在各系統的角色" 200 '"ticket":["cs_agent"]'
call GET "$TICKET/api/tickets/T-1001" "$ALICE_TICKET";         expect "工單系統登入的 token 查工單" 200 "T-1001"
call GET "$LOGISTICS/api/shipments/S-3001" "$ALICE_TICKET";    expect "同一張 SSO token 也能查物流（各系統互通）" 200 "S-3001"
call GET "$ORDER/api/orders/O-2001" "$DAVE_ORDER";             expect "dave 在訂單系統沒有角色 → 403" 403
call GET "$TICKET/api/tickets/T-1001" "$WANG_LOGI";            expect "wang 在工單系統沒有角色 → 403" 403

echo "[工單權限]"
call GET "$TICKET/api/tickets/T-1002" "$ALICE_TICKET";         expect "alice 查 bob 的工單 → 403" 403
call GET "$TICKET/api/tickets/T-1002" "$CAROL_TICKET";         expect "carol（主管）可查 T-1002" 200

echo "[補償：上限內生效、超過送主管簽核]"
call POST "$TICKET/api/tickets/T-1001/compensations" "$ALICE_TICKET" \
  '{"type":"SHIPPING_FEE_REFUND","amount":60,"reason":"物流延誤，補償改寄運費"}'
expect "alice 補償 60 元 → 直接生效" 201 '"outcome":"APPLIED"'
call POST "$TICKET/api/tickets/T-1001/compensations" "$ALICE_TICKET" \
  '{"type":"COUPON","amount":150,"reason":"客人很生氣","channel":"AI_COPILOT"}'
expect "alice 補償 150 元 → 待主管簽核" 202 '"outcome":"PENDING_APPROVAL"'
CR_ID=$(printf '%s' "$BODY" | sed -nE 's/.*"id":"(CR-[0-9]+)".*/\1/p')
[[ -z "$CR_ID" ]] && { bad "取不到補償申請單號，略過簽核測試"; CR_ID="CR-missing"; }
call POST "$TICKET/api/compensation-requests/$CR_ID/approve" "$ALICE_TICKET"; expect "alice 不能核准 → 403" 403
call POST "$TICKET/api/compensation-requests/$CR_ID/approve" "$CAROL_TICKET"; expect "carol 核准 $CR_ID" 200 '"status":"APPROVED"'
call POST "$TICKET/api/compensation-requests/$CR_ID/approve" "$CAROL_TICKET"; expect "重複核准 → 409" 409
REF="CF-SMOKE-$RUN_ID-1"
call POST "$TICKET/api/tickets/T-1001/compensations" "$ALICE_TICKET" \
  "{\"type\":\"COUPON\",\"amount\":30,\"reason\":\"冪等測試\",\"externalRef\":\"$REF\"}"
expect "帶 externalRef 建立補償" 201
call POST "$TICKET/api/tickets/T-1001/compensations" "$ALICE_TICKET" \
  "{\"type\":\"COUPON\",\"amount\":30,\"reason\":\"冪等測試\",\"externalRef\":\"$REF\"}"
expect "同一個 externalRef 重送 → 回傳既有結果" 200 "$REF"

echo "[改寄：申請單 → 物流人員執行]"
call POST "$LOGISTICS/api/redirect-requests" "$ALICE_LOGI" \
  '{"shipmentId":"S-3001","newAddress":"台北市信義區松仁路 100 號 12 樓","reason":"客人下週出差"}'
expect "alice 建立改寄申請" 201 '"status":"PENDING"'
RR_ID=$(printf '%s' "$BODY" | sed -nE 's/.*"id":"(RR-[0-9]+)".*/\1/p')
[[ -z "$RR_ID" ]] && { bad "取不到改寄申請單號，後續執行測試會失敗"; RR_ID="RR-missing"; }
call GET "$LOGISTICS/api/shipments/S-3001" "$ALICE_LOGI";      expect "執行前地址未變更" 200 "文化路"
call POST "$LOGISTICS/api/redirect-requests" "$ALICE_LOGI" \
  '{"shipmentId":"S-3001","newAddress":"另一個地址 1 號","reason":"重複"}'
expect "同一貨件已有待處理申請 → 409" 409
call POST "$LOGISTICS/api/redirect-requests/$RR_ID/execute" "$ALICE_LOGI"; expect "alice 不能執行 → 403" 403
call POST "$LOGISTICS/api/redirect-requests/$RR_ID/execute" "$WANG_LOGI";  expect "wang（物流人員）執行 $RR_ID" 200 '"status":"EXECUTED"'
call GET "$LOGISTICS/api/shipments/S-3001" "$ALICE_LOGI";      expect "執行後地址已變更" 200 "松仁路"
call POST "$LOGISTICS/api/redirect-requests/$RR_ID/execute" "$WANG_LOGI";  expect "重複執行 → 409" 409
call POST "$LOGISTICS/api/redirect-requests" "$ALICE_LOGI" \
  '{"shipmentId":"S-3003","newAddress":"台中市南屯區公益路二段 1 號"}'
expect "配送中的 S-3003 不能申請改寄 → 409" 409

echo "[經由 Copilot：passthrough（ADR-0009）]"
call GET "$TICKET/api/tickets/T-1001" "$ALICE_COPILOT";        expect "Copilot 登入的 token 直接可用，權限仍由 profile API 決定" 200
call GET "$TICKET/api/tickets/T-1002" "$ALICE_COPILOT";        expect "經由 Copilot 一樣不能越權 → 403" 403
call POST "$LOGISTICS/api/redirect-requests" "$ALICE_COPILOT" \
  "{\"shipmentId\":\"S-3001\",\"newAddress\":\"台北市內湖區瑞光路 1 號\",\"channel\":\"AI_COPILOT\",\"externalRef\":\"CF-SMOKE-$RUN_ID-2\"}"
expect "經由 Copilot 開單：申請人是 alice" 201 '"requestedBy":"alice"'
[[ "$BODY" == *'"submittedVia":"copilot-web"'* ]] && ok "舊系統從 token 的 azp 記錄「經由 copilot-web」" || bad "舊系統未記錄經由的 client"

echo
echo "結果：$pass 通過，$fail 失敗"
[[ $fail -eq 0 ]]
