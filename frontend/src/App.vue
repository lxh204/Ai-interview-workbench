<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type ServiceStatus } from './api'
import InterviewView from './views/InterviewView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import ResumeView from './views/ResumeView.vue'

const sections = [
  {
    id: 'resume',
    title: '简历诊断',
    description: '按目标岗位检查项目描述、技术匹配度与可改进的表达。',
    component: ResumeView,
  },
  {
    id: 'interview',
    title: '模拟面试',
    description: '选择技术方向，逐题作答并回看反馈。',
    component: InterviewView,
  },
  {
    id: 'knowledge',
    title: '知识库问答',
    description: '整理自己的复习资料，提问时查看回答引用的原文。',
    component: KnowledgeView,
  },
] as const

type SectionId = typeof sections[number]['id']

function sectionFromLocation(): SectionId {
  const hash = window.location.hash.slice(1)
  return sections.find((section) => section.id === hash)?.id ?? 'resume'
}

const active = ref(sectionFromLocation())
const current = computed(() => sections.find((section) => section.id === active.value) ?? sections[0])
const status = ref<ServiceStatus>()
const checkPending = ref(true)
const statusLabel = computed(() => {
  if (checkPending.value) return '正在连接'
  if (!status.value) return '服务未连接'
  return status.value.mode === 'model' ? '模型已连接' : '离线演示'
})

async function checkStatus() {
  checkPending.value = true
  try {
    status.value = await api<ServiceStatus>('/status')
  } catch {
    status.value = undefined
  } finally {
    checkPending.value = false
  }
}

function handleHashChange() {
  const next = sectionFromLocation()
  if (active.value !== next) window.scrollTo(0, 0)
  active.value = next
  document.title = current.value.title + ' | 候场'
}

onMounted(() => {
  handleHashChange()
  window.addEventListener('hashchange', handleHashChange)
  void checkStatus()
})

onUnmounted(() => window.removeEventListener('hashchange', handleHashChange))
</script>

<template>
  <div class="app-shell">
    <a class="skip-to-content" href="#main-content" @click.prevent="($refs.main as HTMLElement)?.focus()">跳到主要内容</a>
    <aside class="sidebar">
      <a class="brand" href="#resume" aria-label="候场，面试工作台">
        <span class="brand-symbol" aria-hidden="true"></span>
        <span class="brand-name">候场 <small>面试工作台</small></span>
      </a>

      <nav class="navigation" aria-label="工作区">
        <a
          v-for="section in sections"
          :key="section.id"
          :href="'#' + section.id"
          class="nav-link"
          :aria-current="active === section.id ? 'page' : undefined"
        >
          <span class="glyph" :class="'glyph-' + section.id" aria-hidden="true"></span>
          <span>{{ section.title }}</span>
        </a>
      </nav>

      <button
        type="button"
        class="service-status"
        :class="{ connected: Boolean(status), model: status?.mode === 'model' }"
        :disabled="checkPending"
        @click="checkStatus"
      >
        <span class="status-dot" aria-hidden="true"></span>
        <span class="status-description">
          <strong>{{ statusLabel }}</strong>
          <small v-if="status">{{ status.retrieval === 'hybrid' ? '混合检索' : '关键词检索' }}</small>
          <small v-else-if="!checkPending">点击重新检查</small>
        </span>
      </button>
    </aside>

    <main id="main-content" ref="main" class="workspace" tabindex="-1">
      <header class="page-mast" :class="'mast-' + active">
        <div class="mast-copy">
          <h1>{{ current.title }}</h1>
          <p>{{ current.description }}</p>
        </div>
        <div class="mast-picture" aria-hidden="true">
          <i></i><i></i><i></i>
        </div>
      </header>

      <KeepAlive>
        <component :is="current.component" v-bind="active === 'knowledge' ? { status } : {}" />
      </KeepAlive>
    </main>
  </div>
</template>
