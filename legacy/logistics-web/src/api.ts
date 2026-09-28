import { accessToken } from './auth'

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

/** 呼叫舊系統 API（同源，由 Vite proxy／nginx 轉送），帶上 SSO token。 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${await accessToken()}`)
  if (init.body) headers.set('Content-Type', 'application/json; charset=utf-8')
  const res = await fetch(path, { ...init, headers })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    const msg = (data && (data.message || data.detail || data.error)) || `HTTP ${res.status}`
    throw new ApiError(res.status, msg)
  }
  return data as T
}

export interface Profile {
  username: string
  systems: Record<string, string[]>
}

/** 公司 profile API：這個人在各系統的角色。 */
export function myProfile(): Promise<Profile> {
  return api<Profile>('/profile-api/me')
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export const CHANNEL_LABEL: Record<string, string> = {
  WEB: '網頁',
  PHONE: '電話',
  AI_COPILOT: 'AI Copilot',
}
