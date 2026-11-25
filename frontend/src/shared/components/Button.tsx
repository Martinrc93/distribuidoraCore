import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, useInRouterContext } from 'react-router-dom'
import { appPath } from '../appBase'

type BaseProps = {
  children: ReactNode
  variant?: 'primary' | 'secondary' | 'ghost' | 'link' | 'danger'
  fullWidth?: boolean
}

type ButtonProps = BaseProps & ButtonHTMLAttributes<HTMLButtonElement> & { href?: never }
type LinkProps = BaseProps & AnchorHTMLAttributes<HTMLAnchorElement> & { href: string; external?: boolean }

export function Button({ children, variant = 'primary', fullWidth = false, className, ...props }: ButtonProps | LinkProps) {
  const inRouter = useInRouterContext()
  const classes = [`button button-${variant}`, fullWidth && 'button-full', className].filter(Boolean).join(' ')
  if (typeof props.href === 'string') {
    const { href, external, ...anchor } = props as LinkProps
    const internal = href.startsWith('/') && !href.startsWith('//') && !external && anchor.download == null
    if (internal && inRouter) return <Link {...anchor} className={classes} to={href}>{children}</Link>
    return <a {...anchor} className={classes} href={internal ? appPath(href) : href}>{children}</a>
  }
  return <button {...props as ButtonHTMLAttributes<HTMLButtonElement>} className={classes}>{children}</button>
}
