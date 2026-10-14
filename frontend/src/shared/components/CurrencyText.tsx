import { Children, type ReactNode } from 'react'

const currencyPattern = /(?:[−-]\s*)?(?:(?:AR|US)\s*)?\$\s*\d(?:[\d.,]*\d)?/g

export function hasCurrencyAmount(text: string) {
  return new RegExp(currencyPattern.source).test(text)
}

/** Emphasize currency amounts while preserving surrounding text and markup. */
export function CurrencyText({ children }: { children: ReactNode }) {
  return <>{Children.map(children, (child) => {
    if (typeof child !== 'string') return child
    const parts: ReactNode[] = []
    let offset = 0
    for (const match of child.matchAll(currencyPattern)) {
      parts.push(child.slice(offset, match.index))
      parts.push(<b className="currency-value" key={match.index}>{match[0]}</b>)
      offset = match.index + match[0].length
    }
    if (offset === 0) return child
    parts.push(child.slice(offset))
    return <>{parts}</>
  })}</>
}
