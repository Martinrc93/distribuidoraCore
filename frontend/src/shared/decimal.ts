/** Normalizes integer and decimal input (dot or Argentine comma, with optional thousands dots) to a plain decimal string. */
export function normalizeDecimalString(raw: string): string | null {
  const trimmed = raw.trim()
  if (!trimmed) return null

  if (trimmed.includes(',')) {
    if (!/^[+-]?(?:\d{1,3}(?:\.\d{3})+|\d+),\d+$/.test(trimmed)) return null
    return trimmed.replaceAll('.', '').replace(',', '.')
  }

  const dotCount = (trimmed.match(/\./g) ?? []).length
  if (dotCount >= 2) {
    if (!/^[+-]?\d{1,3}(?:\.\d{3})+$/.test(trimmed)) return null
    return trimmed.replaceAll('.', '')
  }

  if (!/^[+-]?\d+(?:\.\d+)?$/.test(trimmed)) return null
  return trimmed
}

/** Parses a decimal string into a finite number, returning NaN when malformed or empty. */
export function parseDecimalInput(raw: string): number {
  const normalized = normalizeDecimalString(raw)
  return normalized === null ? Number.NaN : Number(normalized)
}
