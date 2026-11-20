<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, errorMessage } from '../api'
import { bankLink, knowledgeDate, knowledgeRecordsLink, type BankSummary } from './knowledge'

const banks = ref<BankSummary[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const category = ref('')
const sort = ref('recent')

const categories = computed(() => [...new Set(banks.value.map(bank => bank.category).filter(Boolean))].sort())
const visible = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase()
  return banks.value.filter(bank =>
    (!needle || [bank.title, bank.filename, bank.category].some(value => value?.toLocaleLowerCase().includes(needle))) &&
    (!category.value || bank.category === category.value),
  ).sort((first, second) => {
    const difference = sort.value === 'questions' ? second.active - first.active
      : sort.value === 'practice' ? second.practices - first.practices
      : sort.value === 'visited' ? Date.parse(second.lastPractice ?? '1970-01-01') - Date.parse(first.lastPractice ?? '1970-01-01')
      : 0
    return difference || Date.parse(second.createdAt) - Date.parse(first.createdAt)
  })
})

async function load() {
  loading.value = true
  error.value = ''
  try { banks.value = await api<BankSummary[]>('/knowledge/question-banks') }
  catch (failure) { error.value = errorMessage(failure) }
  finally { loading.value = false }
}

onMounted(() => void load())
</script>

<template>
  <div class="knowledge-workspace">
    <header class="knowledge-header page-header">
      <div><h1>知识库面试</h1><p>选择一份资料开始面试，或维护对应的题库。</p></div>
      <div class="knowledge-actions">
        <a class="button secondary" :href="knowledgeRecordsLink()">练习记录</a>
        <a class="button primary" href="#/knowledgebase/upload">上传知识库</a>
      </div>
    </header>
    <div class="knowledge-panel knowledge-toolbar">
      <label class="knowledge-search"><span class="sr-only">搜索知识库面试</span>
        <input v-model="keyword" type="search" placeholder="按名称、文件名或分类搜索…">
      </label>
      <select v-model="category" aria-label="筛选知识库分类">
        <option value="">全部分类</option><option v-for="item in categories" :key="item">{{ item }}</option>
      </select>
      <select v-model="sort" aria-label="题库排序">
        <option value="recent">最新上传</option><option value="questions">已启用题最多</option>
        <option value="practice">练习次数最多</option><option value="visited">最近练习</option>
      </select>
      <button class="button secondary" :disabled="loading" type="button" @click="load">刷新</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="button small secondary" @click="load">重试</button></p>
    <p v-if="loading && !banks.length" class="knowledge-panel knowledge-empty" role="status">正在读取知识库…</p>
    <div v-else-if="!visible.length && !error" class="knowledge-panel knowledge-empty">
      <span class="knowledge-empty-icon glyph glyph-knowledge" aria-hidden="true"></span>
      <h2>{{ banks.length ? '没有匹配的知识库' : '暂无可用知识库' }}</h2>
      <p>{{ banks.length ? '换个名称或分类再试。' : '上传技术资料后，可以生成题目并练习。' }}</p>
      <a v-if="!banks.length" class="button primary" href="#/knowledgebase/upload">上传第一份资料</a>
    </div>
    <section v-else class="knowledge-overview-grid" aria-label="知识库题库" :aria-busy="loading">
      <article v-for="bank in visible" :key="bank.id" class="knowledge-panel knowledge-overview-card">
        <header>
          <span class="knowledge-card-icon"><span class="glyph glyph-knowledge" aria-hidden="true"></span></span>
          <div class="knowledge-overview-title"><h2>{{ bank.title }}</h2><p class="muted">{{ bank.filename || '文本笔记' }}</p></div>
          <span class="knowledge-badge" :class="{ success: bank.active > 0 }">{{ bank.active ? '可面试' : '待启用' }}</span>
        </header>
        <dl class="knowledge-overview-stats">
          <div><dt>题目总数</dt><dd>{{ bank.total }}</dd></div>
          <div><dt>草稿</dt><dd>{{ bank.draft }}</dd></div>
          <div class="enabled"><dt>已启用</dt><dd>{{ bank.active }}</dd></div>
          <div><dt>已归档</dt><dd>{{ bank.archived }}</dd></div>
        </dl>
        <div class="knowledge-overview-practice">
          <a :href="knowledgeRecordsLink(bank.id)" class="knowledge-link">练习 {{ bank.practices }} 次，完成 {{ bank.completed }} 次</a>
          <small v-if="bank.lastPractice" class="muted">最近练习 {{ knowledgeDate(bank.lastPractice) }}</small>
        </div>
        <div class="knowledge-overview-actions">
          <a v-if="bank.active" :href="bankLink(bank.id, 'start')" class="button primary">开始面试</a>
          <button v-else class="button primary" type="button" disabled>开始面试</button>
          <a :href="bankLink(bank.id, 'generate')" class="button secondary">生成题目</a>
          <a :href="bankLink(bank.id)" class="button secondary">管理题库</a>
        </div>
      </article>
    </section>
  </div>
</template>
