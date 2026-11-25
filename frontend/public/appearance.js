try {
  document.documentElement.dataset.theme = localStorage.getItem('distribuidora.theme') === 'dark' ? 'dark' : 'light'
  if (localStorage.getItem('distribuidora.text-size') === 'large') {
    document.documentElement.dataset.textSize = 'large'
  }
} catch {
  document.documentElement.dataset.theme = 'light'
}
