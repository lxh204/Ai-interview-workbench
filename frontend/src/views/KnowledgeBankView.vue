<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, errorMessage, json } from '../api'
import KnowledgeDialog from './KnowledgeDialog.vue'
import {
  bankLink, bankPath, chatLink, difficulties, entityPath, knowledgeDocuments, knowledgeParameter, knowledgeRecordsLink,
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
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
const notice = ref('')
const windowMode = ref<'generate' | 'edit' | 'read' | 'delete' | 'start'>()
const active = ref<Question>()
const form = ref<QuestionForm>({ question: '', referenceAnswer: '', category: '', difficulty: '基础', status: 'DRAFT', followUps: [] })
const generateCount = ref(3)
const generateDifficulty = ref<Difficulty>('基础')
const generateFollowUps = ref(1)
const practiceCount = ref(1)
const practiceFollowUps = ref(0)
const practiceCategory = ref('')
const practiceDifficulty = ref('')
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
const pageCount = computed(() => Math.max(1, Math.ceil(visible.value.length / pageSize)))
const pageQuestions = computed(() => visible.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const allPageSelected = computed(() => pageQuestions.value.length > 0 &&
  pageQuestions.value.every(question => picked.value.includes(question.id)))
const drawPool = computed(() => picked.value.length
  ? available.value.filter(question => picked.value.includes(question.id)) : available.value)
const practiceDirections = computed(() => [...new Set(drawPool.value.map(question => question.category))].sort())
const practiceDifficulties = computed(() => difficulties.filter(difficulty =>
  drawPool.value.some(question => question.difficulty === difficulty)))
const practiceCandidates = computed(() => drawPool.value.filter(question =>
  (!practiceCategory.value || question.category === practiceCategory.value) &&
  (!practiceDifficulty.value || question.difficulty === practiceDifficulty.value)))
const followUpOptions = computed(() => [0, 1, 2, 3, 4, 5].map(count => ({
  count, available: practiceCandidates.value.filter(item => item.followUps.length >= count).length,
})))
const eligibleCount = computed(() => followUpOptions.value.find(option => option.count === practiceFollowUps.value)?.available ?? 0)
const canStart = computed(() => Number.isInteger(practiceCount.value) && practiceCount.value >= 1 &&
  practiceCount.value <= 20 && practiceCount.value <= eligibleCount.value)

watch([word, statusFilter, categoryFilter, difficultyFilter], () => { page.value = 1 })
watch(pageCount, maximum => { page.value = Math.min(page.value, maximum) })

async function loadDocuments() {
  loading.value = true
  error.value = ''
  try {
    documents.value = await api<Document[]>(knowledgeDocuments)
    const fromAddress = knowledgeParameter('document') || knowledgeParameter('knowledgeBaseId')
    selectAddress(fromAddress)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function loadQuestions(id = selectedDocument.value, action = '') {
  if (!id) return
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    const result = await api<Question[]>(bankPath(id))
    if (version === requestVersion) {
      questions.value = result
      openAddressAction(action)
    }
  } catch (failure) {
    if (version === requestVersion) error.value = errorMessage(failure)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

watch(selectedDocument, id => {
  questions.value = []
  picked.value = []
    page.value = 1
  statusFilter.value = ''
  categoryFilter.value = ''
  difficultyFilter.value = ''
  word.value = ''
  notice.value = ''
  if (id) {
    const source = knowledgeParameter('document') || knowledgeParameter('knowledgeBaseId')
    const action = source === id ? knowledgeParameter('action') : ''
    window.history.replaceState(null, '', bankLink(id))
    void loadQuestions(id, action)
  }
})

function applyAddress() {
  if (busy.value || location.hash.split('?')[0] !== '#/knowledgebase-interview') return
  const id = knowledgeParameter('document') || knowledgeParameter('knowledgeBaseId')
  const action = knowledgeParameter('action')
  if (id && id === selectedDocument.value && action) {
    window.history.replaceState(null, '', bankLink(id))
    if (loading.value) void loadQuestions(id, action)
    else openAddressAction(action)
    return
  }
  selectAddress(id)
}

function openAddressAction(action: string) {
  if (action === 'generate') show('generate')
  if (action === 'start') {
    if (drawPool.value.length) show('start')
    else notice.value = '请先启用一道题，再开始练习。'
  }
}

function selectAddress(id: string) {
  if (id && !documents.value.some(document => document.id === id)) {
    ++requestVersion
    loading.value = false
    selectedDocument.value = ''
    error.value = '对应资料不存在，请重新选择资料。'
  } else {
    error.value = ''
    selectedDocument.value = id || documents.value[0]?.id || ''
  }
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
      difficulty: item.difficulty, status: item.status, followUps: item.followUps.map(followUp => ({ ...followUp })),
    } : { question: '', referenceAnswer: '', category: document.value?.category || '资料复习', difficulty: '基础', status: 'DRAFT', followUps: [] }
  }
  if (mode === 'start') {
    practiceCategory.value = practiceDirections.value.includes(categoryFilter.value) ? categoryFilter.value : ''
    practiceDifficulty.value = practiceDifficulties.value.some(difficulty => difficulty === difficultyFilter.value) ? difficultyFilter.value : ''
    practiceFollowUps.value = 0
    practiceCount.value = Math.min(3, Math.max(1, practiceCandidates.value.length))
  }
}

function togglePage() {
  const onPage = pageQuestions.value.map(question => question.id)
  picked.value = allPageSelected.value
    ? picked.value.filter(id => !onPage.includes(id))
    : [...new Set([...picked.value, ...onPage])].slice(0, 100)
}

async function changeStatus(ids: string[], status: 'ACTIVE' | 'ARCHIVED') {
  if (busy.value || !ids.length) return
  busy.value = true
  error.value = ''
  try {
    const updated = await api<Question[]>(bankPath(selectedDocument.value) + '/batch-status', json({ ids, status }))
    const byId = new Map(updated.map(question => [question.id, question]))
    questions.value = questions.value.map(question => byId.get(question.id) ?? question)
    picked.value = picked.value.filter(id => !byId.has(id))
    notice.value = '已' + (status === 'ACTIVE' ? '启用 ' : '归档 ') + updated.length + ' 道题。'
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
        const rows = await api<Question[]>(path + '/generate', json({
          count: generateCount.value, difficulty: generateDifficulty.value, followUpCount: generateFollowUps.value,
        }))
        questions.value = [...rows, ...questions.value]
        word.value = ''
        categoryFilter.value = ''
        difficultyFilter.value = ''
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
        if (!canStart.value) return
        const practice = await api<Practice>(knowledgePractices, json({
          documentId: selectedDocument.value,
          questionIds: picked.value.length ? drawPool.value.map(question => question.id) : undefined,
          mainCount: practiceCount.value, followUpCount: practiceFollowUps.value,
          category: practiceCategory.value, difficulty: practiceDifficulty.value,
        }))
        location.hash = practiceLink(practice.id, selectedDocument.value)
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
        <a class="button secondary" href="#/knowledgebase-interview">面试总览</a>
        <a class="button secondary" :href="knowledgeRecordsLink(selectedDocument)">练习记录</a>
        <button class="button primary" :disabled="!drawPool.length || busy" @click="show('start')">开始练习</button>
      </div>
    </header>
    <section class="knowledge-panel knowledge-bank-selector">
      <label class="field">资料范围
        <select v-model="selectedDocument" :disabled="loading || busy || !documents.length">
          <option v-if="!selectedDocument" value="" disabled>{{ loading ? '正在加载资料…' : documents.length ? '请选择资料' : '没有可用资料' }}</option>
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
      <div v-if="document" class="knowledge-status-filters" role="group" aria-label="题目状态筛选">
        <button class="button small secondary" :aria-pressed="!statusFilter" @click="statusFilter = ''">全部 {{ questions.length }}</button>
        <button v-for="(label, key) in statuses" :key="key" class="button small secondary" :aria-pressed="statusFilter === key"
          @click="statusFilter = key">{{ label }} {{ questions.filter(item => item.status === key).length }}</button>
      </div>
      <div v-if="document" class="knowledge-toolbar">
        <label class="knowledge-search"><span class="sr-only">搜索题目</span><input v-model="word" type="search" placeholder="搜索题干、参考答案或方向…"></label>
        <select v-model="difficultyFilter" aria-label="筛选难度">
          <option value="">全部难度</option><option v-for="item in difficulties" :key="item">{{ item }}</option>
        </select>
        <select v-model="categoryFilter" aria-label="筛选面试方向">
          <option value="">全部方向</option><option v-for="item in directions" :key="item">{{ item }}</option>
        </select>
      </div>
      <div v-if="picked.length" class="knowledge-bulk knowledge-row">
        <span>已选 {{ picked.length }} 道</span>
        <div class="knowledge-actions">
          <button class="button small secondary" :disabled="busy" @click="changeStatus(picked, 'ACTIVE')">批量启用</button>
          <button class="button small secondary" :disabled="busy" @click="changeStatus(picked, 'ARCHIVED')">批量归档</button>
          <button class="button small secondary" :disabled="busy" @click="picked = []">清空选择</button>
        </div>
      </div>
      <p v-if="loading" class="knowledge-empty" role="status">正在加载题库…</p>
      <div v-else-if="!visible.length" class="knowledge-empty">
        <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
        <h2>{{ !document ? '先导入一份资料' : questions.length ? '没有符合筛选条件的题目' : '这份资料还没有题目' }}</h2>
        <p>{{ !document ? '资料导入后，可在这里生成或手动创建题目。' : questions.length ? '试试调整关键词、方向、难度或状态。' : '先生成草稿，核对原文后启用，也可以直接手动出题。' }}</p>
        <a v-if="!document" class="button primary" href="#/knowledgebase">管理知识库</a>
        <button v-else-if="!questions.length" class="button primary" @click="show('generate')">生成题目</button>
      </div>
      <template v-else><div class="knowledge-table-wrap">
        <table class="knowledge-table">
          <thead><tr><th scope="col"><input type="checkbox" :checked="allPageSelected" :disabled="busy" aria-label="选择本页题目" @change="togglePage"></th>
            <th scope="col">题目</th><th scope="col">方向与难度</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in pageQuestions" :key="item.id">
              <td><input v-model="picked" type="checkbox" :aria-label="'选择 ' + item.question" :value="item.id"
                :disabled="busy || (picked.length >= 100 && !picked.includes(item.id))"></td>
              <td class="knowledge-question-title"><button class="knowledge-link" @click="show('read', item)">{{ item.question }}</button>
                <small class="muted">{{ item.followUps.length }} 个追问<span v-if="item.sourceIndex"> · 资料片段 {{ item.sourceIndex }}</span></small></td>
              <td class="knowledge-document-title"><strong>{{ item.category }}</strong><small class="muted">{{ item.difficulty }}</small></td>
              <td><span class="knowledge-badge" :class="{ success: item.status === 'ACTIVE' }">{{ statuses[item.status] }}</span></td>
              <td><div class="knowledge-actions knowledge-row-actions">
                <button class="button small secondary" :disabled="busy" @click="show('edit', item)">编辑</button>
                <button v-if="item.status !== 'ACTIVE'" class="button small secondary" :disabled="busy" @click="changeStatus([item.id], 'ACTIVE')">启用</button>
                <button v-if="item.status !== 'ARCHIVED'" class="button small secondary" :disabled="busy" @click="changeStatus([item.id], 'ARCHIVED')">归档</button>
                <button class="button small danger ghost" :disabled="busy" @click="show('delete', item)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="knowledge-pagination knowledge-row">
        <small class="muted">筛选结果 {{ visible.length }} 道 · 每页 {{ pageSize }} 道</small>
        <div class="knowledge-actions">
          <button class="button small secondary" :disabled="page <= 1" @click="page--">上一页</button>
          <span>{{ page }} / {{ pageCount }}</span>
          <button class="button small secondary" :disabled="page >= pageCount" @click="page++">下一页</button>
        </div>
      </footer></template>
    </section>
    <KnowledgeDialog v-if="windowMode" :title="{ generate: '生成题目草稿', edit: active ? '编辑题目' : '手动出题', read: '题目详情', delete: '删除题目', start: '开始资料练习' }[windowMode]" :busy="busy" @close="show(undefined)">
      <form class="knowledge-form" @submit.prevent="submit">
        <fieldset class="knowledge-form" :disabled="busy">
          <template v-if="windowMode === 'generate'">
            <p class="muted">将使用《{{ document?.title }}》中的片段生成草稿。</p>
            <div class="knowledge-form-columns">
              <label class="field">题量<input v-model.number="generateCount" type="number" min="1" max="10" required></label>
              <label class="field">难度<select v-model="generateDifficulty"><option v-for="difficulty in difficulties" :key="difficulty">{{ difficulty }}</option></select></label>
              <label class="field">每题追问数<input v-model.number="generateFollowUps" type="number" min="0" max="5" required></label>
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
            <div class="knowledge-row">
              <h3>追问（{{ form.followUps.length }} / 5）</h3>
              <button class="button small secondary" type="button" :disabled="form.followUps.length >= 5"
                @click="form.followUps.push({ question: '', referenceAnswer: '' })">添加追问</button>
            </div>
            <div v-for="(item, index) in form.followUps" :key="index" class="knowledge-follow-up knowledge-form">
              <div class="knowledge-row"><strong>追问 {{ index + 1 }}</strong>
                <button class="button small secondary" type="button" @click="form.followUps.splice(index, 1)">移除</button></div>
              <label class="field">追问题干<textarea v-model="item.question" maxlength="600" rows="2" required></textarea></label>
              <label class="field">追问参考答案<textarea v-model="item.referenceAnswer" maxlength="5000" rows="3" required></textarea></label>
            </div>
          </template>
          <template v-else-if="windowMode === 'read' && active">
            <p class="knowledge-prewrap"><strong>{{ active.question }}</strong></p>
            <section class="knowledge-form"><h3>参考答案</h3><p class="knowledge-prewrap">{{ active.referenceAnswer }}</p></section>
            <details v-for="(item, index) in active.followUps" :key="index" class="knowledge-source">
              <summary>追问 {{ index + 1 }}　{{ item.question }}</summary><p>{{ item.referenceAnswer }}</p>
            </details>
            <details v-if="active.sourceText" class="knowledge-source"><summary>查看原文片段 {{ active.sourceIndex }}</summary><p>{{ active.sourceText }}</p></details>
          </template>
          <p v-else-if="windowMode === 'delete'">确定删除「{{ active?.question }}」？</p>
          <template v-else>
            <p>从《{{ document?.title }}》{{ picked.length ? '已勾选并启用的题目' : '已启用的题目' }}中抽题。</p>
            <div class="knowledge-form-columns">
              <label class="field">面试方向<select v-model="practiceCategory"><option value="">全部方向</option>
                <option v-for="item in practiceDirections" :key="item">{{ item }}</option></select></label>
              <label class="field">难度<select v-model="practiceDifficulty"><option value="">全部难度</option>
                <option v-for="item in practiceDifficulties" :key="item">{{ item }}</option></select></label>
              <label class="field">主问题数量<input v-model.number="practiceCount" type="number" min="1" max="20" required></label>
              <label class="field">每题追问<select v-model.number="practiceFollowUps">
                <option v-for="option in followUpOptions" :key="option.count" :value="option.count">{{ option.count }} 个（{{ option.available }} 道主问题可用）</option>
              </select></label>
            </div>
            <p v-if="canStart" class="knowledge-notice" role="status">可用 {{ eligibleCount }} 道主问题，本次共 {{ practiceCount * (practiceFollowUps + 1) }} 道（含追问）。</p>
            <p v-else class="error" role="status">可用 {{ eligibleCount }} 道主问题，请调整方向、难度、主问题或追问数量。</p>
          </template>
        </fieldset>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <div v-if="windowMode !== 'read'" class="knowledge-actions knowledge-end">
          <button class="button secondary" type="button" :disabled="busy" @click="show(undefined)">取消</button>
          <button class="button" :class="windowMode === 'delete' ? 'danger' : 'primary'" :disabled="busy || (windowMode === 'start' && !canStart)">
            {{ busy ? '处理中…' : { generate: '生成草稿', edit: '保存题目', delete: '确认删除', start: '进入练习' }[windowMode] }}
          </button>
        </div>
      </form>
    </KnowledgeDialog>
  </div>
</template>
