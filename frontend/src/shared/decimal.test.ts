import { describe, expect, it } from 'vitest'
import { normalizeDecimalString, parseDecimalInput } from './decimal'

describe('decimal input parser', () => {
  it.each([
    ['1250,50', '1250.50', 1250.5],
    ['1.250,50', '1250.50', 1250.5],
    ['1.250.000,25', '1250000.25', 1250000.25],
    ['1.250.000', '1250000', 1250000],
    ['0,5', '0.5', 0.5],
    ['0.5', '0.5', 0.5],
    ['10.2500', '10.2500', 10.25],
    ['  1.250,50  ', '1250.50', 1250.5],
  ])('parses %s to %s', (raw, expectedString, expectedNumber) => {
    expect(normalizeDecimalString(raw)).toBe(expectedString)
    expect(parseDecimalInput(raw)).toBe(expectedNumber)
  })

  it.each(['', '   ', 'abc', '12.34,56', '1.2.3', '1,2,3', '12,'])('rejects malformed input %s', (raw) => {
    expect(normalizeDecimalString(raw)).toBeNull()
    expect(parseDecimalInput(raw)).toBeNaN()
  })
})
