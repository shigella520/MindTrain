import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { DEFAULT_SCHEDULER_PROVIDER_ID, schedulerProviderName } from '../domain/schedulers'
import { coreApi } from '../services/core'
import type { Overview } from '../types/api'

const EMPTY_OVERVIEW: Overview = {
  attempts: 0,
  correct: 0,
  accuracy: 0,
  todayCompletedMainQuestions: 0,
  dailyTarget: 0,
  reviewBudget: 0,
  newBudget: 0,
  reportingTimeZone: '',
  completedSessions: 0,
  dueCount: 0,
  newItemAllowance: 0,
  newItemsPaused: false,
  schedulerProvider: DEFAULT_SCHEDULER_PROVIDER_ID,
  schedulerProviderName: schedulerProviderName(DEFAULT_SCHEDULER_PROVIDER_ID),
  activeQuestions: 0,
  pendingGeneratedQuestions: 0,
  knowledgeDomainCount: 0,
  knowledgeTopicCount: 0,
  weakTopics: [],
}

export const useDashboardStore = defineStore('dashboard', () => {
  const overview = ref<Overview>({ ...EMPTY_OVERVIEW })
  const loading = ref(false)
  const error = ref('')
  const accuracyPercent = computed(() => Math.round(overview.value.accuracy * 100))
  const schedulerName = computed(() => overview.value.schedulerProviderName
    || schedulerProviderName(overview.value.schedulerProvider || DEFAULT_SCHEDULER_PROVIDER_ID))

  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      overview.value = await coreApi.overview()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '无法连接 Training Core'
    } finally {
      loading.value = false
    }
  }

  return { overview, loading, error, accuracyPercent, schedulerName, refresh }
})
