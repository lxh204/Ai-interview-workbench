<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  api,
  errorMessage,
  json,
  type KnowledgeDocument,
  type KnowledgeResult,
  type ServiceStatus,
} from '../api'
import { knowledgeExample } from '../examples'

defineProps<{ status?: ServiceStatus }>()

const documents = ref<KnowledgeDocument[]>([])
const loadingDocuments = ref(true)
const documentError = ref('')
const adding = ref(false)
const showAdd = ref(false)
const title = ref('')
const text = ref('')
const confirmDelete = ref<string>()
const deleting = ref(false)

const question = ref('')
const submittedQuestion = ref('')
const asking = ref(false)
const askError = ref('')
const result = ref<KnowledgeResult>()

async function loadDocuments() {
  loadingDocuments.value = true
  documentError.value = ''
  try {
    documents.value = await api<KnowledgeDocument[]>('/knowledge/documents')
  } catch (failure) {
    documentError.value = errorMessage(failure)
  } finally {
    loadingDocuments.value = false
  }
}

onMounted(() => void loadDocuments())

function useExample() {
  title.value = knowledgeExample.title
  text.value = knowledgeExample.text
  question.value = knowledgeExample.question
  showAdd.value = true
}

async function addDocument() {
  if (!title.value.trim() || !text.value.trim() || adding.value) return
  adding.value = true
  documentError.value = ''
  try {
    const document = await api<KnowledgeDocument>(
      '/knowledge/documents',
      json({ title: title.value.trim(), text: text.value.trim() }),
    )
    documents.value = [document, ...documents.value]
    title.value = ''
    text.value = ''
    showAdd.value = false
  } catch (failure) {
    documentError.value = errorMessage(failure)
  } finally {
    adding.value = false
  }
}

async function deleteDocument(id: string) {
  if (deleting.value) return
  deleting.value = true
  documentError.value = ''
  try {
    await api<void>('/knowledge/documents/' + encodeURIComponent(id), { method: 'DELETE' })
    documents.value = documents.value.filter((document) => document.id !== id)
    confirmDelete.value = undefined
  } catch (failure) {
    documentError.value = errorMessage(failure)
  } finally {
    deleting.value = false
  }
}

async function ask() {
  if (!question.value.trim() || asking.value) return
  asking.value = true
  askError.value = ''
  result.value = undefined
  submittedQuestion.value = question.value.trim()
  try {
    result.value = await api<KnowledgeResult>('/knowledge/ask', json({ question: submittedQuestion.value }))
  } catch (failure) {
    askError.value = errorMessage(failure)
  } finally {
    asking.value = false
  }
}
</script>

<template>
  <div class="panel-grid knowledge-grid">
    <section class="panel" aria-labelledby="ask-heading" :aria-busy="asking">
      <header class="panel-heading">
        <h2 id="ask-heading">向知识库提问</h2>
        <span v-if="status" class="chip">{{ status.retrieval === 'hybrid' ? '混合检索' : '关键词检索' }}</span>
      </header>

      <form class="field-list" @submit.prevent="ask">
        <label class="field">
          <span class="sr-only">问题</span>
          <textarea
            v-model="question"
            :disabled="asking"
            maxlength="500"
            rows="4"
            placeholder="例如，Spring 事务为什么在同一个对象内部调用时可能失效？"
          ></textarea>
          <small class="counter">{{ question.length }} / 500</small>
        </label>
        <button type="submit" class="button primary full-width" :disabled="!question.trim() || asking">
          {{ asking ? '正在检索资料…' : '检索并回答' }}
        </button>
        <p v-if="askError" class="error" role="alert">{{ askError }}</p>
      </form>

      <div class="knowledge-answer" aria-live="polite">
        <template v-if="result">
          <header class="panel-heading result-heading">
            <h3>本次回答</h3>
            <span class="chip">{{ result.mode === 'demo' ? '离线检索' : '模型回答' }}</span>
          </header>
          <p class="submitted-question">{{ submittedQuestion }}</p>
          <p class="result-text">{{ result.answer }}</p>

          <h3 class="sources-title">引用资料 <small>{{ result.sources.length }}</small></h3>
          <ul v-if="result.sources.length" class="source-list">
            <li v-for="source in result.sources" :key="source.documentId + '/' + source.chunkIndex">
              <details class="disclosure">
                <summary>
                  <span>{{ source.title }}</span>
                  <span class="chip">片段 {{ source.chunkIndex + 1 }}</span>
                </summary>
                <p class="result-text">{{ source.text }}</p>
              </details>
            </li>
          </ul>
          <p v-else class="muted">本次检索没有找到可引用的资料。</p>
        </template>

        <div v-else class="empty-state compact" role="status">
          <span class="empty-icon"><span class="glyph glyph-knowledge" aria-hidden="true"></span></span>
          <p>{{ asking ? '正在查找与你的问题相关的片段。' : '回答和引用片段会在这里显示。' }}</p>
        </div>
      </div>
    </section>

    <section class="panel output" aria-labelledby="documents-heading" :aria-busy="loadingDocuments">
      <header class="panel-heading">
        <h2 id="documents-heading">资料库</h2>
        <span class="chip">{{ documents.length }} 份</span>
      </header>

      <div class="document-actions">
        <button
          type="button"
          class="button secondary"
          :disabled="adding"
          :aria-expanded="showAdd"
          aria-controls="add-document"
          @click="showAdd = !showAdd"
        >{{ showAdd ? '收起表单' : '添加资料' }}</button>
        <button type="button" class="text-button" :disabled="adding || asking" @click="useExample">填入示例</button>
      </div>

      <form v-show="showAdd" id="add-document" class="field-list add-document" @submit.prevent="addDocument">
        <fieldset :disabled="adding" class="field-list">
          <label class="field">
            <span>资料标题</span>
            <input v-model="title" name="document-title" type="text" placeholder="例如，Spring 事务复习笔记">
          </label>
          <label class="field">
            <span>资料正文</span>
            <textarea
              v-model="text"
              name="document-text"
              maxlength="60000"
              rows="5"
              placeholder="粘贴自己的笔记或资料正文。"
            ></textarea>
          </label>
        </fieldset>
        <button
          type="submit"
          class="button primary full-width"
          :disabled="!title.trim() || !text.trim() || adding || loadingDocuments"
        >{{ adding ? '正在保存…' : '保存资料' }}</button>
      </form>

      <p v-if="documentError" class="error" role="alert">{{ documentError }}</p>
      <p v-if="loadingDocuments" class="muted" role="status">正在加载资料…</p>

      <ul v-else-if="documents.length" class="document-list">
        <li v-for="document in documents" :key="document.id" class="document-item">
          <div class="document-row">
            <span class="glyph glyph-resume" aria-hidden="true"></span>
            <div class="document-info">
              <strong>{{ document.title }}</strong>
              <small>{{ document.chunkCount }} 个片段</small>
            </div>
            <button
              type="button"
              class="text-button quiet"
              :aria-label="'删除 ' + document.title"
              :disabled="deleting"
              @click="confirmDelete = document.id"
            >删除</button>
          </div>
          <div v-if="confirmDelete === document.id" class="confirmation">
            <p>删除这份资料及其检索片段？</p>
            <button type="button" class="text-button" :disabled="deleting" @click="deleteDocument(document.id)">
              {{ deleting ? '正在删除…' : '确认删除' }}
            </button>
            <button type="button" class="text-button quiet" :disabled="deleting" @click="confirmDelete = undefined">
              取消
            </button>
          </div>
        </li>
      </ul>

      <div v-else-if="documentError" class="empty-state compact">
        <button type="button" class="button secondary" @click="loadDocuments">重新加载资料</button>
      </div>

      <div v-else class="empty-state compact">
        <span class="empty-icon"><span class="glyph glyph-resume" aria-hidden="true"></span></span>
        <strong>暂无资料</strong>
        <p>添加复习笔记后，就可以用它们查找答案。</p>
      </div>
    </section>
  </div>
</template>
