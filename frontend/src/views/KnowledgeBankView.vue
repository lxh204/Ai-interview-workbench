<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, errorMessage, json } from '../api'
import KnowledgeDialog from './KnowledgeDialog.vue'
import {
  bankLink, bankPath, chatLink, difficulties, entityPath, knowledgeDocuments, knowledgeParameter,
  knowledgePractices, practiceLink, statuses, withMethod, type Difficulty, type Document, type Practice,
  type Question, type QuestionForm, type QuestionStatus,
} from './knowledge'

const documents = ref<Document[]>([])
const selectedDocument = ref('')
const questions = ref<Question[]>([])
const word = ref('')
const categoryFilter = ref('')
const difficultyFilter = ref('')
const statusFilter = ref<QuestionStatus | ''>('')
const picked = ref<string[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
const notice = ref('')
const windowMode = ref<'generate' | 'edit' | 'read' | 'delete' | 'start'>()
const active = ref<Question>()
const form = ref<QuestionForm>({ question: '', referenceAnswer: '', category: '', difficulty: '基础', status: 'DRAFT' })
const generateCount = ref(3)
const generateDifficulty = ref<Difficulty>('基础')
const practiceCount = ref(1)
let requestVersion = 0

const document = computed(() => documents.value.find(item => item.id === selectedDocument.value))
const directions = computed(() => [...new Set(questions.value.map(question => question.category))].sort())
const available = computed(() => questions.value.filter(question => question.status === 'ACTIVE'))
const visible = computed(() => {
  const needle = word.value.trim().toLocaleLowerCase()
  return questions.value.filter(question => (!needle || [question.question, question.referenceAnswer, question.category]
    .some(value => value.toLocaleLowerCase().includes(needle))) && (!categoryFilter.value || question.category === categoryFilter.value) &&
    (!difficultyFilter.value || question.difficulty === difficultyFilter.value) && (!statusFilter.value || question.status === statusFilter.value))
})
const drawPool = computed(() => {
  const filtered = available.value.filter(question => (!categoryFilter.value || question.category === categoryFilter.value) &&
    (!difficultyFilter.value || question.difficulty === difficultyFilter.value))
  return picked.value.length ? available.value.filter(question => picked.value.includes(question.id)) : filtered
})

async function loadDocuments() {
  loading.value = true
  error.value = ''
  try {
    documents.value = await api<Document[]>(knowledgeDocuments)
    const fromAddress = knowledgeParameter('document')
    selectedDocument.value = documents.value.find(item => item.id === fromAddress)?.id || documents.value[0]?.id || ''
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function loadQuestions(id = selectedDocument.value) {
  if (!id) return
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await api<Question[]>(bankPath(id))
    if (version === requestVersion) questions.value = result
  } catch (failure) {
    if (version === requestVersion) error.value = errorMessage(failure)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

watch(selectedDocument, id => {
  questions.value = []
  picked.value = []
  statusFilter.value = ''
  categoryFilter.value = ''
  difficultyFilter.value = ''
  word.value = ''
  notice.value = ''
  if (id) {
    window.history.replaceState(null, '', bankLink(id))
    void loadQuestions(id)
  }
})

function applyAddress() {
  if (busy.value || location.hash.split('?')[0] !== '#/knowledgebase-interview') return
  const id = knowledgeParameter('document')
  if (documents.value.some(document => document.id === id)) selectedDocument.value = id
}

onMounted(() => {
  window.addEventListener('hashchange', applyAddress)
  void loadDocuments()
})
onUnmounted(() => window.removeEventListener('hashchange', applyAddress))

function show(mode: typeof windowMode.value, item?: Question) {
  windowMode.value = mode
  active.value = item
  formError.value = ''
  if (mode === 'edit') {
    form.value = item ? {
      question: item.question, referenceAnswer: item.referenceAnswer, category: item.category,
      difficulty: item.difficulty, status: item.status,
    } : { question: '', referenceAnswer: '', category: document.value?.category || '资料复习', difficulty: '基础', status: 'DRAFT' }
  }
  if (mode === 'start') practiceCount.value = Math.min(drawPool.value.length, picked.value.length || 3, 20)
}

async function mutate(item: Question, status: QuestionStatus) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const updated = await api<Question>(entityPath(bankPath(item.documentId), item.id),
      withMethod('PUT', { ...item, status }))
    questions.value = questions.value.map(question => question.id === updated.id ? updated : question)
    if (status !== 'ACTIVE') picked.value = picked.value.filter(id => id !== item.id)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!selectedDocument.value || busy.value) return
  busy.value = true
  formError.value = ''
  notice.value = ''
  const path = bankPath(selectedDocument.value)
  try {
    switch (windowMode.value) {
      case 'generate': {
        const rows = await api<Question[]>(path + '/generate', json({ count: generateCount.value, difficulty: generateDifficulty.value }))
        questions.value = [...rows, ...questions.value]
        statusFilter.value = 'DRAFT'
        notice.value = '已生成 ' + rows.length + ' 道草稿，核对题目与参考答案后即可启用。'
        break
      }
      case 'edit': {
        if (active.value) {
          const updated = await api<Question>(entityPath(path, active.value.id), withMethod('PUT', form.value))
          questions.value = questions.value.map(item => item.id === updated.id ? updated : item)
          if (updated.status !== 'ACTIVE') picked.value = picked.value.filter(id => id !== updated.id)
        } else {
          const item = await api<Question>(path, json(form.value))
          questions.value.unshift(item)
        }
        notice.value = '题目已保存。'
        break
      }
      case 'delete': {
        if (!active.value) return
        const id = active.value.id
        await api<void>(entityPath(path, id), { method: 'DELETE' })
        questions.value = questions.value.filter(item => item.id !== id)
        picked.value = picked.value.filter(pickedId => pickedId !== id)
        notice.value = '题目已删除。'
        break
      }
      case 'start': {
        const remaining = [...drawPool.value]
        const chosen: string[] = []
        while (remaining.length && chosen.length < practiceCount.value) {
          const offset = picked.value.length ? 0 : Math.floor(Math.random() * remaining.length)
          const [item] = remaining.splice(offset, 1)
          if (item) chosen.push(item.id)
        }
        const practice = await api<Practice>(knowledgePractices, json({ documentId: selectedDocument.value, questionIds: chosen }))
        location.hash = practiceLink(practice.id)
        break
      }
    }
    show(undefined)
  } catch (failure) {
    formError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="knowledge-workspace">
    <header class="knowledge-header page-header">
      <div><h1>知识库面试</h1><p>根据资料建立自己的面试题库，启用题目后按方向或难度练习。</p></div>
      <div class="knowledge-actions">
        <a class="button secondary" href="#/knowledgebase-interview/records">练习记录</a>
        <button class="button primary" :disabled="!drawPool.length || busy" @click="show('start')">开始练习</button>
      </div>
    </header>
    <section class="knowledge-panel knowledge-bank-selector">
      <label class="field">资料范围
        <select v-model="selectedDocument" :disabled="loading || busy || !documents.length">
          <option v-if="!documents.length" value="">{{ loading ? '正在加载资料…' : '没有可用资料' }}</option>
          <option v-for="entry in documents" :key="entry.id" :value="entry.id">{{ entry.title }}</option>
        </select>
      </label>
      <div class="knowledge-actions">
        <a v-if="document" class="button secondary" :href="chatLink(document.id)">打开资料问答</a>
        <a class="button secondary" href="#/knowledgebase">管理知识库</a>
      </div>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="button small secondary" @click="selectedDocument ? loadQuestions() : loadDocuments()">重试</button></p>
    <p v-if="notice" class="knowledge-notice" role="status">{{ notice }}</p>
    <section class="knowledge-panel">
      <header class="knowledge-bank-heading">
        <div><h2>题库管理</h2><p class="muted">共 {{ questions.length }} 道，{{ available.length }} 道已启用</p></div>
        <div class="knowledge-actions">
          <button class="button secondary" :disabled="!document || busy" @click="show('edit')">手动出题</button>
          <button class="button primary" :disabled="!document || busy" @click="show('generate')">从资料生成题目</button>
        </div>
      </header>
      <div v-if="document" class="knowledge-toolbar">
        <label class="knowledge-search"><span class="sr-only">搜索题目</span><input v-model="word" type="search" placeholder="搜索题干、参考答案或方向…"></label>
        <select v-model="statusFilter" aria-label="筛选题目状态">
          <option value="">全部状态</option><option v-for="(label, key) in statuses" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="difficultyFilter" aria-label="筛选难度">
          <option value="">全部难度</option><option v-for="item in difficulties" :key="item">{{ item }}</option>
        </select>
        <select v-model="categoryFilter" aria-label="筛选面试方向">
          <option value="">全部方向</option><option v-for="item in directions" :key="item">{{ item }}</option>
        </select>
      </div>
      <p v-if="picked.length" class="knowledge-list-caption muted">已选 {{ picked.length }} 道练习题 <button class="button small secondary" @click="picked = []">清空选择</button></p>
      <p v-if="loading" class="knowledge-empty" role="status">正在加载题库…</p>
      <div v-else-if="!visible.length" class="knowledge-empty">
        <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
        <h2>{{ !document ? '先导入一份资料' : questions.length ? '没有符合筛选条件的题目' : '这份资料还没有题目' }}</h2>
        <p>{{ !document ? '资料导入后，可在这里生成或手动创建题目。' : questions.length ? '试试调整关键词、方向、难度或状态。' : '先生成草稿，核对原文后启用，也可以直接手动出题。' }}</p>
        <a v-if="!document" class="button primary" href="#/knowledgebase">管理知识库</a>
        <button v-else-if="!questions.length" class="button primary" @click="show('generate')">生成题目</button>
      </div>
      <div v-else class="knowledge-table-wrap">
        <table class="knowledge-table">
          <thead><tr><th scope="col">选题</th><th scope="col">题目</th><th scope="col">方向与难度</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in visible" :key="item.id">
              <td><input v-model="picked" type="checkbox" :aria-label="'选择 ' + item.question" :value="item.id"
                :disabled="item.status !== 'ACTIVE' || busy || (picked.length >= 20 && !picked.includes(item.id))"></td>
              <td class="knowledge-question-title"><button class="knowledge-link" @click="show('read', item)">{{ item.question }}</button>
                <small v-if="item.sourceIndex" class="muted">资料片段 {{ item.sourceIndex }}</small></td>
              <td class="knowledge-document-title"><strong>{{ item.category }}</strong><small class="muted">{{ item.difficulty }}</small></td>
              <td><span class="knowledge-badge" :class="{ success: item.status === 'ACTIVE' }">{{ statuses[item.status] }}</span></td>
              <td><div class="knowledge-actions knowledge-row-actions">
                <button class="button small secondary" :disabled="busy" @click="show('edit', item)">编辑</button>
                <button v-if="item.status !== 'ACTIVE'" class="button small secondary" :disabled="busy" @click="mutate(item, 'ACTIVE')">启用</button>
                <button v-else class="button small secondary" :disabled="busy" @click="mutate(item, 'ARCHIVED')">归档</button>
                <button class="button small danger ghost" :disabled="busy" @click="show('delete', item)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <KnowledgeDialog v-if="windowMode" :title="{ generate: '生成题目草稿', edit: active ? '编辑题目' : '手动出题', read: '题目详情', delete: '删除题目', start: '开始资料练习' }[windowMode]" :busy="busy" @close="show(undefined)">
      <form class="knowledge-form" @submit.prevent="submit">
        <fieldset class="knowledge-form" :disabled="busy">
          <template v-if="windowMode === 'generate'">
            <p class="muted">将使用《{{ document?.title }}》中的片段生成草稿。</p>
            <div class="knowledge-form-columns">
              <label class="field">题量<input v-model.number="generateCount" type="number" min="1" max="10" required></label>
              <label class="field">难度<select v-model="generateDifficulty"><option v-for="difficulty in difficulties" :key="difficulty">{{ difficulty }}</option></select></label>
            </div>
          </template>
          <template v-else-if="windowMode === 'edit'">
            <label class="field">题干<textarea v-model="form.question" maxlength="600" rows="3" required></textarea></label>
            <label class="field">参考答案<textarea v-model="form.referenceAnswer" maxlength="5000" rows="4" required></textarea></label>
            <label class="field">方向<input v-model="form.category" maxlength="100" required></label>
            <div class="knowledge-form-columns">
              <label class="field">难度<select v-model="form.difficulty"><option v-for="difficulty in difficulties" :key="difficulty">{{ difficulty }}</option></select></label>
              <label class="field">状态<select v-model="form.status"><option v-for="(label, key) in statuses" :key="key" :value="key">{{ label }}</option></select></label>
            </div>
          </template>
          <template v-else-if="windowMode === 'read' && active">
            <p class="knowledge-prewrap"><strong>{{ active.question }}</strong></p>
            <section class="knowledge-form"><h3>参考答案</h3><p class="knowledge-prewrap">{{ active.referenceAnswer }}</p></section>
            <details v-if="active.sourceText" class="knowledge-source"><summary>查看原文片段 {{ active.sourceIndex }}</summary><p>{{ active.sourceText }}</p></details>
          </template>
          <p v-else-if="windowMode === 'delete'">确定删除「{{ active?.question }}」？</p>
          <template v-else>
            <p>本次从《{{ document?.title }}》{{ picked.length ? '已勾选的题目' : '符合方向和难度的已启用题目' }}中抽题，当前可用 {{ drawPool.length }} 道。</p>
            <label class="field">本次题量<input v-model.number="practiceCount" type="number" min="1" :max="Math.min(drawPool.length, 20)" required></label>
          </template>
        </fieldset>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <div v-if="windowMode !== 'read'" class="knowledge-actions knowledge-end">
          <button class="button secondary" type="button" :disabled="busy" @click="show(undefined)">取消</button>
          <button class="button" :class="windowMode === 'delete' ? 'danger' : 'primary'" :disabled="busy">
            {{ busy ? '处理中…' : { generate: '生成草稿', edit: '保存题目', delete: '确认删除', start: '进入练习' }[windowMode] }}
          </button>
        </div>
      </form>
    </KnowledgeDialog>
  </div>
</template>
