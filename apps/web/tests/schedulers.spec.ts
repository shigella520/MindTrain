import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SCHEDULER_PROVIDER_ID,
  DEFAULT_SCHEDULER_PROVIDER_NAME,
  schedulerProviderName,
} from '../src/domain/schedulers'

describe('scheduler display names', () => {
  it('uses the unified Chinese name for the weighted provider', () => {
    expect(DEFAULT_SCHEDULER_PROVIDER_ID).toBe('weighted')
    expect(DEFAULT_SCHEDULER_PROVIDER_NAME).toBe('加权调度')
    expect(schedulerProviderName('weighted')).toBe('加权调度')
  })

  it('keeps an unknown provider ID visible for diagnostics', () => {
    expect(schedulerProviderName('anki')).toBe('anki')
  })
})
