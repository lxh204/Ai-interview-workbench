<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, errorMessage, json } from '../api'
import KnowledgeDialog from './KnowledgeDialog.vue'
import KnowledgeMarkdown from './KnowledgeMarkdown.vue'
import {
  entityPath, knowledgeConversations, knowledgeDate, knowledgeDocuments, knowledgeParameter, streamAnswer, withMethod,
  type Conversation, type ConversationSummary, type Document, type Turn,
} from './knowledge'

const documents = ref<Document[]>([])
const conversations = ref<ConversationSummary[]>([])
const selectedIds = ref<string[]>([])
const current = ref<Conversation>()
const draft = ref('')
const questionInFlight = ref('')
const partial = ref('')
const scopeSearch = ref('')
const historySearch = ref('')
const loading = ref(false)
const historyLoading = ref(false)
const opening = ref(false)
const sending = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const dialogError = ref('')
const dialog = ref<{ action: 'rename' | 'delete'; session: ConversationSummary }>()
const name = ref('')
const thread = ref<HTMLElement>()
const textarea = ref<HTMLTextAreaElement>()
let abort: AbortController | undefined
let request: { conversation: string; id: string } | undefined
let openVersion = 0
let disposed = false
let lastVisitedDocument = ''

const visibleDocuments = computed(() => documents.value.filter(document =>
  [document.title, document.category, document.filename].some(value =>
    value?.toLocaleLowerCase().includes(scopeSearch.value.trim().toLocaleLowerCase())),
))
const history = computed(() => conversations.value.filter(session =>
  [session.title, ...namesOf(session.documentIds)].some(value =>
    value.toLocaleLowerCase().includes(historySearch.value.trim().toLocaleLowerCase())),
))
const selectedNames = computed(() => namesOf(selectedIds.value))
const changed = computed(() => current.value && (selectedIds.value.length !== current.value.documentIds.length ||
  selectedIds.value.some(id => !current.value?.documentIds.includes(id))))
const unavailable = computed(() => selectedIds.value.filter(id => !documents.value.some(document => document.id === id)))
const canSend = computed(() => draft.value.trim() && selectedIds.value.length && !unavailable.value.length &&
  !sending.value && !opening.value && !busy.value)

function namesOf(ids: string[]) {
  return ids.flatMap(id => documents.value.find(document => id === document.id)?.title ?? [])
}

function setAddress(session?: string) {
  historyReplace(session ? '#/knowledgebase/chat?session=' + encodeURIComponent(session) : '#/knowledgebase/chat')
}

function historyReplace(hash: string) {
  window.history.replaceState(null, '', hash)
}

async function load() {
  loading.value = true
  try {
    [documents.value, conversations.value] = await Promise.all([
      api<Document[]>(knowledgeDocuments), api<ConversationSummary[]>(knowledgeConversations),
    ])
    await applyAddress()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function applyAddress() {
  if (!location.hash.startsWith('#/knowledgebase/chat') && location.pathname !== '/knowledgebase/chat') return
  const session = knowledgeParameter('session') || knowledgeParameter('sessionId')
  if (session && session !== current.value?.id) {
    await openSession(session)
    return
  }
  const documentId = knowledgeParameter('document') || knowledgeParameter('knowledgeBaseId')
  if (!session && documentId) {
    newConversation(false)
    selectedIds.value = [documentId]
    if (lastVisitedDocument !== documentId && documents.value.some(document => document.id === documentId)) {
      lastVisitedDocument = documentId
      void api<void>(entityPath(knowledgeDocuments, documentId) + '/visits', { method: 'POST' }).catch(() => undefined)
    }
  }
}

function onHashChange() {
  if (!sending.value) void applyAddress()
}

onMounted(() => {
  window.addEventListener('hashchange', onHashChange)
  void load()
})

onUnmounted(() => {
  disposed = true
  window.removeEventListener('hashchange', onHashChange)
  if (request) void cancelRemote(request)
  abort?.abort()
})

function scrollToEnd() {
  void nextTick(() => {
    if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
  })
}

function openCitation(turnIndex: number, number: number) {
  const element = document.getElementById('knowledge-source-' + turnIndex + '-' + number)
  if (element instanceof HTMLDetailsElement) {
    element.open = true
    element.querySelector('summary')?.focus()
  }
}

function newConversation(changeAddress = true) {
  if (sending.value) return
  ++openVersion
  opening.value = false
  current.value = undefined
  error.value = ''
  notice.value = ''
  draft.value = ''
  selectedIds.value = selectedIds.value.filter(id => !unavailable.value.includes(id))
  if (changeAddress) setAddress()
  void nextTick(() => textarea.value?.focus())
}

async function openSession(id: string) {
  if (sending.value) return
  const version = ++openVersion
  opening.value = true
  error.value = ''
  notice.value = ''
  try {
    const session = await api<Conversation>(entityPath(knowledgeConversations, id))
    if (openVersion !== version) return
    current.value = session
    selectedIds.value = [...session.documentIds]
    draft.value = ''
    setAddress(id)
    scrollToEnd()
  } catch (failure) {
    if (openVersion === version) error.value = errorMessage(failure)
  } finally {
    if (openVersion === version) opening.value = false
  }
}

async function refreshHistory() {
  conversations.value = await api<ConversationSummary[]>(knowledgeConversations)
}

async function reloadHistory() {
  historyLoading.value = true
  error.value = ''
  try { await refreshHistory() }
  catch (failure) { error.value = errorMessage(failure) }
  finally { historyLoading.value = false }
}

async function saveScope() {
  if (!current.value || !selectedIds.value.length || unavailable.value.length || busy.value || sending.value) return
  busy.value = true
  error.value = ''
  try {
    current.value = await api<Conversation>(entityPath(knowledgeConversations, current.value.id),
      withMethod('PATCH', { documentIds: selectedIds.value }))
    notice.value = '已保存问答资料范围。'
    await refreshHistory()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function newRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = ((bytes[6] ?? 0) & 15) | 64
  bytes[8] = ((bytes[8] ?? 0) & 63) | 128
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  error.value = ''
  notice.value = ''
  const question = draft.value.trim()
  questionInFlight.value = question
  const controller = new AbortController()
  abort = controller
  scrollToEnd()
  try {
    let session = current.value
    if (!session) {
      session = await api<Conversation>(knowledgeConversations, { ...json({ documentIds: selectedIds.value }), signal: controller.signal })
    } else if (changed.value) {
      session = await api<Conversation>(entityPath(knowledgeConversations, session.id),
        { ...withMethod('PATCH', { documentIds: selectedIds.value }), signal: controller.signal })
    }
    if (disposed || controller.signal.aborted) return
    current.value = session
    setAddress(session.id)
    request = { conversation: session.id, id: newRequestId() }
    const turn: Turn = await streamAnswer(session.id, question, request.id, controller.signal, text => {
      partial.value += text
      scrollToEnd()
    })
    current.value = { ...session, title: session.turns.length || session.title !== '新对话' ? session.title : question.slice(0, 60),
      turns: [...session.turns, turn] }
    draft.value = ''
    questionInFlight.value = ''
    partial.value = ''
    scrollToEnd()
    await refreshHistory()
  } catch (failure) {
    if (!controller.signal.aborted) error.value = errorMessage(failure)
  } finally {
    abort = undefined
    request = undefined
    sending.value = false
    questionInFlight.value = ''
    partial.value = ''
    void nextTick(() => textarea.value?.focus())
  }
}

function cancelRemote(active: { conversation: string; id: string }) {
  return api<void>(entityPath(knowledgeConversations, active.conversation) + '/requests/' + active.id,
    { method: 'DELETE', keepalive: true }).catch(() => undefined)
}

function stop() {
  if (!sending.value) return
  if (request) void cancelRemote(request)
  abort?.abort()
  notice.value = '已停止接收回答，可修改问题后重新发送。'
}

async function togglePin(session: ConversationSummary) {
  if (sending.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const updated = await api<Conversation>(entityPath(knowledgeConversations, session.id),
      withMethod('PATCH', { pinned: !session.pinned }))
    if (current.value?.id === session.id) current.value = updated
    await refreshHistory()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function confirm(action: 'rename' | 'delete', session: ConversationSummary) {
  dialog.value = { action, session }
  name.value = session.title
  dialogError.value = ''
}

async function saveAction() {
  if (!dialog.value || busy.value) return
  const { action, session } = dialog.value
  busy.value = true
  dialogError.value = ''
  try {
    const path = entityPath(knowledgeConversations, session.id)
    if (action === 'rename') {
      const updated = await api<Conversation>(path, withMethod('PATCH', { title: name.value.trim() }))
      if (current.value?.id === session.id) current.value = updated
    } else {
      await api<void>(path, { method: 'DELETE' })
      if (current.value?.id === session.id) newConversation()
    }
    await refreshHistory()
    dialog.value = undefined
  } catch (failure) {
    dialogError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function composerKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void send()
  }
}
</script>

<template>
  <div class="knowledge-workspace">
    <header class="knowledge-header page-header">
      <div><h1>问答助手</h1><p>选择每次对话用到的资料，继续追问并核对引用原文。</p></div>
      <div class="knowledge-actions">
        <a class="button secondary" href="#/knowledgebase">管理知识库</a>
        <button class="button primary" :disabled="sending" type="button" @click="newConversation()">新建对话</button>
      </div>
    </header>
    <div class="knowledge-chat-layout">
      <aside class="knowledge-rail" aria-label="资料与历史会话">
        <details open class="knowledge-panel knowledge-resource">
          <summary><strong>资料范围</strong><span class="knowledge-badge">{{ selectedIds.length }} 份</span></summary>
          <div class="knowledge-rail-panel">
          <input v-model="scopeSearch" type="search" placeholder="搜索可用资料…" aria-label="搜索问答资料">
          <p v-if="loading" class="muted">正在加载资料…</p>
          <p v-else-if="!documents.length" class="muted">当前没有资料。<a class="knowledge-link" href="#/knowledgebase">导入资料</a></p>
          <p v-else-if="!visibleDocuments.length" class="muted">没有符合条件的资料。</p>
          <fieldset v-else class="knowledge-scope" :disabled="sending || opening || busy">
            <label v-for="document in visibleDocuments" :key="document.id" class="knowledge-check">
              <input v-model="selectedIds" type="checkbox" :value="document.id" :disabled="selectedIds.length >= 20 && !selectedIds.includes(document.id)">
              <span><strong>{{ document.title }}</strong><small class="muted">{{ document.category || '未分类' }}</small></span>
            </label>
          </fieldset>
          <div v-if="unavailable.length" class="knowledge-form"><p class="error">有 {{ unavailable.length }} 份已选资料已删除。</p>
            <button class="button small secondary" :disabled="sending" @click="selectedIds = selectedIds.filter(id => !unavailable.includes(id))">移除失效资料</button>
          </div>
          <button v-if="changed" class="button small secondary" :disabled="!selectedIds.length || !!unavailable.length || sending || busy" @click="saveScope">保存资料范围</button>
          <p v-else-if="documents.length" class="knowledge-scope-hint muted">可选择多份资料一起提问</p>
          </div>
        </details>

        <details open class="knowledge-panel knowledge-resource">
          <summary><strong>历史会话</strong><span class="knowledge-badge">{{ conversations.length }}</span></summary>
          <div class="knowledge-rail-panel">
          <div class="knowledge-row">
            <input v-model="historySearch" type="search" placeholder="搜索会话…" aria-label="搜索历史会话">
            <button type="button" class="button small secondary" :disabled="historyLoading || sending" @click="reloadHistory">刷新</button>
          </div>
          <p v-if="!history.length" class="muted">{{ conversations.length ? '没有匹配的会话。' : '发送问题后，会话将在这里保存。' }}</p>
          <ul v-else class="knowledge-history">
            <li v-for="session in history" :key="session.id" :class="{ active: current?.id === session.id }">
              <button type="button" class="knowledge-history-open" :disabled="sending || busy" :aria-current="current?.id === session.id ? 'true' : undefined" @click="openSession(session.id)">
                <strong><small v-if="session.pinned" class="knowledge-badge">置顶</small> {{ session.title }}</strong>
                <small class="muted">{{ knowledgeDate(session.updatedAt) }} · {{ session.turnCount }} 轮</small>
                <small v-if="namesOf(session.documentIds).length" class="muted">{{ namesOf(session.documentIds).join('、') }}</small>
              </button>
              <div class="knowledge-actions">
                <button class="button small secondary" :disabled="sending || busy" @click="togglePin(session)">{{ session.pinned ? '撤销置顶' : '置顶' }}</button>
                <button class="button small secondary" :disabled="sending || busy" @click="confirm('rename', session)">改名</button>
                <button class="button small danger ghost" :disabled="sending || busy" @click="confirm('delete', session)">删除</button>
              </div>
            </li>
          </ul>
          </div>
        </details>
      </aside>

      <section class="knowledge-panel knowledge-conversation" :aria-busy="sending || opening">
        <header class="knowledge-conversation-head knowledge-row">
          <div><h2>{{ current?.title || '新对话' }}</h2>
            <p class="muted">{{ selectedNames.length ? '已选资料：' + selectedNames.join('、') : '还未选择资料' }}</p></div>
          <button v-if="current" type="button" class="button small secondary" :disabled="sending || opening || busy"
            aria-label="重新加载当前会话" @click="openSession(current.id)">刷新</button>
        </header>
        <p v-if="error" class="error knowledge-chat-feedback" role="alert">{{ error }}</p>
        <p v-if="notice" class="knowledge-notice knowledge-chat-feedback" role="status">{{ notice }}</p>
        <div ref="thread" class="knowledge-thread" role="log" aria-label="对话消息" :aria-busy="sending || opening">
          <p v-if="opening" class="knowledge-empty" role="status">正在读取会话…</p>
          <div v-else-if="!current?.turns.length && !questionInFlight" class="knowledge-empty">
            <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
            <h2>围绕你的资料提问</h2>
            <p>{{ selectedIds.length ? '试着询问一个具体的概念、流程或使用条件。' : '在资料范围中勾选资料，再输入问题。' }}</p>
          </div>
          <template v-for="(turn, index) in current?.turns" :key="index">
            <article class="knowledge-message question"><span class="knowledge-avatar" aria-hidden="true">我</span>
              <div class="knowledge-message-body"><h3>你 <time v-if="turn.createdAt" :datetime="turn.createdAt">{{ knowledgeDate(turn.createdAt) }}</time></h3>
                <p>{{ turn.question }}</p></div></article>
            <article class="knowledge-message answer">
              <span class="knowledge-avatar assistant" aria-hidden="true"><span class="glyph glyph-chat"></span></span>
              <div class="knowledge-message-body">
                <h3>问答助手 <time v-if="turn.createdAt" :datetime="turn.createdAt">{{ knowledgeDate(turn.createdAt) }}</time>
                  <small v-if="turn.mode === 'demo'" class="knowledge-badge">本地检索</small></h3>
                <KnowledgeMarkdown :text="turn.answer" :sources="turn.sources.length" @cite="openCitation(index, $event)" />
                <div v-if="turn.sources.length" class="knowledge-citations">
                  <h4>参考片段</h4>
                  <details v-for="(source, sourceIndex) in turn.sources" :id="'knowledge-source-' + index + '-' + (sourceIndex + 1)"
                    :key="source.documentId + '/' + source.chunkIndex" class="knowledge-source">
                    <summary>[{{ sourceIndex + 1 }}] {{ source.title }} <small class="muted">片段 {{ source.chunkIndex }}</small></summary>
                    <p>{{ source.text }}</p>
                  </details>
                </div>
              </div>
            </article>
          </template>
          <template v-if="questionInFlight">
            <article class="knowledge-message question"><span class="knowledge-avatar" aria-hidden="true">我</span>
              <div class="knowledge-message-body"><h3>你</h3><p>{{ questionInFlight }}</p></div></article>
            <article class="knowledge-message answer"><span class="knowledge-avatar assistant" aria-hidden="true"><span class="glyph glyph-chat"></span></span>
              <div class="knowledge-message-body"><h3>问答助手</h3><KnowledgeMarkdown v-if="partial" :text="partial" />
                <p v-else>正在查找相关片段…</p></div></article>
          </template>
        </div>
        <form class="knowledge-composer" @submit.prevent="send">
          <label class="sr-only" for="knowledge-chat-question">输入问题</label>
          <textarea id="knowledge-chat-question" ref="textarea" v-model="draft" rows="3" maxlength="500"
            placeholder="输入你的问题，Enter 发送，Shift + Enter 换行" :disabled="sending || opening" @keydown="composerKey"></textarea>
          <div class="knowledge-row"><small class="muted">{{ selectedIds.length ? '将基于 ' + selectedIds.length + ' 份资料回答' : '请先选择资料' }} · {{ draft.length }} / 500</small>
            <button v-if="sending" type="button" class="button danger ghost" @click="stop">停止回答</button>
            <button v-else class="button primary" :disabled="!canSend">发送</button>
          </div>
        </form>
      </section>
    </div>
    <KnowledgeDialog v-if="dialog" :title="dialog.action === 'rename' ? '修改会话名称' : '删除会话'" :busy="busy" @close="dialog = undefined">
      <form class="knowledge-form" @submit.prevent="saveAction">
        <label v-if="dialog.action === 'rename'" class="field">会话名称<input v-model="name" maxlength="100" required :disabled="busy"></label>
        <p v-else>确定删除「{{ dialog.session.title }}」及其全部消息？</p>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <div class="knowledge-actions knowledge-end">
          <button class="button secondary" type="button" :disabled="busy" @click="dialog = undefined">取消</button>
          <button class="button" :class="dialog.action === 'delete' ? 'danger' : 'primary'" :disabled="busy || !name.trim()">{{ dialog.action === 'delete' ? '确认删除' : '保存名称' }}</button>
        </div>
      </form>
    </KnowledgeDialog>
  </div>
</template>
