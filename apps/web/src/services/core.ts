import { useConfigStore } from '../stores/config'
import { DEFAULT_SCHEDULER_PROVIDER_ID } from '../domain/schedulers'
import type { Attempt, Backlog, KnowledgeCatalogTree, KnowledgeDomain, KnowledgeTopicDetail, KnowledgeTopicSearchResponse, NextAssignment, Overview, RejectedGeneratedQuestion, Session, TrainingSettings } from '../types/api'

export class CoreApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message)
  }
}

function idempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const config = useConfigStore()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body) headers.set('Content-Type', 'application/json')
  if (config.token) headers.set('Authorization', `Bearer ${config.token}`)

  const response = await fetch(`${config.apiBaseUrl}${path}`, { ...options, headers })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new CoreApiError(body.message || `Training Core returned HTTP ${response.status}`, response.status, body.code)
  }
  return response.json() as Promise<T>
}

function post<T>(path: string, body?: unknown, prefix = 'web'): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey(prefix) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export const coreApi = {
  overview: () => request<Overview>('/reports/overview'),
  backlog: () => request<Backlog>('/schedulers/backlog'),
  createSession: (domainId = 'java-backend') =>
    post<Session>('/sessions', {
      domainId,
      schedulerProvider: DEFAULT_SCHEDULER_PROVIDER_ID,
    }, 'session'),
  nextAssignment: (sessionId: string) =>
    post<NextAssignment>(`/sessions/${encodeURIComponent(sessionId)}/assignments/next`, undefined, 'next'),
  submitAnswer: (assignmentId: string, answer: string) =>
    post<Attempt>(`/assignments/${encodeURIComponent(assignmentId)}/attempts`, { answer }, 'answer'),
  rejectGeneratedQuestion: (assignmentId: string) =>
    post<RejectedGeneratedQuestion>(`/assignments/${encodeURIComponent(assignmentId)}/reject`, undefined, 'reject'),
  finishSession: (sessionId: string) =>
    post<Session>(`/sessions/${encodeURIComponent(sessionId)}/finish`, undefined, 'finish'),
  trainingSettings: () => request<TrainingSettings>('/settings/training'),
  updateTrainingSettings: (settings: Omit<TrainingSettings, 'reviewBudget' | 'updatedAt' | 'updatedBy'>) =>
    request<TrainingSettings>('/settings/training', {
      method: 'PUT',
      headers: { 'Idempotency-Key': idempotencyKey('training-settings') },
      body: JSON.stringify(settings),
    }),
  knowledgeDomains: (query = '') => request<KnowledgeDomain[]>(`/catalog/domains${query ? `?q=${encodeURIComponent(query)}` : ''}`),
  knowledgeCatalogTree: (domainId: string) =>
    request<KnowledgeCatalogTree>(`/catalog/domains/${encodeURIComponent(domainId)}/tree`),
  searchKnowledgeTopics: (query: string, domainId?: string) => {
    const params = new URLSearchParams({ q: query, limit: '100' })
    if (domainId) params.set('domainId', domainId)
    return request<KnowledgeTopicSearchResponse>(`/catalog/topics/search?${params}`)
  },
  knowledgeTopic: (topicId: string) =>
    request<KnowledgeTopicDetail>(`/catalog/topics/${encodeURIComponent(topicId)}`),
}
