import { useConfigStore } from '../stores/config'
import type { Attempt, Backlog, NextAssignment, Overview, Session } from '../types/api'

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
  createSession: (questionCount = 10, domainId = 'java-backend') =>
    post<Session>('/sessions', { questionCount, domainId, schedulerProvider: 'weighted' }, 'session'),
  nextAssignment: (sessionId: string) =>
    post<NextAssignment>(`/sessions/${encodeURIComponent(sessionId)}/assignments/next`, undefined, 'next'),
  submitAnswer: (assignmentId: string, answer: string) =>
    post<Attempt>(`/assignments/${encodeURIComponent(assignmentId)}/attempts`, { answer }, 'answer'),
  finishSession: (sessionId: string) =>
    post<Session>(`/sessions/${encodeURIComponent(sessionId)}/finish`, undefined, 'finish'),
}
