const moneyFormatters = new Map<number, Intl.NumberFormat>()
const numberFormatters = new Map<number, Intl.NumberFormat>()
const decimalInputFormatter = new Intl.NumberFormat('es-AR', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 4,
})

function getMoneyFormatter(maxDigits: number) {
  let formatter = moneyFormatters.get(maxDigits)
  if (!formatter) {
    formatter = new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: maxDigits,
    })
    moneyFormatters.set(maxDigits, formatter)
  }
  return formatter
}

function getNumberFormatter(maxDigits: number) {
  let formatter = numberFormatters.get(maxDigits)
  if (!formatter) {
    formatter = new Intl.NumberFormat('es-AR', { maximumFractionDigits: maxDigits })
    numberFormatters.set(maxDigits, formatter)
  }
  return formatter
}

export function formatMoney(value: unknown, maxDigits = 2) {
  return getMoneyFormatter(maxDigits).format(Number(value ?? 0))
}

export function formatNumber(value: unknown, maxDigits = 2) {
  return getNumberFormatter(maxDigits).format(Number(value ?? 0))
}

export function formatDecimalInput(value: number) {
  return decimalInputFormatter.format(value)
}
