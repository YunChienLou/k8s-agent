#!/usr/bin/env bash
# 取得使用者 token（模擬從某個前端登入）
#   ./scripts/token.sh <帳號> <client>
#   client：ticket-web / order-web / logistics-web（舊系統前端）、copilot-web（Copilot）
# 帳號：alice / bob (cs_agent)、carol (cs_supervisor)、wang (logistics_staff)、dave (sre)，密碼同帳號
set -euo pipefail

USER_NAME="${1:-alice}"
CLIENT_ID="${2:-ticket-web}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"

resp=$(curl -s -X POST "$KEYCLOAK_URL/realms/demo/protocol/openid-connect/token" \
  -d grant_type=password \
  -d "client_id=$CLIENT_ID" \
  -d "username=$USER_NAME" \
  -d "password=$USER_NAME")

token=$(printf '%s' "$resp" | sed -nE 's/.*"access_token":"([^"]+)".*/\1/p')
if [[ -z "$token" ]]; then
  echo "取得 token 失敗：$resp" >&2
  exit 1
fi
printf '%s\n' "$token"
