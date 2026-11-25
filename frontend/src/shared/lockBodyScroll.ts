let activeLocks = 0
let previousOverflow = ''

/** Release only after the last nested modal has closed. */
export function lockBodyScroll() {
  if (activeLocks === 0) {
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  activeLocks += 1
  let released = false
  return () => {
    if (released) return
    released = true
    activeLocks -= 1
    if (activeLocks === 0) document.body.style.overflow = previousOverflow
  }
}
