import { describe, expect, it } from 'vitest'
import { initialTrainingDomainId } from '../src/domain/trainingDomains'
import type { KnowledgeDomain } from '../src/types/api'

function domain(id: string): KnowledgeDomain {
  return {
    id,
    name: id,
    description: '',
    originType: 'ai_dialogue',
    rootTopicCount: 1,
    topicCount: 1,
    activeQuestionCount: 0,
    createdAt: '',
    updatedAt: '',
  }
}

describe('training domain selection', () => {
  it('does not invent a domain for an empty catalog', () => {
    expect(initialTrainingDomainId([])).toBe('')
  })

  it('automatically selects the sole domain', () => {
    expect(initialTrainingDomainId([domain('sole')])).toBe('sole')
  })

  it('requires an explicit choice when multiple domains exist', () => {
    expect(initialTrainingDomainId([domain('first'), domain('second')])).toBe('')
  })
})
