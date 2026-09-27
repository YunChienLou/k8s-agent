#!/usr/bin/env bash
# Token exchange（RFC 8693）：用 <client> 的身分，把 subject token 換成 audience=<audience> 的 token
#   ./scripts/exchange.sh <client> <secret> <subject_token> <audience>
# 成功：印出新 token；失敗：印出錯誤到 stderr 並回傳非 0
set -euo pipefail

CLIENT_ID="$1"; SECRET="$2"; SUBJECT="$3"; AUDIENCE="$4"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"

resp=$(curl -s -X POST "$KEYCLOAK_URL/realms/demo/protocol/openid-connect/token" \
  -d grant_type=urn:ietf:params:oauth:grant-type:token-exchange \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$SECRET" \
  -d "subject_token=$SUBJECT" \
  -d subject_token_type=urn:ietf:params:oauth:token-type:access_token \
  -d requested_token_type=urn:ietf:params:oauth:token-type:access_token \
  -d "audience=$AUDIENCE")

token=$(printf '%s' "$resp" | sed -nE 's/.*"access_token":"([^"]+)".*/\1/p')
if [[ -z "$token" ]]; then
  echo "交換失敗：$resp" >&2
  exit 1
fi
printf '%s\n' "$token"
