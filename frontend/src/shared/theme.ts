export type Theme = 'light' | 'dark'

const storageKey = 'distribuidora.theme'
const changeEvent = 'distribuidora-theme-change'

function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#101b20' : '#f3f5f2')
}

export function initializeTheme() {
  let theme: Theme = 'light'
  try {
    if (localStorage.getItem(storageKey) === 'dark') theme = 'dark'
  } catch { /* Keep the default when browser storage is unavailable. */ }
  applyTheme(theme)
}

export function getTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

export function toggleTheme() {
  const theme = getTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(theme)
  try {
    localStorage.setItem(storageKey, theme)
  } catch { /* Switching still works without persistent storage. */ }
  window.dispatchEvent(new Event(changeEvent))
}

export function subscribeToTheme(onChange: () => void) {
  function onStorage(event: StorageEvent) {
    if (event.key !== storageKey && event.key !== null) return
    initializeTheme()
    onChange()
  }
  window.addEventListener(changeEvent, onChange)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(changeEvent, onChange)
    window.removeEventListener('storage', onStorage)
  }
}
