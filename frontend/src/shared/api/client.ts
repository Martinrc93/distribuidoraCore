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
  refreshToken: string | null
}

export type AffectedPriceList = {
  priceListId: string
  code: string
  currentPrice: number
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail?: string,
    message = 'No se pudo guardar el registro',
    public readonly code?: string,
    public readonly affectedPriceLists?: AffectedPriceList[],
  ) {
    super(detail ?? message)
    this.name = 'ApiError'
  }
}

const TOKEN_KEY = 'distribuidora.accessToken'
const REFRESH_TOKEN_KEY = 'distribuidora.refreshToken'
let refreshInFlight: Promise<string> | undefined

export function getAccessToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY)
}

function storeTokens(result: LoginResponse) {
  sessionStorage.setItem(TOKEN_KEY, result.accessToken)
  if (result.refreshToken) sessionStorage.setItem(REFRESH_TOKEN_KEY, result.refreshToken)
  else sessionStorage.removeItem(REFRESH_TOKEN_KEY)
}

export function clearAccessToken() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_TOKEN_KEY)
}

export async function login(email: string, password: string) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw new Error('Credenciales inválidas')
  const result = await response.json() as LoginResponse
  storeTokens(result)
  return result
}

async function refreshAccessToken(): Promise<string> {
  if (refreshInFlight) return refreshInFlight

  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    clearAccessToken()
    throw new ApiError(401, 'Sesión expirada', 'Sesión expirada')
  }

  refreshInFlight = (async () => {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!response.ok) {
      clearAccessToken()
      throw await apiErrorFromResponse(response, 'Sesión expirada')
    }
    const result = await response.json() as LoginResponse
    if (!result.accessToken || !result.refreshToken) {
      clearAccessToken()
      throw new ApiError(401, 'La API no devolvió credenciales renovadas', 'Sesión expirada')
    }
    storeTokens(result)
    return result.accessToken
  })()

  try {
    return await refreshInFlight
  } finally {
    refreshInFlight = undefined
  }
}

async function fetchAuthenticated(path: string, init: RequestInit): Promise<Response> {
  const token = getAccessToken()
  const response = await fetch(path, {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  })
  if (response.status !== 401 || !token) return response

  const newToken = await refreshAccessToken()
  return fetch(path, {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${newToken}` },
  })
}

async function apiErrorFromResponse(response: Response, fallback: string): Promise<ApiError> {
  const body = await response.json().catch(() => null) as {
    detail?: string
    code?: string
    affectedPriceLists?: AffectedPriceList[]
  } | null
  return new ApiError(response.status, body?.detail, fallback, body?.code, body?.affectedPriceLists)
}

export async function apiGet<T>(path: string): Promise<T> {
  const response = await fetchAuthenticated(path, {})
  if (!response.ok) {
    if (response.status === 401) clearAccessToken()
    throw await apiErrorFromResponse(response, response.status === 401 ? 'Sesión expirada' : 'No se pudieron cargar los datos')
  }
  return response.json() as Promise<T>
}

export async function apiGetBlob(path: string, filename: string): Promise<void> {
  const response = await fetchAuthenticated(path, {})
  if (!response.ok) {
    if (response.status === 401) clearAccessToken()
    throw await apiErrorFromResponse(response, response.status === 401 ? 'Sesión expirada' : 'No se pudo descargar el documento')
  }

  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

async function apiMutate<T>(path: string, method: string, body: unknown): Promise<T> {
  const hasBody = body !== undefined
  const response = await fetchAuthenticated(path, {
    method,
    headers: hasBody ? { 'Content-Type': 'application/json' } : {},
    ...(hasBody ? { body: JSON.stringify(body) } : {}),
  })
  if (!response.ok) {
    if (response.status === 401) clearAccessToken()
    throw await apiErrorFromResponse(response, 'No se pudo guardar el registro')
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export async function logout() {
  const refreshToken = getRefreshToken()
  try {
    if (refreshToken) {
      await fetch('/api/auth/logout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
    }
  } finally {
    clearAccessToken()
  }
}

export function apiPost<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'POST', body) }
export function apiPut<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'PUT', body) }
export function apiPatch<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'PATCH', body) }
export function apiDelete<T = void>(path: string): Promise<T> { return apiMutate<T>(path, 'DELETE', undefined) }
