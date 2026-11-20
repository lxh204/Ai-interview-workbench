export type Mode = 'demo' | 'model'
export type Role = 'java' | 'ai'

export interface ServiceStatus {
  mode: Mode
  retrieval: 'lexical' | 'hybrid'
}

export interface ResumeResult {
  mode: Mode
  feedback: string
  sampleText: string
}

export interface InterviewAnswer {
  question: string
  answer: string
  feedback: string
  score: number | null
}

export interface Interview {
  id: string
  role: Role
  index: number
  total: number
  question: string | null
  completed: boolean
  answers: InterviewAnswer[]
}

export interface KnowledgeDocument {
  id: string
  title: string
  chunkCount: number
}

export interface KnowledgeSource {
  documentId: string
  title: string
  chunkIndex: number
  text: string
}

export interface KnowledgeResult {
  mode: Mode
  answer: string
  sources: KnowledgeSource[]
}

export const roles: Record<Role, string> = {
  java: 'Java 后端开发',
  ai: 'AI 应用开发',
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch('/api' + path, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const message = typeof body?.message === 'string' && body.message.trim()
      ? body.message
      : '请求失败（' + response.status + '），请稍后重试。'
    throw new ApiError(message, response.status)
  }

  return response.status === 204 ? undefined as T : response.json()
}

export function json(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '请求未完成，请确认本地服务已启动后重试。'
}
