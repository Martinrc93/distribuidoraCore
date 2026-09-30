import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'

type Option = { id: string; name: string }
type Props = {
  label: string
  options: Option[]
  value: string
  onChange: (value: string) => void
  allLabel: string
  unavailableLabel: string
  loadingLabel: string
  loading?: boolean
  disabled?: boolean
  className?: string
}

const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es').trim()

export function SearchableSelect({ label, options, value, onChange, allLabel, unavailableLabel, loadingLabel, loading = false, disabled = false, className = '' }: Props) {
  const id = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState<string | null>(null)
  const [active, setActive] = useState(-1)
  const expanded = open && !disabled
  const selectedName = value ? options.find((option) => option.id === value)?.name ?? unavailableLabel : allLabel
  const matches = options.filter((option) => normalize(option.name).includes(normalize(search ?? '')))
  const visibleOptions = [{ id: '', name: allLabel }, ...matches]
  const activeOption = expanded && active >= 0 && active < visibleOptions.length ? active : -1

  useEffect(() => {
    if (activeOption >= 0) listRef.current?.children[activeOption]?.scrollIntoView?.({ block: 'nearest' })
  }, [activeOption])

  function close() {
    setOpen(false)
    setSearch(null)
    setActive(-1)
  }

  function choose(option: Option) {
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
      event.preventDefault()
      if (activeOption >= 0) choose(visibleOptions[activeOption])
    } else if (event.key === 'Escape') {
      if (expanded) event.stopPropagation()
      close()
    } else if (event.key === 'Tab') {
      close()
    }
  }

  return <div className={`field searchable-select ${className}`} onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) close()
  }}>
    <label htmlFor={id}>{label}</label>
    <div className="searchable-select-control">
      <input ref={inputRef} id={id} className="input" role="combobox" aria-autocomplete="list" aria-expanded={expanded}
        aria-controls={expanded ? `${id}-list` : undefined} aria-activedescendant={activeOption >= 0 ? `${id}-option-${activeOption}` : undefined}
        autoComplete="off" disabled={disabled} placeholder="Buscar por nombre…"
        value={loading ? loadingLabel : expanded && search !== null ? search : selectedName}
        onFocus={(event) => event.target.select()} onClick={(event) => { if (!expanded) event.currentTarget.select(); setOpen(true) }} onKeyDown={handleKeyDown}
        onChange={(event) => { setSearch(event.target.value); setOpen(true); setActive(-1) }} />
      <button type="button" className="searchable-select-toggle" aria-label={`Abrir opciones: ${label}`} tabIndex={-1}
        disabled={disabled} aria-expanded={expanded} onMouseDown={(event) => event.preventDefault()}
        onClick={() => { if (expanded) close(); else setOpen(true); inputRef.current?.focus() }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      {expanded && <div className="searchable-select-menu">
        <ul ref={listRef} id={`${id}-list`} role="listbox" aria-label={label}>
          {visibleOptions.map((option, index) => <li key={option.id} id={`${id}-option-${index}`} role="option"
            aria-selected={value === option.id} className={index === activeOption ? 'active' : ''}
            onMouseDown={(event) => event.preventDefault()} onMouseMove={() => setActive(index)} onClick={() => choose(option)}>{option.name}</li>)}
        </ul>
        {matches.length === 0 && <p role="status">No se encontraron coincidencias.</p>}
      </div>}
    </div>
  </div>
}
