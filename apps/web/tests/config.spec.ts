import { describe, expect, it } from 'vitest'
import { normalizeApiBase } from '../src/stores/config'

describe('normalizeApiBase', () => {
  it('keeps the same-origin API default', () => {
    expect(normalizeApiBase('')).toBe('/api/v1')
    expect(normalizeApiBase('/api/v1/')).toBe('/api/v1')
  })

  it('adds the Core API prefix to a remote origin', () => {
    expect(normalizeApiBase('https://train.example.com/')).toBe('https://train.example.com/api/v1')
    expect(normalizeApiBase('https://train.example.com/api/v1')).toBe('https://train.example.com/api/v1')
  })
})
