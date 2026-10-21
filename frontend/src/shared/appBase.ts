/** Keep routes, API requests and credentials inside the deployed instance. */
export function appPath(path: string) {
  if (!path.startsWith('/') || path.startsWith('//')) return path
  return `${import.meta.env.BASE_URL.replace(/\/$/, '')}${path}`
}

export function instanceStorageKey(key: string) {
  const base = import.meta.env.BASE_URL
  return base === '/' ? key : `${key}:${base}`
}
