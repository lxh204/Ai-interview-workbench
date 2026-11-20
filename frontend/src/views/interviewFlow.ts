import { onActivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError, api, errorMessage, json, type Interview } from '../api'

export type InterviewKind = 'text' | 'voice'
export type InterviewRole = 'java' | 'ai' | 'frontend' | 'python' | 'qa'
export type Difficulty = 'junior' | 'mid' | 'senior'
export type Topic = 'general' | 'spring' | 'storage' | 'rag' | 'model' | 'algorithm' | 'frontend' | 'python' | 'system' | 'testing' | 'jd'
export type InterviewConfig = { role: InterviewRole; topic: Topic; difficulty: Difficulty; questionCount: number; kind: InterviewKind; jdText?: string }
export type InterviewSummary = Omit<Interview, 'question' | 'answers' | 'role'> & {
  role: InterviewRole
  topic: Topic
  difficulty: Difficulty
  kind: InterviewKind
  paused: boolean
  createdAt: string | null
  completedAt: string | null
}
export type InterviewDetail = Pick<Interview, 'question' | 'answers'> & InterviewSummary

export const difficulties: Record<Difficulty, string> = {
  junior: '校招 · 基础题',
  mid: '中级 · 方案取舍',
  senior: '高级 · 稳定性与恢复',
}
export const topicOptions: Record<Topic, { label: string; description: string }> = {
  general: { label: '综合面试', description: '覆盖该岗位的主要技术场景。' },
  spring: { label: 'Spring 实战', description: '围绕事务、接口与配置排查。' },
  storage: { label: '数据库与缓存', description: '围绕并发更新、分页与缓存一致性。' },
  rag: { label: '知识库与 RAG', description: '围绕文档处理、检索与引用评估。' },
  model: { label: '模型应用工程', description: '围绕接口调用、输出校验与成本。' },
  algorithm: { label: '数据结构与算法', description: '围绕算法选择、边界条件和复杂度。' },
  frontend: { label: '前端工程', description: '围绕 Vue 状态、性能和可访问性。' },
  python: { label: 'Python 后端', description: '围绕异步接口、数据校验与异常处理。' },
  system: { label: '系统设计', description: '围绕容量、一致性、监控和发布。' },
  testing: { label: '测试开发', description: '围绕接口契约、并发和端到端测试。' },
  jd: { label: 'JD 定制面试', description: '粘贴岗位描述，练习技能匹配和项目举证。' },
}
export const roleTopics: Record<InterviewRole, Topic[]> = {
  java: ['general', 'spring', 'storage', 'algorithm', 'system', 'testing', 'jd'],
  ai: ['general', 'rag', 'model', 'algorithm', 'system', 'testing', 'jd'],
  frontend: ['general', 'frontend', 'algorithm', 'testing', 'system', 'jd'],
  python: ['general', 'python', 'storage', 'algorithm', 'system', 'testing', 'jd'],
  qa: ['general', 'testing', 'storage', 'system', 'algorithm', 'jd'],
}
export const interviewRoles: Record<InterviewRole, string> = {
  java: 'Java 后端开发',
  ai: 'AI 应用开发',
  frontend: '前端开发',
  python: 'Python 后端开发',
  qa: '测试开发',
}

export function sessionLink(id: string, kind: InterviewKind) {
  return kind === 'voice' ? '#/voice-interview?session=' + encodeURIComponent(id)
    : '#/interview/session/' + encodeURIComponent(id)
}

export function reportLink(id: string) {
  return '#/interview/report/' + encodeURIComponent(id)
}

export function formatInterviewDate(value: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

export function useInterviewLocation() {
  const read = () => window.location.hash || '#' + window.location.pathname + window.location.search
  const location = ref(read())
  const sync = () => { location.value = read() }
  onMounted(() => window.addEventListener('hashchange', sync))
  onActivated(sync)
  onUnmounted(() => window.removeEventListener('hashchange', sync))
  return location
}

export function pathId(hash: string, prefix: string) {
  if (!hash.startsWith(prefix)) return ''
  try {
    return decodeURIComponent(hash.slice(prefix.length).split('?')[0].replace(/\/$/, ''))
  } catch {
    return ''
  }
}

export function useInterviewSession(currentId: () => string) {
  const session = ref<InterviewDetail>()
  const answer = ref('')
  const error = ref('')
  const pending = ref(false)
  let requestVersion = 0

  async function restore(id: string, message = '') {
    const version = ++requestVersion
    pending.value = true
    error.value = ''
    try {
      const next = await api<InterviewDetail>('/interviews/' + encodeURIComponent(id))
      if (version === requestVersion) {
        if (session.value?.id !== next.id || session.value?.index !== next.index || next.completed) answer.value = ''
        session.value = next
        error.value = message
      }
    } catch (failure) {
      if (version === requestVersion) error.value = errorMessage(failure)
    } finally {
      if (version === requestVersion) pending.value = false
    }
  }

  watch(currentId, id => {
    if (id && id === session.value?.id) return
    ++requestVersion
    session.value = undefined
    answer.value = ''
    error.value = ''
    pending.value = false
    if (id) void restore(id)
  }, { immediate: true })

  async function start(settings: InterviewConfig) {
    if (pending.value) return
    const version = ++requestVersion
    pending.value = true
    error.value = ''
    try {
      const next = await api<InterviewDetail>('/interviews', json(settings))
      if (version !== requestVersion) return
      session.value = next
      window.location.hash = sessionLink(next.id, next.kind)
    } catch (failure) {
      if (version === requestVersion) error.value = errorMessage(failure)
    } finally {
      if (version === requestVersion) pending.value = false
    }
  }

  async function command(route: 'answers' | 'state', body: Record<string, string>) {
    const active = session.value
    if (!active || active.completed || pending.value) return
    const version = ++requestVersion
    pending.value = true
    error.value = ''
    try {
      const next = await api<InterviewDetail>('/interviews/' + encodeURIComponent(active.id) + '/' + route,
        json({ index: active.index, ...body }))
      if (version !== requestVersion) return
      session.value = next
      if (route === 'answers' || next.completed) answer.value = ''
      if (next.completed) window.location.hash = reportLink(next.id)
    } catch (failure) {
      if (version !== requestVersion) return
      if (failure instanceof ApiError && failure.status === 409) {
        await restore(active.id, failure.message)
      } else {
        error.value = errorMessage(failure)
      }
    } finally {
      if (version === requestVersion) pending.value = false
    }
  }

  function submit() {
    const text = answer.value.trim()
    if (text && !session.value?.paused) return command('answers', { answer: text })
  }

  function setState(action: 'pause' | 'resume' | 'finish') {
    return command('state', { action })
  }

  return { session, answer, error, pending, restore, start, submit, setState }
}
