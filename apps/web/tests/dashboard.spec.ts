import { describe, expect, it } from 'vitest'
import { dailyProgressPercent, knowledgeCatalogSummary, schedulerCopy } from '../src/domain/dashboard'

describe('dashboard live metrics', () => {
  it('calculates progress from the server-provided daily target', () => {
    expect(dailyProgressPercent(4, 10)).toBe(40)
    expect(dailyProgressPercent(12, 10)).toBe(100)
    expect(dailyProgressPercent(4, 0)).toBe(0)
  })

  it('renders scheduler state from live progress instead of configuration-only quotas', () => {
    expect(schedulerCopy({
      status: 'due', dueCount: 3, oldestDueLabel: '7月22日', todayCompleted: 4,
      dailyTarget: 10, todayNewItemsIntroduced: 2,
    })).toEqual({
      title: '优先完成 3 道到期复习',
      description: '今日已完成 4 / 10 道主问题，今日已引入 2 道新题。',
      badge: '待复习',
      tone: 'attention',
    })

    expect(schedulerCopy({
      status: 'paused', dueCount: 24, oldestDueLabel: '7月18日', todayCompleted: 2,
      dailyTarget: 10, todayNewItemsIntroduced: 1, pauseReason: 'backlog_count_and_overdue_age',
    }).description).toContain('24 道到期复习')

    expect(schedulerCopy({
      status: 'completed', dueCount: 0, oldestDueLabel: '没有逾期', todayCompleted: 10,
      dailyTarget: 10, todayNewItemsIntroduced: 3,
    }).badge).toBe('已完成')
  })

  it('renders real knowledge catalog aggregate counts', () => {
    expect(knowledgeCatalogSummary(2, 179)).toBe('2 个领域 · 179 个知识点')
    expect(knowledgeCatalogSummary(0, 0)).toBe('0 个领域 · 0 个知识点')
  })
})
