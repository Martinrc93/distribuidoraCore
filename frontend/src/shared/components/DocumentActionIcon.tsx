const paths = {
  print: 'M6 9V3h12v6 M6 18H5a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-1 M6 14h12v7H6z M17 12h1',
  download: 'M12 3v12 M7 10l5 5 5-5 M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3',
  email: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z M3 7l9 6 9-6',
  whatsapp: 'M3 21l1.4-4.6a8.7 8.7 0 1 1 3.3 3.2L3 21z M9 8l1.3 2-1 1.1c.7 1.3 1.8 2.4 3.1 3.1l1.1-1 2 1.3c-.2 1.3-1.1 1.7-2.3 1.4-2.7-.7-4.8-2.8-5.5-5.5C7.4 9.2 7.8 8.2 9 8z',
} as const

export function DocumentActionIcon({ action }: { action: keyof typeof paths }) {
  return <svg className="shrink-0 text-accent-dark" width="20" height="20" viewBox="0 0 24 24"
    fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={paths[action]} />
  </svg>
}
