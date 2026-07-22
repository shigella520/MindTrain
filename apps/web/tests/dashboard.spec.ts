import { describe, expect, it } from 'vitest'
import { dailyProgressPercent, knowledgeCatalogSummary, quotaSummary } from '../src/domain/dashboard'

describe('dashboard live metrics', () => {
  it('calculates progress from the server-provided daily target', () => {
    expect(dailyProgressPercent(4, 10)).toBe(40)
    expect(dailyProgressPercent(12, 10)).toBe(100)
    expect(dailyProgressPercent(4, 0)).toBe(0)
  })

  it('renders scheduler quotas without assuming an 8/2 split', () => {
    expect(quotaSummary(6, 4)).toBe('6 道复习题与 4 道新题')
  })

  it('renders real knowledge catalog aggregate counts', () => {
    expect(knowledgeCatalogSummary(2, 179)).toBe('2 个领域 · 179 个知识点')
    expect(knowledgeCatalogSummary(0, 0)).toBe('0 个领域 · 0 个知识点')
  })
})
