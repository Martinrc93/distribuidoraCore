import { appPath, instanceStorageKey } from '../appBase'

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

const TOKEN_KEY = instanceStorageKey('distribuidora.accessToken')
const REFRESH_TOKEN_KEY = instanceStorageKey('distribuidora.refreshToken')
const rotationScope = instanceStorageKey('distribuidora.refresh-rotation')
const authListeners = new Set<() => void>()
let authGeneration = 0
let refreshInFlight: { generation: number; promise: Promise<string> } | undefined

type Rotation = { type: 'rotated'; fingerprint: string; tokens: LoginResponse }
type RotationMessage = Rotation | { type: 'synchronize'; fingerprint: string }
let rotationChannel: BroadcastChannel | undefined
let lastRotation: Rotation | undefined
let loginNotice: 'rolePermissionsUpdated' | 'userRoleUpdated' | undefined

export function subscribeAuthChange(listener: () => void) {
  authListeners.add(listener)
  return () => { authListeners.delete(listener) }
}

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
  authListeners.forEach(listener => listener())
}

export function getLoginNotice() {
  return loginNotice
}

export function clearAccessToken(notice?: typeof loginNotice) {
  authGeneration++
  loginNotice = notice
  lastRotation = undefined
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_TOKEN_KEY)
  authListeners.forEach(listener => listener())
}

export async function login(email: string, password: string) {
  const generation = ++authGeneration
  let response: Response
  try {
    response = await fetch(appPath('/api/auth/login'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
  } catch {
    throw new Error('No se pudo conectar con el servidor. Verifica la conexión e intenta nuevamente.')
  }
  if (response.status === 401 || response.status === 403) throw new Error('Credenciales inválidas')
  if (response.status >= 500) throw new Error('El servidor no está disponible. Intenta nuevamente en unos momentos.')
  if (!response.ok) throw await apiErrorFromResponse(response, 'No se pudo iniciar sesión. Intenta nuevamente.')
  const result = await response.json() as LoginResponse
  if (generation !== authGeneration) {
    if (result.refreshToken) void revokeRefreshToken(result.refreshToken).catch(() => undefined)
    throw expiredSession()
  }
  lastRotation = undefined
  loginNotice = undefined
  storeTokens(result)
  return result
}

function expiredSession() {
  return new ApiError(401, 'Sesión expirada', 'Sesión expirada')
}

async function refreshFingerprint(token: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

function refreshChannel() {
  if (!rotationChannel && typeof window.BroadcastChannel === 'function' && navigator.locks && crypto.subtle) {
    rotationChannel = new BroadcastChannel(rotationScope)
    rotationChannel.onmessage = ({ data }: MessageEvent<RotationMessage>) => {
      if (data?.type === 'synchronize') {
        if (lastRotation?.fingerprint === data.fingerprint) rotationChannel?.postMessage(lastRotation)
      } else if (data?.type === 'rotated' && data.tokens?.accessToken && data.tokens.refreshToken) {
        const token = getRefreshToken()
        const generation = authGeneration
        if (!token) return
        void refreshFingerprint(token).then(fingerprint => {
          if (fingerprint === data.fingerprint && token === getRefreshToken() && generation === authGeneration) {
            storeTokens(data.tokens)
          }
        }).catch(() => undefined)
      }
    }
  }
  return rotationChannel
}

function waitForPeerRotation(channel: BroadcastChannel, fingerprint: string, refreshToken: string) {
  return new Promise<string | null>(resolve => {
    const unsubscribe = subscribeAuthChange(() => {
      if (getRefreshToken() !== refreshToken) finish(getAccessToken())
    })
    const timeout = window.setTimeout(() => finish(null), 2_000)
    function finish(value: string | null) {
      unsubscribe()
      window.clearTimeout(timeout)
      resolve(value)
    }
    if (getRefreshToken() !== refreshToken) finish(getAccessToken())
    else channel.postMessage({ type: 'synchronize', fingerprint } satisfies RotationMessage)
  })
}

function markRotation(marker: string) {
  const now = Date.now()
  for (let index = localStorage.length - 1; index >= 0; index--) {
    const key = localStorage.key(index)
    if (key?.startsWith(rotationScope + ':') && Number(localStorage.getItem(key)) < now) localStorage.removeItem(key)
  }
  localStorage.setItem(marker, String(now + 7 * 86_400_000))
}

async function refreshAccessToken(generation: number): Promise<string> {
  if (refreshInFlight?.generation === generation) return refreshInFlight.promise

  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    clearAccessToken()
    throw expiredSession()
  }

  async function rotate(token: string): Promise<LoginResponse> {
    const response = await fetch(appPath('/api/auth/refresh'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: token }),
    })
    if (!response.ok) {
      if (generation === authGeneration && (response.status === 401 || response.status === 403)) clearAccessToken()
      throw await apiErrorFromResponse(response, 'Sesión expirada')
    }
    const result = await response.json().catch(() => null) as LoginResponse | null
    if (generation !== authGeneration) {
      if (result?.refreshToken) void revokeRefreshToken(result.refreshToken).catch(() => undefined)
      throw expiredSession()
    }
    if (!result?.accessToken || !result.refreshToken) {
      clearAccessToken()
      throw expiredSession()
    }
    storeTokens(result)
    return result
  }

  const promise = (async () => {
    const channel = refreshChannel()
    if (!channel) return (await rotate(refreshToken)).accessToken
    const fingerprint = await refreshFingerprint(refreshToken)
    const marker = rotationScope + ':' + fingerprint
    return navigator.locks.request(marker, async () => {
      if (generation !== authGeneration) throw expiredSession()
      if (getRefreshToken() !== refreshToken) return getAccessToken() ?? ''
      if (Number(localStorage.getItem(marker)) > Date.now()) {
        const shared = await waitForPeerRotation(channel, fingerprint, refreshToken)
        if (generation !== authGeneration) throw expiredSession()
        if (shared) return shared
        clearAccessToken()
        throw expiredSession()
      }
      const result = await rotate(refreshToken)
      lastRotation = { type: 'rotated', fingerprint, tokens: result }
      try { markRotation(marker) } catch { /* Storage may be restricted by the browser. */ }
      channel.postMessage(lastRotation)
      return result.accessToken
    })
  })()
  refreshInFlight = { generation, promise }

  try {
    return await promise
  } finally {
    if (refreshInFlight?.promise === promise) refreshInFlight = undefined
  }
}

async function fetchAuthenticated(path: string, init: RequestInit): Promise<Response> {
  const generation = authGeneration
  refreshChannel()
  const token = getAccessToken()
  const response = await fetch(appPath(path), {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  })
  if (generation !== authGeneration) throw expiredSession()
  if (response.status !== 401 || !token) return response

  init.signal?.throwIfAborted()
  const current = getAccessToken()
  const newToken = current && current !== token ? current : await refreshAccessToken(generation)
  if (generation !== authGeneration || !newToken) throw expiredSession()
  const retry = await fetch(appPath(path), {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${newToken}` },
  })
  if (generation !== authGeneration) throw expiredSession()
  if (retry.status === 401 && getAccessToken() === newToken) clearAccessToken()
  return retry
}

async function apiErrorFromResponse(response: Response, fallback: string): Promise<ApiError> {
  const body = await response.json().catch(() => null) as {
    detail?: string
    code?: string
    affectedPriceLists?: AffectedPriceList[]
  } | null
  return new ApiError(response.status, body?.detail, fallback, body?.code, body?.affectedPriceLists)
}

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetchAuthenticated(path, signal ? { signal } : {})
  if (!response.ok) {
    throw await apiErrorFromResponse(response, response.status === 401 ? 'Sesión expirada' : 'No se pudieron cargar los datos')
  }
  return response.json() as Promise<T>
}

/**
 * Prints an authenticated PDF without downloading it: loads it in a hidden iframe and opens the print dialog.
 * Pass `target` (a window opened synchronously in the click handler) to show the PDF in a new tab instead,
 * for touch devices whose browsers cannot print PDFs from an iframe.
 */
export async function apiPrintPdf(path: string, target?: Window | null): Promise<void> {
  let frame: HTMLIFrameElement | undefined
  let objectUrl: string | undefined
  function cleanup() {
    frame?.remove()
    if (objectUrl) URL.revokeObjectURL(objectUrl)
  }
  try {
    const response = await fetchAuthenticated(path, {})
    if (!response.ok) {
      throw await apiErrorFromResponse(response, 'No se pudo preparar el documento para imprimir')
    }
    const pdf = await response.blob()
    objectUrl = URL.createObjectURL(pdf.type === 'application/pdf' ? pdf : pdf.slice(0, pdf.size, 'application/pdf'))
    if (target) {
      target.location.href = objectUrl
    } else {
      const printer = document.createElement('iframe')
      frame = printer
      printer.title = 'Documento para imprimir'
      printer.setAttribute('aria-hidden', 'true')
      printer.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
      printer.src = objectUrl
      await new Promise<void>((resolve, reject) => {
        const timeout = window.setTimeout(() => finish(new Error('El documento tardó demasiado en cargar.')), 15_000)
        function finish(error?: Error) {
          window.clearTimeout(timeout)
          printer.onload = null
          printer.onerror = null
          if (error) reject(error)
          else resolve()
        }
        printer.onload = () => finish()
        printer.onerror = () => finish(new Error('No se pudo abrir el documento para imprimir.'))
        document.body.appendChild(printer)
      })
      try {
        const printWindow = printer.contentWindow
        if (!printWindow) throw new Error('No se pudo abrir la vista de impresión.')
        printWindow.focus()
        printWindow.print()
      } catch {
        window.open(objectUrl, '_blank', 'noopener')
      }
    }
    window.setTimeout(cleanup, 60_000)
  } catch (error) {
    target?.close()
    cleanup()
    throw error
  }
}

export async function apiGetBlob(path: string, filename: string): Promise<void> {
  const response = await fetchAuthenticated(path, {})
  if (!response.ok) {
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
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000)
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
    throw await apiErrorFromResponse(response, 'No se pudo guardar el registro')
  }
  if (response.status === 204 || response.body === null) return undefined as T
  if (!response.body) return response.json() as Promise<T>
  const content = await response.text()
  return content.length === 0 ? undefined as T : JSON.parse(content) as T
}

export async function logout() {
  const refreshToken = getRefreshToken()
  clearAccessToken()
  if (refreshToken) await revokeRefreshToken(refreshToken)
}

function revokeRefreshToken(refreshToken: string) {
  return fetch(appPath('/api/auth/logout'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })
}

export function apiPost<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'POST', body) }
export function apiPut<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'PUT', body) }
export function apiPatch<T>(path: string, body: unknown): Promise<T> { return apiMutate<T>(path, 'PATCH', body) }
export function apiDelete<T = void>(path: string): Promise<T> { return apiMutate<T>(path, 'DELETE', undefined) }
