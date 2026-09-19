import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from 'react'

type BaseProps = {
  children: ReactNode
  variant?: 'primary' | 'secondary' | 'ghost' | 'link'
  fullWidth?: boolean
}

type ButtonProps = BaseProps & ButtonHTMLAttributes<HTMLButtonElement> & { href?: never }
type LinkProps = BaseProps & AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }

export function Button({ children, variant = 'primary', fullWidth = false, ...props }: ButtonProps | LinkProps) {
  const className = `button button-${variant}${fullWidth ? ' button-full' : ''}`
  if ('href' in props && props.href) {
    return <a className={className} {...props}>{children}</a>
  }
  return <button className={className} {...props as ButtonHTMLAttributes<HTMLButtonElement>}>{children}</button>
}
