#!/usr/bin/env bash
# 端對端 smoke test：只驗證「需要真正的 Keycloak」才測得到的部分。
# 業務規則（權限、補償上限、申請單流程、冪等、經由 Copilot 的紀錄）都在各服務的 JUnit 測試：
#   cd legacy/<服務> && mvn test
#
# 需先 `cd infra && docker compose up -d --build`
set -uo pipefail
cd "$(dirname "$0")"

LOGISTICS=http://localhost:8082
TICKET=http://localhost:8083
PROFILE=http://localhost:8084
RUN_ID=$(date +%s)

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }

# call <method> <url> <token> [json] → 設定 CODE 與 BODY（body 經 stdin 以 UTF-8 送出，避開 Windows 參數轉碼）
call() {
  local method="$1" url="$2" token="$3" data="${4:-}" out
  local args=(-s -X "$method" -w $'\n%{http_code}' -H "Authorization: Bearer $token")
  if [[ -n "$data" ]]; then
    args+=(-H 'Content-Type: application/json; charset=utf-8' --data-binary @-)
    out=$(printf '%s' "$data" | curl "${args[@]}" "$url")
  else
    out=$(curl "${args[@]}" "$url")
  fi
  CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

expect() {
  local name="$1" code="$2" contains="${3:-}"
  if [[ "$CODE" != "$code" ]]; then bad "$name（預期 $code，實際 $CODE）${BODY:0:160}"; return; fi
  if [[ -n "$contains" && "$BODY" != *"$contains"* ]]; then bad "$name（回應未包含 $contains）"; return; fi
  ok "$name ($CODE)"
}

echo "[Keycloak 簽發 token]"
if ALICE_TICKET=$(./token.sh alice ticket-web 2>/dev/null); then ok "從工單系統前端登入取得 token"; else bad "無法從 Keycloak 取得 token（Keycloak 起來了嗎？）"; exit 1; fi
ALICE_COPILOT=$(./token.sh alice copilot-web) || { bad "無法從 copilot-web 取得 token"; exit 1; }
ok "從 Copilot 登入取得 token"

echo "[真實 SSO token 在各系統互通]"
call GET "$PROFILE/api/me" "$ALICE_TICKET";                 expect "profile API 驗證真實 token 並回傳角色" 200 '"ticket":["cs_agent"]'
call GET "$TICKET/api/tickets/T-1001" "$ALICE_TICKET";      expect "工單系統接受 token（舊系統 → profile API 串接）" 200 "T-1001"
call GET "$LOGISTICS/api/shipments/S-3001" "$ALICE_TICKET"; expect "同一張 token 也能用在物流系統" 200 "S-3001"

echo "[真實 token 的 azp]"
call POST "$LOGISTICS/api/redirect-requests" "$ALICE_COPILOT" \
  "{\"shipmentId\":\"S-3001\",\"newAddress\":\"台北市內湖區瑞光路 1 號\",\"channel\":\"AI_COPILOT\",\"externalRef\":\"CF-E2E-$RUN_ID\"}"
if [[ "$CODE" == "201" || "$CODE" == "200" ]] && [[ "$BODY" == *'"submittedVia":"copilot-web"'* ]]; then
  ok "Keycloak 簽發的 token 帶有 azp=copilot-web，舊系統據此記錄經由 Copilot"
elif [[ "$CODE" == "409" ]]; then
  bad "S-3001 已有待處理的申請（重跑前先 docker compose restart logistics-service）"
else
  bad "經由 Copilot 開單失敗（$CODE）${BODY:0:160}"
fi

echo
echo "結果：$pass 通過，$fail 失敗"
[[ $fail -eq 0 ]]
