import Keycloak from 'keycloak-js'

// 公司 SSO 登入（PKCE）。這支前端的 client 是 ticket-web。
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8080',
  realm: 'demo',
  clientId: 'ticket-web',
})

export async function login(): Promise<void> {
  await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false })
}

/** 取得有效的 access token，快到期時自動更新。 */
export async function accessToken(): Promise<string> {
  try {
    await keycloak.updateToken(30)
  } catch {
    await keycloak.login()
  }
  return keycloak.token ?? ''
}

export function username(): string {
  const parsed = keycloak.tokenParsed as { preferred_username?: string } | undefined
  return parsed?.preferred_username ?? ''
}

export function logout(): void {
  void keycloak.logout({ redirectUri: window.location.origin })
}
