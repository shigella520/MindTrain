export function dailyProgressPercent(completed: number, target: number): number {
  if (target <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((completed / target) * 100)))
}

export function quotaSummary(reviewBudget: number, newBudget: number): string {
  return `${reviewBudget} 道复习题与 ${newBudget} 道新题`
}
