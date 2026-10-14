import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import QuestionBankView from '../src/views/QuestionBankView.vue'
import TrainingView from '../src/views/TrainingView.vue'
import { coreApi, CoreApiError } from '../src/services/core'
import { useConfigStore } from '../src/stores/config'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('../src/services/core', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/services/core')>()
  return { ...actual, coreApi: {
    knowledgeDomains: vi.fn(), questions: vi.fn(), question: vi.fn(),
    deleteQuestion: vi.fn(), restoreQuestion: vi.fn(), createSession: vi.fn(),
    nextAssignment: vi.fn(), submitAnswer: vi.fn(),
  } }
})

let app: App | undefined
let container: HTMLDivElement
async function settle() { for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick() } }
async function mount(component: typeof QuestionBankView | typeof TrainingView) {
  container = document.createElement('div')
  document.body.append(container)
  const pinia = createPinia()
  app = createApp(component).use(pinia)
  app.component('RouterLink', { template: '<a><slot /></a>' })
  useConfigStore(pinia).token = 'test-only'
  app.mount(container)
  await settle()
}
async function click(text: string) {
  const button = [...container.querySelectorAll('button')].find(b => b.textContent?.trim() === text)
  expect(button, text).toBeTruthy()
  button!.click()
  await settle()
}
beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  })
})
afterEach(() => { app?.unmount(); container?.remove(); vi.unstubAllGlobals(); vi.resetAllMocks() })

describe('question maintenance UI', () => {
  it('confirms deletion, filters deleted questions and restores without exposing unanswered content', async () => {
    let deleted = false
    const summary = () => ({ id: 'q', version: 1, status: deleted ? 'deleted' : 'active', title: 'Test', stem: 'Question', type: 'single_choice', topicIds: [], sources: [], attemptCount: 0 })
    vi.mocked(coreApi.knowledgeDomains).mockResolvedValue([])
    vi.mocked(coreApi.questions).mockImplementation(async filters => ({ items: filters.status === (deleted ? 'deleted' : 'active') ? [summary()] : [], total: 1, nextCursor: null }) as never)
    vi.mocked(coreApi.question).mockImplementation(async () => ({ summary: summary(), answerVisible: false, question: { options: [{ id: 'A', text: 'Option' }] }, attempts: [] }) as never)
    vi.mocked(coreApi.deleteQuestion).mockImplementation(async () => { deleted = true; return {} as never })
    vi.mocked(coreApi.restoreQuestion).mockImplementation(async () => { deleted = false; return {} as never })
    await mount(QuestionBankView)
    expect(coreApi.questions).toHaveBeenCalledWith(expect.objectContaining({ status: 'active' }))
    container.querySelector<HTMLButtonElement>('.question-row')!.click(); await settle()
    expect(container.textContent).toContain('答案与解析已隐藏')
    await click('删除题目')
    expect(coreApi.deleteQuestion).not.toHaveBeenCalled()
    expect(container.textContent).toContain('删除后退出训练，保留历史答题记录')
    await click('确认删除')
    expect(coreApi.deleteQuestion).toHaveBeenCalledWith('q', 1, '不再需要训练这道题')
    const select = container.querySelector<HTMLSelectElement>('[aria-label="题目状态"]')!
    select.value = 'deleted'; select.dispatchEvent(new Event('change')); await settle()
    container.querySelector<HTMLButtonElement>('.question-row')!.click(); await settle()
    expect(container.textContent).toContain('已删除')
    await click('恢复题目')
    expect(coreApi.restoreQuestion).toHaveBeenCalledWith('q', 1)
    expect(container.textContent).toContain('将按原复习进度参与训练')
  })

  it('clears a deleted pending question and allows the learner to fetch another', async () => {
    vi.mocked(coreApi.knowledgeDomains).mockResolvedValue([{ id: 'domain', name: 'Domain' }] as never)
    vi.mocked(coreApi.createSession).mockResolvedValue({ id: 'session', targetCount: 10, completedMainQuestions: 0 } as never)
    vi.mocked(coreApi.nextAssignment).mockResolvedValue({ status: 'assignment', assignment: {
      assignmentId: 'a', sourceKind: 'new', attemptType: 'main', question: { id: 'q', title: 'Test', stem: 'Question', type: 'single_choice', options: [{ id: 'A', text: 'Option' }] },
    } } as never)
    vi.mocked(coreApi.submitAnswer).mockRejectedValue(new CoreApiError('Deleted', 409, 'question_deleted'))
    await mount(TrainingView)
    const intro = [...container.querySelectorAll('button')].find(b => b.textContent?.includes('继续通过网页复习旧题'))!
    intro.click(); await settle()
    const start = [...container.querySelectorAll('button')].find(b => b.textContent?.includes('开始今日训练'))!
    start.click(); await settle()
    container.querySelector<HTMLButtonElement>('.option-list button')!.click(); await settle()
    await click('提交答案')
    expect(container.textContent).toContain('当前题目已移出训练')
    expect(container.querySelector('.option-list')).toBeNull()
    await click('继续训练')
    expect(coreApi.nextAssignment).toHaveBeenCalledTimes(2)
  })
})
