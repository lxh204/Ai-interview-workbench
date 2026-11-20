<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, errorMessage, json, type ServiceStatus } from '../api'
import { knowledgeExample } from '../examples'
import KnowledgeDialog from './KnowledgeDialog.vue'
import { bankLink, chatLink, entityPath, fileSize, knowledgeDate, knowledgeDocuments, withMethod, type Document } from './knowledge'

const props = defineProps<{ status?: ServiceStatus }>()
type UploadEntry = { file: File; title: string; state: 'ready' | 'sending' | 'saved' | 'error'; message?: string }
const documents = ref<Document[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
const notice = ref('')
const searchInput = ref('')
const search = ref('')
const filterCategory = ref('')
const indexFilter = ref('')
const sortBy = ref('time')
const modal = ref<'import' | 'category' | 'delete'>()
const selected = ref<Document>()
const importMode = ref<'file' | 'text'>('file')
const title = ref('')
const category = ref('')
const content = ref('')
const uploads = ref<UploadEntry[]>([])
const fileInput = ref<HTMLInputElement>()
const dropActive = ref(false)

const categories = computed(() => [...new Set(documents.value.map(document => document.category).filter(Boolean))].sort())
const questionCount = computed(() => documents.value.reduce((total, document) => total + document.questionCount, 0))
const accessCount = computed(() => documents.value.reduce((total, document) => total + document.accessCount, 0))
const activeImports = computed(() => uploads.value.filter(entry => entry.state === 'sending').length)
const filtered = computed(() => Boolean(search.value || filterCategory.value || indexFilter.value || sortBy.value !== 'time'))
const visible = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase()
  return documents.value.filter(document =>
    (!needle || [document.title, document.filename, document.category]
      .some(value => value?.toLocaleLowerCase().includes(needle))) &&
    (!filterCategory.value || (filterCategory.value === '__none' ? !document.category : document.category === filterCategory.value)) &&
    (!indexFilter.value || document.vectorized === (indexFilter.value === 'vector')))
    .sort((first, second) => {
      const comparison = sortBy.value === 'size' ? (second.sizeBytes ?? -1) - (first.sizeBytes ?? -1)
        : sortBy.value === 'access' ? second.accessCount - first.accessCount
        : sortBy.value === 'question' ? second.questionCount - first.questionCount : 0
      return comparison || Date.parse(second.createdAt) - Date.parse(first.createdAt)
    })
})
const canUpload = computed(() => uploads.value.some(entry => entry.state === 'ready' || entry.state === 'error'))

async function reload() {
  loading.value = true
  error.value = ''
  try { documents.value = await api<Document[]>(knowledgeDocuments) }
  catch (failure) { error.value = errorMessage(failure) }
  finally { loading.value = false }
}

function openImport() {
  title.value = ''
  category.value = ''
  content.value = ''
  uploads.value = []
  formError.value = ''
  notice.value = ''
  importMode.value = 'file'
  modal.value = 'import'
}

onMounted(() => {
  void reload()
  if (location.hash.split('?')[0] === '#/knowledgebase/upload' || location.pathname === '/knowledgebase/upload') openImport()
})

function close() {
  modal.value = undefined
  selected.value = undefined
  if (location.hash.startsWith('#/knowledgebase/upload') || location.pathname === '/knowledgebase/upload') {
    location.hash = '/knowledgebase'
  }
}

function addFiles(files: FileList | null) {
  if (!files || busy.value) return
  formError.value = ''
  for (const file of files) {
    if (uploads.value.length >= 10) {
      formError.value = '每批最多选择 10 个文件。'
      break
    }
    if (uploads.value.some(entry => entry.file.name === file.name && entry.file.lastModified === file.lastModified)) continue
    if (!/\.(pdf|docx|txt|md)$/i.test(file.name) || file.size === 0 || file.size > 5 * 1024 * 1024) {
      formError.value = '请选择 5 MB 以内且有内容的 PDF、DOCX、TXT 或 Markdown。'
      continue
    }
    uploads.value.push({ file, title: '', state: 'ready' })
  }
  if (fileInput.value) fileInput.value.value = ''
}

function drop(event: DragEvent) {
  dropActive.value = false
  addFiles(event.dataTransfer?.files ?? null)
}

function clearFilters() {
  searchInput.value = ''
  search.value = ''
  filterCategory.value = ''
  indexFilter.value = ''
  sortBy.value = 'time'
}

async function addDocument() {
  if (busy.value) return
  busy.value = true
  formError.value = ''
  try {
    if (importMode.value === 'text') {
      const document = await api<Document>(knowledgeDocuments, json({
        title: title.value.trim(), text: content.value.trim(), category: category.value.trim(),
      }))
      documents.value.unshift(document)
      notice.value = '资料已保存，可以开始问答或创建题目。'
      close()
      return
    }
    for (const entry of uploads.value) {
      if (entry.state === 'saved') continue
      entry.state = 'sending'
      entry.message = undefined
      const body = new FormData()
      body.set('file', entry.file)
      body.set('category', category.value.trim())
      if (entry.title.trim()) body.set('title', entry.title.trim())
      try {
        const document = await api<Document>(knowledgeDocuments + '/upload', { method: 'POST', body })
        documents.value.unshift(document)
        entry.state = 'saved'
      } catch (failure) {
        entry.state = 'error'
        entry.message = errorMessage(failure)
      }
    }
    const count = uploads.value.filter(entry => entry.state === 'saved').length
    if (count) notice.value = '已导入 ' + count + ' 份资料。'
    if (count === uploads.value.length) close()
  } catch (failure) {
    formError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function prepare(action: 'category' | 'delete', document: Document) {
  selected.value = document
  category.value = document.category
  formError.value = ''
  modal.value = action
}

async function updateDocument() {
  if (!selected.value || busy.value) return
  busy.value = true
  formError.value = ''
  const id = selected.value.id
  const path = entityPath(knowledgeDocuments, id)
  try {
    if (modal.value === 'delete') {
      await api<void>(path, { method: 'DELETE' })
      documents.value = documents.value.filter(document => document.id !== id)
      notice.value = '资料已删除。'
    } else {
      const updated = await api<Document>(path, withMethod('PATCH', { category: category.value.trim() }))
      documents.value = documents.value.map(document => document.id === id ? updated : document)
      notice.value = '分类已保存。'
    }
    close()
  } catch (failure) {
    formError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function reindex(document: Document) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = '正在更新检索索引…'
  try {
    const updated = await api<Document>(entityPath(knowledgeDocuments, document.id) + '/index', { method: 'POST' })
    documents.value = documents.value.map(row => row.id === updated.id ? updated : row)
    notice.value = updated.vectorized ? '已更新向量索引。' : '关键词索引可用。连接嵌入模型后可添加向量索引。'
  } catch (failure) {
    notice.value = ''
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="knowledge-workspace">
    <header class="knowledge-header page-header">
      <div><h1>知识库管理</h1><p>导入技术资料、项目文档和复习笔记，用于问答和练习。</p></div>
      <div class="knowledge-actions">
        <a class="button secondary" href="#/knowledgebase/chat">问答助手</a>
        <button class="button primary" type="button" @click="openImport"><span aria-hidden="true">＋</span> 上传知识库</button>
      </div>
    </header>
    <div class="knowledge-stats">
      <div><small>知识库总数</small><strong>{{ documents.length }}</strong><small>{{ documents.length }} 份可用于问答</small></div>
      <div><small>总提问次数</small><strong>{{ questionCount }}</strong><small>{{ activeImports ? activeImports + ' 份正在处理' : '按参与问答的文档计' }}</small></div>
      <div><small>总访问次数</small><strong>{{ accessCount }}</strong><small>题库、资料问答入口和下载</small></div>
    </div>

    <p v-if="notice" class="knowledge-notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="button small secondary" @click="reload">重试</button></p>
    <section class="knowledge-panel" aria-label="资料列表" :aria-busy="loading">
      <form class="knowledge-toolbar" @submit.prevent="search = searchInput.trim()">
        <label class="knowledge-search"><span class="sr-only">搜索资料</span><input v-model="searchInput" type="search" placeholder="搜索文档名称或分类…" @search="search = searchInput.trim()"></label>
        <button class="button small secondary" type="submit">搜索</button>
        <select v-model="filterCategory" aria-label="筛选资料分类">
          <option value="">所有分类</option><option value="__none">未分类</option>
          <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="indexFilter" aria-label="筛选索引类型">
          <option value="">所有索引</option><option value="vector">向量索引</option><option value="keyword">关键词索引</option>
        </select>
        <select v-model="sortBy" aria-label="知识库排序">
          <option value="time">最新上传</option><option value="size">文件大小</option>
          <option value="access">访问次数</option><option value="question">提问次数</option>
        </select>
        <button class="button secondary" type="button" :disabled="loading || busy" @click="reload">刷新</button>
      </form>
      <p class="knowledge-list-caption muted">共 {{ visible.length }} 份资料
        <button v-if="filtered" type="button" class="button small secondary" @click="clearFilters">清空筛选</button>
      </p>
      <p v-if="loading" class="knowledge-empty" role="status">正在加载资料…</p>
      <div v-else-if="!visible.length" class="knowledge-empty">
        <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
        <h2>{{ documents.length ? '没有匹配的资料' : '还没有知识库文档' }}</h2>
        <p>{{ documents.length ? '换个搜索词或清除筛选条件。' : '导入资料后，可以查阅原文、创建题目并练习。' }}</p>
        <button v-if="documents.length" class="button secondary" @click="clearFilters">清除筛选</button>
        <button v-else class="button primary" @click="openImport">导入第一份资料</button>
      </div>
      <div v-else class="knowledge-table-wrap">
        <table class="knowledge-table">
          <thead><tr><th>文档</th><th>分类</th><th>大小</th><th>检索索引</th><th>提问</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="document in visible" :key="document.id">
              <td class="knowledge-document-title"><strong>{{ document.title }}</strong><small class="muted">{{ document.filename || '文本笔记' }}</small></td>
              <td><button class="button small secondary" :aria-label="'编辑分类 ' + document.title" :disabled="busy" @click="prepare('category', document)">{{ document.category || '未分类' }}</button></td>
              <td>{{ fileSize(document.sizeBytes) }}</td>
              <td><span class="knowledge-badge success">{{ document.vectorized ? '向量已就绪' : '关键词已就绪' }}</span><small class="knowledge-index-caption muted">{{ document.chunkCount }} 个片段</small></td>
              <td>{{ document.questionCount }}</td>
              <td class="knowledge-date">{{ knowledgeDate(document.createdAt) }}</td>
              <td><div class="knowledge-actions knowledge-row-actions">
                <a class="button small secondary" :href="chatLink(document.id)">问答</a>
                <a class="button small secondary" :href="bankLink(document.id)">题库</a>
                <a class="button small secondary" :href="'/api' + entityPath(knowledgeDocuments, document.id) + '/download'" download>下载</a>
                <button v-if="props.status?.retrieval === 'hybrid'" class="button small secondary" :disabled="busy" @click="reindex(document)">重建索引</button>
                <button class="button small danger ghost" :disabled="busy" :aria-label="'删除 ' + document.title" @click="prepare('delete', document)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <KnowledgeDialog v-if="modal === 'import'" title="上传知识库" :busy="busy" @close="close">
      <form class="knowledge-form" @submit.prevent="addDocument">
        <div class="knowledge-actions" role="group" aria-label="导入方式">
          <button class="button" :class="importMode === 'file' ? 'primary' : 'secondary'" type="button" :disabled="busy" @click="importMode = 'file'">上传文件</button>
          <button class="button" :class="importMode === 'text' ? 'primary' : 'secondary'" type="button" :disabled="busy" @click="importMode = 'text'">粘贴文本</button>
        </div>
        <fieldset class="knowledge-form" :disabled="busy">
          <template v-if="importMode === 'file'">
            <div class="knowledge-dropzone" :class="{ active: dropActive }" @dragover.prevent="dropActive = true" @dragleave="dropActive = false" @drop.prevent="drop">
              <strong>拖放资料或选择文件</strong><small>PDF、DOCX、UTF-8 TXT、Markdown，每批最多 10 份，每份不超过 5 MB。</small>
              <button type="button" class="button secondary" @click="fileInput?.click()">选择文件</button>
              <input ref="fileInput" class="sr-only" type="file" accept=".pdf,.docx,.txt,.md" multiple aria-label="选择资料文件" @change="addFiles(($event.target as HTMLInputElement).files)">
            </div>
            <ul v-if="uploads.length" class="knowledge-uploads">
              <li v-for="(entry, index) in uploads" :key="entry.file.name + entry.file.lastModified">
                <div class="knowledge-row"><strong>{{ entry.file.name }} <small class="muted">{{ fileSize(entry.file.size) }}</small></strong>
                  <span v-if="entry.state !== 'ready'" class="knowledge-badge" :class="{ success: entry.state === 'saved' }">{{ { ready: '', sending: '解析并建立索引…', saved: '已导入', error: '导入失败' }[entry.state] }}</span>
                  <button v-if="entry.state !== 'saved'" class="button small secondary" type="button" :disabled="busy" @click="uploads.splice(index, 1)">移除</button>
                </div>
                <label class="field">知识库名称<input v-model="entry.title" maxlength="100" placeholder="默认使用文件名" :disabled="entry.state === 'saved'"></label>
                <small v-if="entry.message" class="error">{{ entry.message }}</small>
              </li>
            </ul>
          </template>
          <template v-else>
            <label class="field">资料标题<input v-model="title" maxlength="100" required placeholder="例如 Spring 事务复习笔记"></label>
            <label class="field">资料正文<textarea v-model="content" rows="7" maxlength="60000" required placeholder="粘贴可检索的笔记正文。"></textarea></label>
            <button class="button small secondary" type="button" @click="title = knowledgeExample.title; content = knowledgeExample.text">填入示例</button>
          </template>
          <label class="field">分类 <small class="muted">选填</small><input v-model="category" maxlength="100" list="knowledge-categories" placeholder="例如 Java、数据库、项目资料"></label>
        </fieldset>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <div class="knowledge-actions knowledge-end">
          <button type="button" class="button secondary" :disabled="busy" @click="close">取消</button>
          <button class="button primary" :disabled="busy || (importMode === 'file' ? !canUpload : !title.trim() || !content.trim())">{{ busy ? '导入中…' : '导入资料' }}</button>
        </div>
      </form>
    </KnowledgeDialog>

    <KnowledgeDialog v-if="modal === 'category' || modal === 'delete'" :title="modal === 'category' ? '编辑分类' : '删除资料'" :busy="busy" @close="close">
      <form class="knowledge-form" @submit.prevent="updateDocument">
        <label v-if="modal === 'category'" class="field">分类<input v-model="category" maxlength="100" list="knowledge-categories" placeholder="留空为未分类" :disabled="busy"></label>
        <p v-else>确认删除「{{ selected?.title }}」及其检索片段和题库？</p>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <div class="knowledge-actions knowledge-end">
          <button type="button" class="button secondary" :disabled="busy" @click="close">取消</button>
          <button class="button" :class="modal === 'delete' ? 'danger' : 'primary'" :disabled="busy">{{ modal === 'delete' ? '确认删除' : '保存分类' }}</button>
        </div>
      </form>
    </KnowledgeDialog>
    <datalist id="knowledge-categories"><option v-for="item in categories" :key="item" :value="item" /></datalist>
  </div>
</template>
