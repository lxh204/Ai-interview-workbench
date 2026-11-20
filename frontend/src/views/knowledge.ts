import { ApiError, json, type KnowledgeDocument, type KnowledgeResult } from '../api'
import './knowledge.css'

export interface Document extends KnowledgeDocument {
  category: string
  filename: string | null
  vectorized: boolean
  createdAt: string
  sizeBytes: number | null
  questionCount: number
  accessCount: number
}

export interface ConversationSummary {
  id: string
  title: string
  documentIds: string[]
  pinned: boolean
  updatedAt: string
  turnCount: number
}

export interface Turn extends KnowledgeResult {
  question: string
  createdAt: string | null
}

export type Conversation = Omit<ConversationSummary, 'turnCount'> & { turns: Turn[] }

export type Difficulty = '基础' | '进阶' | '深入'
export const difficulties: Difficulty[] = ['基础', '进阶', '深入']
export type QuestionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export const statuses: Record<QuestionStatus, string> = { DRAFT: '草稿', ACTIVE: '已启用', ARCHIVED: '已归档' }

export interface FollowUp {
  question: string
  referenceAnswer: string
}

export interface QuestionForm {
  question: string
  referenceAnswer: string
  category: string
  difficulty: Difficulty
  status: QuestionStatus
  followUps: FollowUp[]
}

export interface Question extends QuestionForm {
  id: string
  documentId: string
  sourceIndex: number | null
  sourceText: string | null
  createdAt: string
  followUpIndex: number | null
}

export interface PracticeSummary {
  id: string
  documentId: string
  documentTitle: string
  index: number
  total: number
  completed: boolean
  updatedAt: string
}

export interface Practice extends PracticeSummary {
  questions: Question[]
  answers: { index: number; content: string; feedback: string }[]
}

export const knowledgeDocuments = '/knowledge/documents'
export const knowledgeConversations = '/knowledge/conversations'
export const knowledgePractices = '/knowledge/practices'

export const entityPath = (base: string, id: string) => base + '/' + encodeURIComponent(id)
export const bankPath = (id: string) => entityPath(knowledgeDocuments, id) + '/questions'
export const chatLink = (id: string) => '#/knowledgebase/chat?document=' + encodeURIComponent(id)
export const bankLink = (id: string) => '#/knowledgebase-interview?document=' + encodeURIComponent(id)
export const knowledgeRecordsLink = (documentId?: string) => '#/knowledgebase-interview/records' +
  (documentId ? '?document=' + encodeURIComponent(documentId) : '')
export const practiceLink = (id: string, documentId?: string) => '#/knowledgebase-interview/records?practice=' + encodeURIComponent(id) +
  (documentId ? '&document=' + encodeURIComponent(documentId) : '')

export function knowledgeParameter(key: string): string {
  const index = location.hash.indexOf('?')
  return new URLSearchParams(index >= 0 ? location.hash.slice(index + 1) : location.search).get(key) || ''
}

const dateFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
export const knowledgeDate = (date: string) => dateFormat.format(new Date(date))

export function fileSize(bytes: number | null): string {
  if (bytes === null) return '—'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

export function withMethod(method: 'PATCH' | 'PUT', body: unknown): RequestInit {
  return { ...json(body), method }
}

export async function streamAnswer(
  conversationId: string, question: string, requestId: string, signal: AbortSignal, onDelta: (text: string) => void,
): Promise<Turn> {
  const response = await fetch('/api' + entityPath(knowledgeConversations, conversationId) + '/stream', {
    ...json({ question, requestId }), signal,
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(body?.message || '无法开始本次问答。', response.status)
  }
  if (!response.body) throw new ApiError('问答连接没有返回数据。', 502)

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) throw new ApiError('问答连接已结束，请重新发送。', 502)
      buffer += value
      for (let delimiter = buffer.indexOf('\n\n'); delimiter >= 0; delimiter = buffer.indexOf('\n\n')) {
        const [header, data] = buffer.slice(0, delimiter).split('\n')
        buffer = buffer.slice(delimiter + 2)
        if (!data?.startsWith('data: ')) continue
        const payload = JSON.parse(data.slice(6))
        if (header === 'event: failure') throw new ApiError(payload.message, 502)
        if (header === 'event: done') return payload as Turn
        if (header === 'event: delta') onDelta(payload.text)
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
