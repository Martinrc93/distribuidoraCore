import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  getConfiguredPrinter,
  getPrinterPreferences,
  inferPrintFormat,
  listLocalPrinters,
  printAgentBaseUrl,
  PrintAgentError,
  printPdfWithLocalAgent,
  printTestWithLocalAgent,
  setPrinterPreference,
} from './printers'

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('printers preferences and local agent client', () => {
  it('infers a4 or ticket format from document endpoints', () => {
    expect(inferPrintFormat('/api/orders/123/documents/ticket')).toBe('ticket')
    expect(inferPrintFormat('/api/documents/customers/5/statement?format=ticket')).toBe('ticket')
    expect(inferPrintFormat('/api/orders/123/documents/a4')).toBe('a4')
    expect(inferPrintFormat('/api/documents/customers/5/statement?format=a4')).toBe('a4')
    expect(inferPrintFormat('/api/documents/price-lists/9')).toBe('a4')
  })

  it('persists A4 and ticket printers in localStorage and removes the key when cleared', () => {
    expect(getPrinterPreferences()).toEqual({ a4: '', ticket: '' })

    expect(setPrinterPreference('a4', '  HP LaserJet Pro  ')).toBeUndefined()
    expect(setPrinterPreference('ticket', 'EPSON TM-T20III')).toBeUndefined()

    expect(getConfiguredPrinter('a4')).toBe('HP LaserJet Pro')
    expect(getConfiguredPrinter('ticket')).toBe('EPSON TM-T20III')
    expect(JSON.parse(localStorage.getItem('distribuidora.printers') ?? '{}')).toEqual({
      a4: 'HP LaserJet Pro',
      ticket: 'EPSON TM-T20III',
    })

    setPrinterPreference('a4', '')
    expect(getConfiguredPrinter('a4')).toBe('')
    expect(getConfiguredPrinter('ticket')).toBe('EPSON TM-T20III')

    setPrinterPreference('ticket', '')
    expect(localStorage.getItem('distribuidora.printers')).toBeNull()
  })

  it('ignores malformed localStorage content', () => {
    localStorage.setItem('distribuidora.printers', 'not-json')
    expect(getPrinterPreferences()).toEqual({ a4: '', ticket: '' })
  })

  it('lists printers from the local print agent', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      defaultPrinter: 'HP LaserJet',
      printers: [
        { name: 'HP LaserJet', isDefault: true },
        { name: 'EPSON TM-T20III', isDefault: false },
      ],
    }), { status: 200 }))

    const result = await listLocalPrinters()
    expect(fetchMock).toHaveBeenCalledWith(`${printAgentBaseUrl}/printers`, expect.objectContaining({ method: 'GET' }))
    expect(result).toEqual({
      defaultPrinter: 'HP LaserJet',
      printers: [
        { name: 'HP LaserJet', isDefault: true },
        { name: 'EPSON TM-T20III', isDefault: false },
      ],
    })
  })

  it('sends PDF bytes to the selected printer and distinguishes unreachable vs printer errors', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    const pdf = new Blob(['%PDF-1.4'], { type: 'application/pdf' })

    await printPdfWithLocalAgent(pdf, 'EPSON TM-T20III', 'ticket', '000100001-ticket.pdf')
    expect(fetchMock).toHaveBeenCalledWith(
      `${printAgentBaseUrl}/print?printer=EPSON+TM-T20III&format=ticket&jobName=000100001-ticket.pdf`,
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/pdf' },
        body: pdf,
      }),
    )

    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(printPdfWithLocalAgent(pdf, 'EPSON TM-T20III', 'ticket')).rejects.toMatchObject({
      name: 'PrintAgentError',
      unreachable: true,
    })

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ error: "La impresora 'Vieja' no esta instalada en esta PC." }), { status: 404 }))
    await expect(printPdfWithLocalAgent(pdf, 'Vieja', 'a4')).rejects.toEqual(
      expect.objectContaining<Partial<PrintAgentError>>({
        name: 'PrintAgentError',
        unreachable: false,
        message: "La impresora 'Vieja' no esta instalada en esta PC.",
      }),
    )
  })

  it('sends test print requests to the local print agent', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }))

    await printTestWithLocalAgent('HP LaserJet', 'a4')
    expect(fetchMock).toHaveBeenCalledWith(
      `${printAgentBaseUrl}/test?printer=HP+LaserJet&format=a4`,
      expect.objectContaining({ method: 'POST' }),
    )
  })
})
