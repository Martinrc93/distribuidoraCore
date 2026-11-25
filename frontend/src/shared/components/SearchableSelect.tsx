import { useEffect, useId, useImperativeHandle, useMemo, useRef, useState, type KeyboardEvent, type Ref } from 'react'
import { CurrencyText, hasCurrencyAmount } from './CurrencyText'

/** `detail` is shown next to the name (e.g. stock) and exposed as the option description; it is not searched. */
type Option = { id: string; name: string; detail?: string }
export type SearchableSelectHandle = { focus: (options?: { open?: boolean }) => void }
type Props = {
  ref?: Ref<SearchableSelectHandle>
  label: string
  options: Option[]
  value: string
  onChange: (value: string) => void
  onEmptyEnter?: (event: KeyboardEvent<HTMLInputElement>) => void
  allLabel: string
  unavailableLabel: string
  loadingLabel: string
  loading?: boolean
  disabled?: boolean
  autoFocus?: boolean
  required?: boolean
  invalid?: boolean
  describedBy?: string
  fullWidth?: boolean
  preserveSearch?: boolean
  className?: string
}

const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es').trim()

export function SearchableSelect({ ref, label, options, value, onChange, onEmptyEnter, allLabel, unavailableLabel, loadingLabel, loading = false, disabled = false, autoFocus = false, required = false, invalid, describedBy, fullWidth = false, preserveSearch = false, className = '' }: Props) {
  const id = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState<string | null>(null)
  const [retainedSearch, setRetainedSearch] = useState('')
  const [active, setActive] = useState(-1)
  const expanded = open && !disabled && !loading
  const selectedName = value ? options.find((option) => option.id === value)?.name ?? unavailableLabel : allLabel
  const previousSearch = preserveSearch ? retainedSearch : ''
  const query = normalize(search ?? previousSearch)
  const indexedOptions = useMemo(() => options.map(option => ({ option, searchName: normalize(option.name) })), [options])
  const visibleOptions = useMemo(() => [
    { id: '', name: allLabel },
    ...indexedOptions.filter(({ searchName }) => searchName.includes(query)).map(({ option }) => option),
  ], [allLabel, indexedOptions, query])
  const activeOption = expanded && active >= 0 && active < visibleOptions.length ? active : -1

  useImperativeHandle(ref, () => ({
    focus({ open = false } = {}) {
      const input = inputRef.current
      if (!input || input.disabled) return
      input.focus()
      if (open) setOpen(true)
    },
  }), [])

  useEffect(() => {
    if (activeOption >= 0) listRef.current?.children[activeOption]?.scrollIntoView?.({ block: 'nearest' })
  }, [activeOption])

  useEffect(() => {
    inputRef.current?.setCustomValidity(required && !value ? 'Selecciona una opción de la lista.' : '')
  }, [required, value])

  function close() {
    setOpen(false)
    setSearch(null)
    setActive(-1)
  }

  function choose(option: Option) {
    if (preserveSearch) setRetainedSearch(option.id ? search ?? retainedSearch : '')
    onChange(option.id)
    close()
    inputRef.current?.focus()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      const direction = event.key === 'ArrowDown' ? 1 : -1
      setActive(activeOption < 0 ? direction > 0 ? 0 : visibleOptions.length - 1 : (activeOption + direction + visibleOptions.length) % visibleOptions.length)
    } else if (event.key === 'Enter' && expanded) {
      if (onEmptyEnter && !value && (activeOption < 0 || !visibleOptions[activeOption].id)) {
        onEmptyEnter(event)
        event.preventDefault()
        event.stopPropagation()
        return
      }
      event.preventDefault()
      if (activeOption >= 0) choose(visibleOptions[activeOption])
    } else if (event.key === 'Escape') {
      if (expanded) {
        event.preventDefault()
        event.stopPropagation()
      }
      close()
    } else if (event.key === 'Tab') {
      close()
    }
  }

  return <div className={`field searchable-select${fullWidth ? ' searchable-select-full' : ''} ${className}`} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) close()
  }}>
    <label htmlFor={id}>{label}</label>
    <div className="searchable-select-control">
      <input ref={inputRef} id={id} className={`input${hasCurrencyAmount(selectedName) ? ' currency-input' : ''}`} role="combobox" aria-autocomplete="list" aria-expanded={expanded}
        aria-controls={expanded ? `${id}-list` : undefined} aria-activedescendant={activeOption >= 0 ? `${id}-option-${activeOption}` : undefined}
        aria-required={required || undefined} aria-invalid={invalid} aria-describedby={describedBy} aria-busy={loading || undefined}
        autoComplete="off" autoFocus={autoFocus} disabled={disabled || loading} placeholder="Buscar por nombre…"
        value={loading ? loadingLabel : expanded && search !== null ? search : previousSearch && (expanded || !value) ? previousSearch : selectedName}
        onFocus={(event) => event.target.select()} onClick={(event) => { if (!expanded) event.currentTarget.select(); setOpen(true) }} onKeyDown={handleKeyDown}
        onChange={(event) => { setSearch(event.target.value); setOpen(true); setActive(-1) }} />
      <button type="button" className="searchable-select-toggle" aria-label={`Abrir opciones: ${label}`} tabIndex={-1}
        disabled={disabled || loading} aria-expanded={expanded} onMouseDown={(event) => event.preventDefault()}
        onClick={() => { if (expanded) close(); else setOpen(true); inputRef.current?.focus() }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      {expanded && <div className="searchable-select-menu">
        <ul ref={listRef} id={`${id}-list`} role="listbox" aria-label={label}>
          {visibleOptions.map((option, index) => <li key={option.id} id={`${id}-option-${index}`} role="option"
            aria-selected={value === option.id} className={[index === activeOption ? 'active' : '', option.detail ? 'has-detail' : ''].filter(Boolean).join(' ')}
            aria-label={option.detail ? option.name : undefined} aria-describedby={option.detail ? `${id}-option-${index}-detail` : undefined}
            onMouseDown={(event) => event.preventDefault()} onMouseMove={() => setActive(index)} onClick={() => choose(option)}><CurrencyText>{option.name}</CurrencyText>{option.detail && <span id={`${id}-option-${index}-detail`} className="searchable-select-detail">{option.detail}</span>}</li>)}
        </ul>
        {visibleOptions.length === 1 && <p role="status">No se encontraron coincidencias.</p>}
      </div>}
    </div>
  </div>
}
