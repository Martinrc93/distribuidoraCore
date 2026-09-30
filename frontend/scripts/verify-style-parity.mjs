import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from '@playwright/test'

const [baselineFile, buildDirectory = 'dist-tailwind-check', outputDirectory = 'test-results/style-parity'] = process.argv.slice(2)
if (!baselineFile) throw new Error('Usage: node scripts/verify-style-parity.mjs <baseline.css> [build-directory] [output-directory]')
const baseline = fs.readFileSync(baselineFile, 'utf8')
const assets = path.join(buildDirectory, 'assets')
const candidate = fs.readdirSync(assets).filter((file) => file.endsWith('.css')).map((file) => fs.readFileSync(path.join(assets, file), 'utf8')).join('\n')
fs.mkdirSync(outputDirectory, { recursive: true })

// Exercise representative component markup without API data or a database dependency.
const button = (text, variant = 'secondary', attributes = '') => `<button type="button" class="button button-${variant}" ${attributes}>${text}</button>`
const table = (variant, count) => `<div class="table-wrap ${variant}"><table class="data-table"><thead><tr>${Array.from({ length: count }, (_, i) => `<th class="${i > 2 ? 'align-right' : ''}">Column ${i + 1}</th>`).join('')}</tr></thead><tbody>${[0, 1].map((row) => `<tr class="clickable-row">${Array.from({ length: count }, (_, i) => `<td data-label="Column ${i + 1}" class="${i === 0 ? 'cell-emphasis' : 'align-right'}">${i === count - 1 ? `<div class="product-row-controls"><span class="badge badge-strong">Active</span><div class="table-row-actions">${button('Edit')}${button('Deactivate', 'danger')}</div></div>` : i === 0 ? `Product ${row + 1}` : '125.50'}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`
const field = `<label class="field"><span>Name</span><input class="input" value="Example product"></label>`
const body = `<div class="app-shell"><aside class="sidebar"><div class="brand-block"><span class="brand-mark">D</span><div><strong>Distribuidora</strong><span>Operations</span></div></div><nav class="main-nav"><div class="nav-group"><span class="nav-group-label">Operations</span><a class="nav-link active" href="#"><span class="nav-dot"></span>Products</a><a class="nav-link" href="#">Customers</a></div></nav><div class="sidebar-footer"><div class="user-chip"><span class="avatar">AD</span><div><strong>Admin</strong><small>Administration</small></div></div>${button('Sign out', 'ghost')}</div></aside><main class="main-area"><header class="topbar"><span class="breadcrumbs">Catalog / Products</span><div class="topbar-actions"><span class="connection-status"><i class="status-dot"></i>Connected</span></div></header><div class="page-content"><div class="page-header"><div><span class="eyebrow">Catalog</span><h1>Products</h1><p>Manage products and their prices.</p></div><div class="page-actions">${button('Cancel')}${button('New product', 'primary', 'id="primary"')}</div></div><div class="stats-grid">${[1, 2, 3, 4].map((i) => `<article class="stat-card ${i === 1 ? 'stat-card-emphasis' : ''}"><span>Metric ${i}</span><strong>125.50</strong><small>Current amount</small></article>`).join('')}</div><section class="panel"><div class="panel-header"><div><h2>Product catalog</h2><p>Current products</p></div>${button('Export')}</div><div class="toolbar"><input class="input search-input" placeholder="Search" id="search"><select class="select"><option>All products</option></select>${button('Filter')}</div>${table('products-table-admin', 6)}${table('products-table-standard', 5)}<div class="pagination"><span>Page 1 of 2</span><div>${button('Previous', 'secondary', 'disabled')}${button('Next')}</div></div></section><section class="panel"><form class="form-grid">${field}<label class="field"><span>Category</span><select class="select"><option>Grocery</option></select></label><label class="field"><span>Notes</span><textarea class="input textarea">Product notes</textarea></label><div class="page-actions customer-form-actions">${button('Discard', 'danger')}<div class="customer-form-actions-right">${button('Cancel')}${button('Save', 'primary')}</div></div></form><p class="error-text">The product could not be saved.</p><p class="success-text">Product saved.</p><p class="warning-text">Stock will be negative.</p><p class="helper-text">Choose a product.</p><span class="badge badge-soft">Pending</span> <span class="badge badge-muted">Inactive</span>${button('View details', 'link')}<div class="empty-state"><div class="empty-icon">—</div><h3>No results</h3><p>Try another search.</p>${button('Clear filters')}</div></section><div class="price-lists-page"><div class="pricing-section-tabs"><button class="pricing-section-tab selected">Prices</button><button class="pricing-section-tab">History</button></div><section class="panel"><div class="panel-header"><div><h2>Price lists</h2><p>Select a list</p></div><div class="price-list-controls"><label class="price-list-filter">Status<select class="select"><option>Active</option></select></label>${button('Create')}</div></div><div class="price-list-selector">${[0, 1].map((i) => `<button class="price-list-card ${i === 0 ? 'selected' : ''}"><span class="price-list-card-heading"><strong>List ${i + 1}</strong><span class="price-list-default">Default</span></span><span class="price-list-code">GENERAL</span><span class="price-list-status active"><i></i>Active</span></button>`).join('')}</div><div class="page-actions price-list-actions">${button('Rename')}${button('Deactivate', 'danger')}</div>${table('price-products-table price-products-table-admin', 5)}${table('price-products-table price-products-table-standard', 4)}</section></div></div></main></div>`
const catalogDialog = `<div class="modal-backdrop catalog-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="catalog-title"><section class="panel catalog-modal"><header class="catalog-modal-header"><p class="catalog-modal-kicker">Brands</p><h2 id="catalog-title">New brand</h2><p>Complete the information to create a brand.</p></header><form><div class="catalog-modal-body">${field}<p class="error-text">A brand with this name already exists.</p></div><footer class="catalog-modal-footer">${button('Cancel')}${button('Save brand', 'primary')}</footer></form></section></div>`
const priceDialog = `<div class="price-status-dialog-backdrop" role="dialog" aria-modal="true" aria-labelledby="price-title"><section class="price-status-dialog is-deactivating"><header class="price-status-dialog-header"><span class="price-status-dialog-icon"><svg viewBox="0 0 24 24"><path d="M4 12h16" stroke="currentColor"/></svg></span><div><p class="price-status-dialog-kicker">Price list</p><h2 id="price-title">Deactivate list</h2></div><button class="price-status-dialog-close">×</button></header><div class="price-status-dialog-body"><p>The selected list will be deactivated.</p><p class="price-status-dialog-error">Try again.</p><div class="price-status-dialog-summary"><span class="price-status-dialog-label">Selected list</span><strong>General price list</strong><div class="price-status-transition"><span class="price-status-pill active">Active</span><span class="price-status-pill">Inactive</span></div></div></div><footer class="price-status-dialog-footer">${button('Cancel')}${button('Deactivate', 'danger')}</footer></section></div>`

const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {}) })
let comparisons = 0
try {
  for (const width of [1440, 1050, 761, 760, 641, 640, 460, 390, 320]) {
    for (const state of ['default', 'hover', 'focus', 'catalog-dialog', 'price-dialog', 'reduced-motion']) {
      const pages = await Promise.all([baseline, candidate].map(async (css) => {
        const page = await browser.newPage({ viewport: { width, height: 1000 }, reducedMotion: state === 'reduced-motion' ? 'reduce' : 'no-preference' })
        await page.setContent(`<!doctype html><html lang="en"><head><meta charset="utf-8"><style>${css}</style></head><body>${body}${state === 'catalog-dialog' ? catalogDialog : state === 'price-dialog' ? priceDialog : ''}</body></html>`)
        if (state === 'hover') await page.locator('#primary').hover()
        if (state === 'focus') await page.locator('#search').focus()
        await page.evaluate(async () => { await document.fonts.ready; await new Promise((resolve) => setTimeout(resolve, 200)) })
        return page
      }))
      try {
        const snapshots = await Promise.all(pages.map((page) => page.evaluate(() => Array.from(document.querySelectorAll('body, body *')).map((element) => {
          const styles = [null, '::before', '::after'].map((pseudo) => {
            const computed = getComputedStyle(element, pseudo)
            return Object.fromEntries(Array.from(computed).filter((property) => !property.startsWith('--')).map((property) => [property, computed.getPropertyValue(property)]))
          })
          return { tag: element.tagName, class: element.className.baseVal ?? element.className, rect: element.getBoundingClientRect().toJSON(), styles }
        }))))
        const screenshots = await Promise.all(pages.map((page, i) => page.screenshot({ path: path.join(outputDirectory, `${width}-${state}-${i === 0 ? 'before' : 'after'}.png`), fullPage: true })))
        const differences = snapshots[1].flatMap((element, index) => {
          const previous = snapshots[0][index]
          const changes = element.styles.flatMap((style, pseudo) => Object.entries(style).filter(([key, value]) => previous.styles[pseudo][key] !== value).map(([key, value]) => `${key}: ${previous.styles[pseudo][key]} -> ${value} (pseudo ${pseudo})`))
          if (JSON.stringify(element.rect) !== JSON.stringify(previous.rect)) changes.push('Geometry changed')
          return changes.length ? [{ tag: element.tag, class: element.class, changes }] : []
        })
        assert.equal(differences.length, 0, `Style differences at ${width}px (${state}): ${JSON.stringify(differences.slice(0, 10), null, 2)}`)
        assert.ok(screenshots[0].equals(screenshots[1]), `Screenshot changed at ${width}px (${state})`)
        comparisons++
      } finally {
        await Promise.all(pages.map((page) => page.close()))
      }
    }
    console.log(`Style and screenshot parity verified at ${width}px`)
  }
  console.log(`${comparisons} comparisons passed. Screenshots: ${path.resolve(outputDirectory)}`)
} finally {
  await browser.close()
}
