export function dailyProgressPercent(completed: number, target: number): number {
  if (target <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((completed / target) * 100)))
}

export function knowledgeCatalogSummary(domainCount: number, topicCount: number): string {
  return `${domainCount} 个领域 · ${topicCount} 个知识点`
}

export type SchedulerStatus = 'healthy' | 'due' | 'overdue' | 'paused' | 'completed'
export type SchedulerTone = 'healthy' | 'attention' | 'warning' | 'completed'

export interface SchedulerCopyInput {
  status: SchedulerStatus
  dueCount: number
  oldestDueLabel: string
  todayCompleted: number
  dailyTarget: number
  todayNewItemsIntroduced: number
  pauseReason?: 'backlog_count' | 'overdue_age' | 'backlog_count_and_overdue_age'
}

export interface SchedulerCopy {
  title: string
  description: string
  badge: string
  tone: SchedulerTone
}

export function schedulerCopy(input: SchedulerCopyInput): SchedulerCopy {
  const progress = `今日已完成 ${input.todayCompleted} / ${input.dailyTarget} 道主问题`
  if (input.status === 'paused') {
    const reason = input.pauseReason === 'backlog_count_and_overdue_age'
      ? `当前有 ${input.dueCount} 道到期复习，且最老项目已超过逾期阈值`
      : input.pauseReason === 'overdue_age'
        ? `最老到期项目为 ${input.oldestDueLabel}，已超过逾期阈值`
        : `当前有 ${input.dueCount} 道到期复习，已超过积压阈值`
    return { title: '先消化积压，再学习新内容', description: `${reason}，新题引入已暂停；${progress}。`, badge: '新题暂停', tone: 'warning' }
  }
  if (input.status === 'completed') {
    return { title: '今日训练目标已经完成', description: `${progress}，今日已引入 ${input.todayNewItemsIntroduced} 道新题。`, badge: '已完成', tone: 'completed' }
  }
  if (input.status === 'overdue') {
    return { title: '有逾期复习需要优先处理', description: `当前有 ${input.dueCount} 道到期复习，最早到期于 ${input.oldestDueLabel}；${progress}。`, badge: '存在逾期', tone: 'warning' }
  }
  if (input.status === 'due') {
    return { title: `优先完成 ${input.dueCount} 道到期复习`, description: `${progress}，今日已引入 ${input.todayNewItemsIntroduced} 道新题。`, badge: '待复习', tone: 'attention' }
  }
  return { title: '当前没有到期复习', description: `${progress}，今日已引入 ${input.todayNewItemsIntroduced} 道新题。`, badge: '状态健康', tone: 'healthy' }
}
