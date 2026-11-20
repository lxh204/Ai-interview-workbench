<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, json } from '../api'
import InterviewAnswers from './InterviewAnswers.vue'
import InterviewSetup from './InterviewSetup.vue'
import { difficulties, interviewTitle, pathId, reportLink, sessionLink, useInterviewLocation, useInterviewSession } from './interviewFlow'

const location = useInterviewLocation()
const routeId = computed(() => pathId(location.value, '#/interview/session/'))
const { session, answer, error, pending, restore, start, submit, setState, bookmark } = useInterviewSession(() => routeId.value)
const finishing = ref(false)
const skipRequested = ref(false)
const draftStatus = ref<'empty' | 'pending' | 'saving' | 'saved' | 'error'>('empty')
const draftLabels = { empty: '输入后自动暂存', pending: '等待暂存…', saving: '正在暂存…', saved: '草稿已暂存', error: '服务器暂存失败' }
const draftKey = computed(() => session.value ? 'interview-draft:' + session.value.id + ':' + session.value.index : '')
let timer: ReturnType<typeof setTimeout> | undefined
let queued: Promise<void> = Promise.resolve()
let editVersion = 0

watch(draftKey, (key, previousKey) => {
  clearTimeout(timer)
  ++editVersion
  finishing.value = false
  skipRequested.value = false
  try {
    if (previousKey && session.value) sessionStorage.removeItem(previousKey)
    if (key && !session.value?.completed) {
      const localDraft = sessionStorage.getItem(key)
      if (localDraft !== null) answer.value = localDraft
    }
  } catch {
    // The server draft remains available when browser storage is restricted.
  }
  draftStatus.value = session.value?.draft ? 'saved' : 'empty'
})

onUnmounted(() => clearTimeout(timer))

function draftChanged(event: Event) {
  const text = (event.target as HTMLTextAreaElement).value
  try { sessionStorage.setItem(draftKey.value, text) } catch { /* Server draft can still be written. */ }
  draftStatus.value = 'pending'
  ++editVersion
  clearTimeout(timer)
  timer = setTimeout(saveDraft, 450)
}

function saveDraft() {
  clearTimeout(timer)
  const current = session.value
  if (!current || current.completed || current.kind !== 'text') return
  const revision = editVersion
  const draft = answer.value
  draftStatus.value = 'saving'
  queued = queued.then(async () => {
    if (revision !== editVersion) return
    try {
      await api<void>('/interviews/' + encodeURIComponent(current.id) + '/draft', json({ index: current.index, draft }))
      if (revision === editVersion) draftStatus.value = 'saved'
    } catch {
      if (revision === editVersion) draftStatus.value = 'error'
    }
  })
  return queued
}

async function finish() {
  if (answer.value.trim()) {
    const saved = await submit()
    if (saved !== true || session.value?.completed) return
  }
  await setState('finish')
  finishing.value = false
}

async function skip() {
  if (answer.value.trim() && !skipRequested.value) {
    skipRequested.value = true
    return
  }
  await setState('skip')
}

async function leave() {
  if (draftStatus.value === 'pending' || draftStatus.value === 'saving') await saveDraft()
  window.location.hash = '#/interviews'
}
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ routeId ? '文字面试' : '模拟面试' }}</h1>
      <p v-if="!routeId">围绕项目、技术和表达完成一次面试练习。</p>
    </div>
    <div v-if="!routeId" class="page-actions">
      <a class="button secondary" href="#/interviews">面试记录</a>
      <a class="button secondary" href="#/knowledgebase-interview">知识库面试</a>
    </div>
  </header>
  <InterviewSetup v-if="!routeId" kind="text" :pending="pending" @start="start" />
  <section v-else-if="!session" class="panel field-list" :aria-busy="pending">
    <p v-if="pending" role="status">正在读取面试进度…</p>
    <div v-else class="session-actions">
      <button class="button secondary" type="button" @click="restore(routeId)">重试读取</button>
      <a class="button primary" href="#/interviews">面试记录</a>
    </div>
  </section>
  <div v-else class="panel-grid interview-grid">
    <section class="panel" aria-labelledby="interview-heading" :aria-busy="pending">
      <header class="panel-heading">
        <h2 id="interview-heading">{{ interviewTitle(session.role, session.topic) }}</h2>
        <span class="chip">{{ difficulties[session.difficulty] }}</span>
      </header>
      <p v-if="session.resumeName" class="result-context">关联简历 · {{ session.resumeName }}</p>
      <div class="progress-area">
        <label class="result-context" for="interview-progress">练习进度 <span>{{ session.index }} / {{ session.total }}</span></label>
        <progress id="interview-progress" :max="session.total" :value="session.index"></progress>
      </div>
      <div v-if="session.kind === 'voice'" class="empty-state">
        <strong>这是一场语音面试</strong>
        <a class="button primary" :href="sessionLink(session.id, session.kind)">进入语音面试</a>
      </div>
      <div v-else-if="session.completed" class="interview-complete">
        <h3>本轮面试已完成</h3>
        <a class="button primary" :href="reportLink(session.id)">查看面试报告</a>
      </div>
      <form v-else-if="session.question" class="field-list" @submit.prevent="submit">
        <div class="question-card" aria-live="polite">
          <div class="question-heading">
            <span>第 {{ session.index + 1 }} 题<span v-if="session.followUpOf != null"> · 第 {{ session.followUpOf + 1 }} 题的追问</span></span>
            <button type="button" class="text-button" :aria-pressed="session.bookmarked" :disabled="pending"
                    @click="bookmark(!session.bookmarked)">{{ session.bookmarked ? '取消收藏' : '收藏题目' }}</button>
          </div>
          <p>{{ session.question }}</p>
        </div>
        <label class="field">
          <span>你的回答</span>
          <textarea v-model="answer" :disabled="pending" rows="9" maxlength="8000" @input="draftChanged"
                    @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit"
                    placeholder="可以结合原理、项目场景、取舍和验证办法。"></textarea>
        </label>
        <div class="question-heading">
          <div class="draft" role="status">
            <span>{{ draftLabels[draftStatus] }}</span>
            <button v-if="draftStatus === 'error'" class="text-button" type="button" @click="saveDraft">重试暂存</button>
          </div>
          <button type="submit" class="button primary" :disabled="!answer.trim() || pending">
            {{ session.index + 1 === session.total ? '提交并完成' : '提交回答' }}
          </button>
        </div>
      </form>
      <div v-if="!session.completed && session.kind === 'text'" class="session-actions">
        <button type="button" class="button secondary" :disabled="pending" @click="leave">稍后继续</button>
        <button v-if="!skipRequested" type="button" class="button secondary" :disabled="pending" @click="skip">跳过本题</button>
        <button v-if="!finishing" type="button" class="text-button" :disabled="pending" @click="finishing = true">提前交卷</button>
        <div v-if="skipRequested" class="confirmation">
          <p>跳过本题会丢弃当前草稿。</p>
          <button type="button" class="text-button" :disabled="pending" @click="skip">确认跳过</button>
          <button type="button" class="text-button quiet" @click="skipRequested = false">取消</button>
        </div>
        <div v-if="finishing" class="confirmation">
          <p>{{ answer.trim() ? '系统将先提交本题回答，再结束面试。' : '报告将保留已经提交的回答。' }}</p>
          <button type="button" class="text-button" :disabled="pending" @click="finish">确认交卷</button>
          <button type="button" class="text-button quiet" @click="finishing = false">取消</button>
        </div>
      </div>
    </section>
    <section class="panel output" aria-labelledby="interview-answers">
      <header class="panel-heading"><h2 id="interview-answers">答题记录</h2><span class="chip">{{ session.index - session.skippedCount }} 道已答</span></header>
      <InterviewAnswers :answers="session.answers" />
    </section>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

<style scoped>
.interview-grid { margin-top: 0; }
.page-actions, .session-actions, .question-heading { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.question-heading { justify-content: space-between; }
.draft { display: flex; flex-wrap: wrap; color: var(--muted); gap: 8px; font-size: 13px; align-items: center; }
.session-actions { margin-top: 16px; }
.session-actions .confirmation { flex-basis: 100%; }
.interview-complete h3 { margin-block: 0 12px; }
</style>
