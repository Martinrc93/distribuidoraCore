export type TextSize = 'normal' | 'large'

const storageKey = 'distribuidora.text-size'
const changeEvent = 'distribuidora-text-size-change'

function applyTextSize(size: TextSize) {
  if (size === 'large') document.documentElement.dataset.textSize = 'large'
  else delete document.documentElement.dataset.textSize
}

/** Restores the saved preference before the first render; defaults to the normal size. */
export function initializeTextSize() {
  let size: TextSize = 'normal'
  try {
    if (localStorage.getItem(storageKey) === 'large') size = 'large'
  } catch { /* Keep the default when browser storage is unavailable. */ }
  applyTextSize(size)
}

export function getTextSize(): TextSize {
  return document.documentElement.dataset.textSize === 'large' ? 'large' : 'normal'
}

export function toggleTextSize() {
  const size = getTextSize() === 'large' ? 'normal' : 'large'
  applyTextSize(size)
  try {
    localStorage.setItem(storageKey, size)
  } catch { /* Switching still works without persistent storage. */ }
  window.dispatchEvent(new Event(changeEvent))
}

export function subscribeToTextSize(onChange: () => void) {
  function onStorage(event: StorageEvent) {
    if (event.key !== storageKey && event.key !== null) return
    initializeTextSize()
    onChange()
  }
  window.addEventListener(changeEvent, onChange)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(changeEvent, onChange)
    window.removeEventListener('storage', onStorage)
  }
}
