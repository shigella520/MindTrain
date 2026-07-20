import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const STORAGE_KEY = 'mindtrain.web.config.v1'

interface SavedConfig {
  apiBaseUrl: string
  token: string
}

function readSavedConfig(): SavedConfig {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) return { apiBaseUrl: '/api/v1', token: '', ...JSON.parse(saved) }
  } catch {
    // Ignore invalid browser state and allow the user to configure again.
  }
  return { apiBaseUrl: '/api/v1', token: '' }
}

export const useConfigStore = defineStore('config', () => {
  const saved = readSavedConfig()
  const apiBaseUrl = ref(saved.apiBaseUrl)
  const token = ref(saved.token)
  const configured = computed(() => token.value.trim().length > 0)

  function save(next: SavedConfig) {
    apiBaseUrl.value = normalizeApiBase(next.apiBaseUrl)
    token.value = next.token.trim()
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ apiBaseUrl: apiBaseUrl.value, token: token.value }))
  }

  function clear() {
    apiBaseUrl.value = '/api/v1'
    token.value = ''
    localStorage.removeItem(STORAGE_KEY)
  }

  return { apiBaseUrl, token, configured, save, clear }
})

export function normalizeApiBase(input: string): string {
  const value = input.trim().replace(/\/+$/, '')
  if (!value) return '/api/v1'
  if (value.endsWith('/api/v1')) return value
  if (value.startsWith('/')) return value
  return `${value}/api/v1`
}
