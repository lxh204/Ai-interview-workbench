<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, errorMessage, json } from '../api'
import { bankLink, entityPath, knowledgeDate, knowledgeParameter, knowledgePractices, practiceLink, type Practice, type PracticeSummary } from './knowledge'

const rows = ref<PracticeSummary[]>([])
const practice = ref<Practice>()
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const filter = ref('')
const search = ref('')
const draft = ref('')
let loadingVersion = 0

const currentQuestion = computed(() => practice.value?.questions[practice.value.index])
const visible = computed(() => rows.value.filter(row =>
  row.documentTitle.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()) &&
  (!filter.value || row.completed === (filter.value === 'complete')),
))

async function load() {
  const version = ++loadingVersion
  loading.value = true
  error.value = ''
  const id = knowledgeParameter('practice')
  try {
    if (id) {
      const record = await api<Practice>(entityPath(knowledgePractices, id))
      if (version !== loadingVersion) return
      if (practice.value?.id !== record.id) draft.value = ''
      practice.value = record
    } else {
      practice.value = undefined
      const records = await api<PracticeSummary[]>(knowledgePractices)
      if (version === loadingVersion) rows.value = records
    }
  } catch (failure) {
    if (version === loadingVersion) error.value = errorMessage(failure)
  } finally {
    if (version === loadingVersion) loading.value = false
  }
}

function applyAddress() {
  if (location.hash.split('?')[0] === '#/knowledgebase-interview/records') void load()
}

onMounted(() => {
  window.addEventListener('hashchange', applyAddress)
  void load()
})
onUnmounted(() => window.removeEventListener('hashchange', applyAddress))

async function submitAnswer() {
  const snapshot = practice.value
  if (!snapshot || !draft.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  const version = loadingVersion
  try {
    const record = await api<Practice>(entityPath(knowledgePractices, snapshot.id) + '/answers',
      json({ index: snapshot.index, answer: draft.value.trim() }))
    if (version !== loadingVersion) return
    practice.value = record
    draft.value = ''
  } catch (failure) {
    if (version === loadingVersion) error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="knowledge-workspace">
    <header class="knowledge-header page-header">
      <div><h1>知识库面试记录</h1><p>{{ practice ? practice.documentTitle : '继续未完成的资料面试，回看作答、参考答案与反馈。' }}</p></div>
      <div class="knowledge-actions">
        <a v-if="practice" class="button secondary" href="#/knowledgebase-interview/records">返回练习记录</a>
        <a class="button secondary" :href="practice ? bankLink(practice.documentId) : '#/knowledgebase-interview'">打开知识库题库</a>
      </div>
    </header>
    <p v-if="loading" class="knowledge-empty" role="status">正在读取资料练习…</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="button small secondary" :disabled="busy" @click="load">重试</button></p>
    <template v-if="!loading && practice">
      <section class="knowledge-panel knowledge-form knowledge-record-detail">
        <header class="knowledge-row">
          <div><h2>{{ practice.completed ? '练习已完成' : '资料练习' }}</h2><p class="muted">{{ knowledgeDate(practice.updatedAt) }}</p></div>
          <span class="knowledge-badge" :class="{ success: practice.completed }">已作答 {{ practice.index }} / {{ practice.total }}</span>
        </header>
        <progress :value="practice.index" :max="practice.total" aria-label="练习进度" />
        <form v-if="currentQuestion" class="knowledge-form" @submit.prevent="submitAnswer">
          <small class="muted">第 {{ practice.index + 1 }} 题 · {{ currentQuestion.category }} · {{ currentQuestion.difficulty }}</small>
          <h3 class="knowledge-prewrap">{{ currentQuestion.question }}</h3>
          <label class="field">你的回答
            <textarea v-model="draft" rows="6" maxlength="5000" required :disabled="busy" placeholder="写下你的思路、应用条件或验证方法。"></textarea>
          </label>
          <div class="knowledge-actions knowledge-end">
            <button class="button primary" :disabled="!draft.trim() || busy">{{ busy ? '正在保存…' : '提交回答' }}</button>
          </div>
        </form>
      </section>
      <section v-if="practice.answers.length" class="knowledge-panel knowledge-form knowledge-record-detail" aria-label="作答回顾">
        <h2>作答回顾</h2>
        <details v-for="answer in practice.answers" :key="answer.index" class="knowledge-source" :open="answer.index === practice.index - 1">
          <summary>第 {{ answer.index + 1 }} 题　{{ practice.questions[answer.index]?.question }}</summary>
          <div class="knowledge-review">
            <section><h3>你的回答</h3><p>{{ answer.content }}</p></section>
            <section><h3>参考答案</h3><p>{{ practice.questions[answer.index]?.referenceAnswer }}</p></section>
            <section><h3>反馈</h3><p>{{ answer.feedback }}</p></section>
            <details v-if="practice.questions[answer.index]?.sourceText" class="knowledge-source">
              <summary>查看原文片段</summary><p>{{ practice.questions[answer.index]?.sourceText }}</p>
            </details>
          </div>
        </details>
      </section>
    </template>
    <section v-else-if="!loading && !knowledgeParameter('practice')" class="knowledge-panel" aria-label="资料练习记录">
      <div class="knowledge-toolbar">
        <label class="knowledge-search"><span class="sr-only">搜索资料练习记录</span><input v-model="search" type="search" placeholder="搜索资料名称…"></label>
        <select v-model="filter" aria-label="筛选练习状态">
          <option value="">所有状态</option><option value="complete">已完成</option><option value="pending">进行中</option>
        </select>
        <button class="button secondary" type="button" @click="load">刷新</button>
      </div>
      <div v-if="!visible.length" class="knowledge-empty">
        <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
        <h2>{{ rows.length ? '没有匹配的练习记录' : '还没有资料练习' }}</h2>
        <p>{{ rows.length ? '调整搜索词或练习状态。' : '在题库启用题目后，开始一次资料练习。' }}</p>
        <a v-if="!rows.length" class="button primary" href="#/knowledgebase-interview">前往题库</a>
      </div>
      <div v-else class="knowledge-table-wrap">
        <table class="knowledge-table">
          <thead><tr><th scope="col">来源资料</th><th scope="col">作答进度</th><th scope="col">状态</th><th scope="col">最近更新</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="row in visible" :key="row.id">
              <td><strong>{{ row.documentTitle }}</strong></td><td>{{ row.index }} / {{ row.total }} 道</td>
              <td><span class="knowledge-badge" :class="{ success: row.completed }">{{ row.completed ? '已完成' : '进行中' }}</span></td>
              <td class="knowledge-date">{{ knowledgeDate(row.updatedAt) }}</td>
              <td><a class="button small secondary" :href="practiceLink(row.id)">{{ row.completed ? '查看练习' : '继续练习' }}</a></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
