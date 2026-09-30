import '@testing-library/jest-dom/vitest'

// jsdom does not implement the native modal dialog methods.
if (!HTMLDialogElement.prototype.showModal) {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
}
if (!HTMLDialogElement.prototype.close) {
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
}
