import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { coreApi } from '../services/core'
import type { Backlog, Overview } from '../types/api'

const EMPTY_OVERVIEW: Overview = {
  attempts: 0,
  correct: 0,
  accuracy: 0,
  completedSessions: 0,
  dueCount: 0,
  newItemAllowance: 2,
  newItemsPaused: false,
  publishedQuestions: 0,
  candidateQuestions: 0,
  weakTopics: [],
}

export const useDashboardStore = defineStore('dashboard', () => {
  const overview = ref<Overview>({ ...EMPTY_OVERVIEW })
  const backlog = ref<Backlog | null>(null)
  const loading = ref(false)
  const error = ref('')
  const accuracyPercent = computed(() => Math.round(overview.value.accuracy * 100))

  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      const [nextOverview, nextBacklog] = await Promise.all([coreApi.overview(), coreApi.backlog()])
      overview.value = nextOverview
      backlog.value = nextBacklog
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法连接 Training Core'
    } finally {
      loading.value = false
    }
  }

  return { overview, backlog, loading, error, accuracyPercent, refresh }
})
