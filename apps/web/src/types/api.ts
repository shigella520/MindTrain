export interface WeakTopic {
  topic_id: string
  name: string | null
  mastery_score: number
  correct_count: number
  wrong_count: number
}

export interface Overview {
  attempts: number
  correct: number
  accuracy: number
  todayCompletedMainQuestions: number
  dailyTarget: number
  reviewBudget: number
  newBudget: number
  reportingTimeZone: string
  completedSessions: number
  dueCount: number
  oldestDueAt?: string
  newItemAllowance: number
  newItemsPaused: boolean
  schedulerProvider?: string
  schedulerProviderName?: string
  activeQuestions: number
  pendingGeneratedQuestions: number
  weakTopics: WeakTopic[]
}

export interface Backlog {
  dueCount: number
  oldestDueAt: string | null
  newItemAllowance: number
  newItemsPaused: boolean
}

export interface TrainingSettings {
  questionCount: number
  newBudget: number
  reviewBudget: number
  backlogPauseThreshold: number
  overduePauseDays: number
  pendingCandidateTtlHours: number
  reportingTimeZone: string
  updatedAt: string
  updatedBy: string | null
}

export interface Session {
  id: string
  status: string
  targetCount: number
  completedMainQuestions: number
  followUpCount: number
  schedulerProvider: string
  startedAt: string
  endedAt: string | null
}

export interface QuestionOption {
  id: string
  text: string
}

export interface Question {
  id: string
  version: number
  type: 'single_choice' | 'multiple_choice'
  title: string
  stem: string
  options: QuestionOption[]
  topicIds: string[]
  difficulty: number
  importance: number
}

export interface Assignment {
  assignmentId: string
  attemptType: string
  parentAttemptId: string | null
  sourceKind: string
  question: Question
  answerPrompt: string
}

export interface NextAssignment {
  status: 'assignment' | 'generation_required' | 'no_available_items' | 'session_complete'
  assignment: Assignment | null
  message: string | null
  generationContext: { id: string; name: string } | null
  generationProfile: {
    questionType: 'single_choice' | 'multiple_choice'
    difficulty: number
    knowledgePoint: {
      topicId: string
      name: string
      importance: number
      javaVersions: string[]
      keywords: string[]
      sourceRefs: string[]
    }
  } | null
  details: Record<string, unknown> | null
}

export interface Attempt {
  attemptId: string
  assignmentId: string
  selectedOptionIds: string[]
  correctOptionIds: string[]
  correct: boolean
  score: number
  explanation: {
    conclusion?: string
    optionAnalysis?: Array<{ optionId: string; correct: boolean; analysis: string }>
    mechanism?: string[]
    pitfalls?: string[]
    versionNotes?: string[]
  }
  sources: Array<{ title?: string; url?: string }>
  answeredAt: string
}

export interface RejectedGeneratedQuestion {
  assignmentId: string
  questionId: string
  sessionId: string
  rejected: boolean
  physicallyDeleted: boolean
  newItemAllowanceRestored: boolean
}
