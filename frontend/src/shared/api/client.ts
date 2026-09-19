export type ApiPage<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type LoginResponse = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

const TOKEN_KEY = 'distribuidora.accessToken'

export function getAccessToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function clearAccessToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

export async function login(email: string, password: string) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw new Error('Credenciales inválidas')
  const result = await response.json() as LoginResponse
  sessionStorage.setItem(TOKEN_KEY, result.accessToken)
  return result
}

export async function apiGet<T>(path: string): Promise<T> {
  const token = getAccessToken()
  const response = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    if (response.status === 401) clearAccessToken()
    throw new Error(response.status === 401 ? 'Sesión expirada' : 'No se pudieron cargar los datos')
  }
  return response.json() as Promise<T>
}

async function apiMutate<T>(path: string, method: string, body: unknown): Promise<T> {
  const token = getAccessToken()
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const detail = await response.json().catch(() => null) as { detail?: string } | null
    throw new Error(detail?.detail ?? 'No se pudo guardar el registro')
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export function apiPost<T>(path: string, body: unknown) { return apiMutate<T>(path, 'POST', body) }
export function apiPut<T>(path: string, body: unknown) { return apiMutate<T>(path, 'PUT', body) }
export function apiPatch<T>(path: string, body: unknown) { return apiMutate<T>(path, 'PATCH', body) }
