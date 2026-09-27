#!/usr/bin/env bash
# 用法：./scripts/token.sh alice      → 印出 alice 的 access token
# 帳號：alice / bob (cs_agent)、carol (cs_supervisor)、dave (sre)，密碼同帳號
set -euo pipefail

USER_NAME="${1:-alice}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"

resp=$(curl -s -X POST "$KEYCLOAK_URL/realms/demo/protocol/openid-connect/token" \
  -d grant_type=password \
  -d client_id=copilot-web \
  -d "username=$USER_NAME" \
  -d "password=$USER_NAME")

token=$(printf '%s' "$resp" | sed -nE 's/.*"access_token":"([^"]+)".*/\1/p')
if [[ -z "$token" ]]; then
  echo "取得 token 失敗：$resp" >&2
  exit 1
fi
printf '%s\n' "$token"
