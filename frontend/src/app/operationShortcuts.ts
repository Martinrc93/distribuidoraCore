import { useMemo, useSyncExternalStore } from 'react'
import { instanceStorageKey } from '../shared/appBase'
import { getCurrentUserId } from '../shared/auth/permissions'
import { commissionSettlementsPath } from '../features/admin/settingsPaths'

export const operationLinks = [
  ['Resumen', '/dashboard', true],
  ['Dashboard', '/analytics', true],
  ['Pedidos', '/orders'],
  ['Ventas', '/sales'],
  ['Compras', '/supplier-orders', true],
  ['Clientes', '/customers'],
  ['Gastos', '/expenses', true],
  ['Liquidaciones', commissionSettlementsPath, true],
] as const

type OperationPath = typeof operationLinks[number][1]
export type OperationShortcuts = Partial<Record<string, string>>

const changeEvent = 'distribuidora-operation-shortcuts-change'
const shortcutPattern = /^(?=Control\+|Alt\+|(?:Shift\+)?Meta\+)(?:Control\+)?(?:Alt\+)?(?:Shift\+)?(?:Meta\+)?[A-Z0-9]$/

export function shortcutFromKey(event: KeyboardEvent) {
  if (event.repeat || event.isComposing || event.getModifierState('AltGraph')) return
  if (!event.ctrlKey && !event.altKey && !event.metaKey) return
  if (!/^[a-z0-9]$/i.test(event.key)) return

  return [
    event.ctrlKey && 'Control',
    event.altKey && 'Alt',
    event.shiftKey && 'Shift',
    event.metaKey && 'Meta',
    event.key.toUpperCase(),
  ].filter(Boolean).join('+')
}

export function formatShortcut(shortcut: string) {
  return shortcut.replace('Control', 'Ctrl').replace('Shift', 'Mayús')
}

function readShortcuts(key: string | undefined) {
  try {
    return key ? localStorage.getItem(key) : null
  } catch {
    return null
  }
}

function parseShortcuts(raw: string | null): OperationShortcuts {
  const shortcuts: OperationShortcuts = {}
  if (!raw) return shortcuts

  try {
    const stored: unknown = JSON.parse(raw)
    if (typeof stored !== 'object' || stored === null) return shortcuts
    for (const [, path] of operationLinks) {
      const shortcut = (stored as Record<string, unknown>)[path]
      if (typeof shortcut === 'string' && shortcutPattern.test(shortcut)) shortcuts[path] = shortcut
    }
  } catch { /* Ignore invalid browser preferences. */ }
  return shortcuts
}

function subscribe(onChange: () => void) {
  window.addEventListener('storage', onChange)
  window.addEventListener(changeEvent, onChange)
  return () => {
    window.removeEventListener('storage', onChange)
    window.removeEventListener(changeEvent, onChange)
  }
}

export function useOperationShortcuts() {
  const userId = getCurrentUserId()
  const key = userId ? instanceStorageKey(`distribuidora.operation-shortcuts:${userId}`) : undefined
  const stored = useSyncExternalStore(subscribe, () => readShortcuts(key))
  const shortcuts = useMemo(() => parseShortcuts(stored), [stored])

  function updateShortcut(path: OperationPath, shortcut?: string) {
    const latest = parseShortcuts(readShortcuts(key))
    const conflict = shortcut && operationLinks.find(([, otherPath]) => otherPath !== path && latest[otherPath] === shortcut)
    if (conflict) return `Este atajo ya está asignado a ${conflict[0]}.`
    if (!key) return 'No se pudo guardar el atajo. Vuelva a iniciar sesión.'

    if (shortcut) latest[path] = shortcut
    else delete latest[path]
    try {
      localStorage.setItem(key, JSON.stringify(latest))
    } catch {
      return 'No se pudo guardar el atajo en este navegador.'
    }
    window.dispatchEvent(new Event(changeEvent))
  }

  return { shortcuts, updateShortcut }
}
