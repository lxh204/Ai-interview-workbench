<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch, type Component } from 'vue'
import { api, type ServiceStatus } from './api'

const modelEvent = 'model-settings-changed'
const files = import.meta.glob<{ default: Component }>('./views/*View.vue')
const views = Object.fromEntries(Object.entries(files).map(([filename, load]) => [
  filename.replace('./views/', '').replace('.vue', ''),
  defineAsyncComponent(() => load().then((module) => module.default)),
]))

const groups = [
  { label: '知识与练习', items: [
    { path: '/knowledgebase', title: '知识库管理', icon: 'database', view: 'KnowledgeView' },
    { path: '/knowledgebase/chat', title: '问答助手', icon: 'chat', view: 'KnowledgeChatView' },
    { path: '/knowledgebase-interview', title: '知识库面试', icon: 'book', view: 'KnowledgeBankView' },
    { path: '/knowledgebase-interview/records', title: '知识库面试记录', icon: 'records', view: 'KnowledgeRecordsView' },
  ] },
  { label: '面试准备', items: [
    { path: '/history', title: '简历管理', icon: 'files', view: 'ResumeView' },
    { path: '/interview-hub', title: '模拟面试', icon: 'sparkle', view: 'InterviewView' },
    { path: '/interviews', title: '面试记录', icon: 'records', view: 'InterviewRecordsView' },
    { path: '/interview-schedule', title: '面试日程', icon: 'calendar', view: 'ScheduleView' },
  ] },
  { label: '系统', items: [
    { path: '/settings', title: '设置', icon: 'settings', view: 'SettingsView' },
  ] },
] as const

const mainRoutes = groups.flatMap(({ items }) => [...items].map((item) => ({
  ...item, navPath: item.path,
})))

const detailRoutes = [
  { pattern: /^\/upload$/, title: '上传简历', navPath: '/history', view: 'ResumeView' },
  { pattern: /^\/history\/[^/]+$/, title: '简历详情', navPath: '/history', view: 'ResumeView' },
  { pattern: /^\/interview$/, title: '面试配置', navPath: '/interview-hub', view: 'InterviewView' },
  { pattern: /^\/interview\/session\/[^/]+$/, title: '文字面试', navPath: '/interview-hub', view: 'InterviewView' },
  { pattern: /^\/interview\/report\/[^/]+$/, title: '面试报告', navPath: '/interviews', view: 'InterviewRecordsView' },
  { pattern: /^\/voice-interview$/, title: '语音面试', navPath: '/interview-hub', view: 'VoiceInterviewView' },
  { pattern: /^\/knowledgebase\/upload$/, title: '上传知识库', navPath: '/knowledgebase', view: 'KnowledgeView' },
]

const shortRoutes: Record<string, string> = {
  resume: '/history',
  interview: '/interview-hub',
  knowledge: '/knowledgebase',
}

function readPath() {
  const fragment = location.hash.slice(1)
  const path = fragment || location.pathname
  return (shortRoutes[path] ?? path).split('?')[0].replace(/\/$/, '') || '/knowledgebase'
}

const path = ref(readPath())
const current = computed(() => {
  const primary = mainRoutes.find((route) => route.path === path.value)
  if (primary) return primary

  const detail = detailRoutes.find((route) => route.pattern.test(path.value))
  if (detail) return detail

  return { title: '页面不存在', view: '', navPath: '' }
})

const status = ref<ServiceStatus>()
async function refreshStatus() {
  try {
    status.value = await api<ServiceStatus>('/status')
  } catch {
    status.value = undefined
  }
}

const narrow = matchMedia('(max-width: 900px)')
const mobile = ref(narrow.matches)
const showMenu = ref(false)
const drawer = ref<HTMLElement>()
const menuTrigger = ref<HTMLButtonElement>()
const drawerClose = ref<HTMLButtonElement>()
const main = ref<HTMLElement>()
function initialTheme() {
  try {
    return localStorage.getItem('interview-workbench-theme') === 'dark'
  } catch {
    return false
  }
}
const dark = ref(initialTheme())

function syncViewport(event: MediaQueryListEvent) {
  mobile.value = event.matches
  if (!mobile.value) showMenu.value = false
}

function handleKeys(event: KeyboardEvent) {
  if (!showMenu.value) return
  if (event.key === 'Escape') showMenu.value = false
  if (event.key !== 'Tab') return

  const links = drawer.value?.querySelectorAll<HTMLElement>('a[href], button')
  if (!links?.length) return
  const first = links[0]
  const last = links[links.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function syncRoute() {
  const next = readPath()
  if (path.value !== next) window.scrollTo(0, 0)
  if (shortRoutes[location.hash.slice(1)]) history.replaceState(null, '', '#' + next)
  path.value = next
  showMenu.value = false
  document.title = current.value.title + ' | AI Interview'
}

watch(dark, (value) => {
  document.documentElement.dataset.theme = value ? 'dark' : 'light'
  try {
    localStorage.setItem('interview-workbench-theme', value ? 'dark' : 'light')
  } catch {
    // The color scheme remains active for this visit.
  }
}, { immediate: true })

watch(showMenu, async (opened) => {
  document.documentElement.classList.toggle('nav-open', opened)
  await nextTick()
  if (opened && showMenu.value) drawerClose.value?.focus()
  if (!opened && mobile.value) menuTrigger.value?.focus()
})

onMounted(() => {
  if (!location.hash) history.replaceState(null, '', '#'+path.value)
  syncRoute()
  window.addEventListener('hashchange', syncRoute)
  window.addEventListener('keydown', handleKeys)
  window.addEventListener(modelEvent, refreshStatus)
  narrow.addEventListener('change', syncViewport)
  void refreshStatus()
})

onUnmounted(() => {
  window.removeEventListener('hashchange', syncRoute)
  window.removeEventListener('keydown', handleKeys)
  window.removeEventListener(modelEvent, refreshStatus)
  narrow.removeEventListener('change', syncViewport)
  document.documentElement.classList.remove('nav-open')
})
</script>

<template>
  <div class="app-shell">
    <a class="skip-to-content" href="#workspace-content" @click.prevent="main?.focus()">跳到主要内容</a>
    <button v-if="showMenu" class="drawer-backdrop" tabindex="-1" aria-label="关闭导航" @click="showMenu = false"></button>
    <aside
      ref="drawer"
      class="sidebar"
      :class="{ 'sidebar-open': showMenu }"
      :inert="mobile && !showMenu"
      :role="showMenu ? 'dialog' : undefined"
      :aria-modal="showMenu ? true : undefined"
      aria-label="工作区导航"
    >
      <div class="sidebar-head">
        <a class="brand" href="#/knowledgebase" @click="showMenu = false">
          <span class="brand-symbol"><span class="glyph glyph-chat" aria-hidden="true"></span></span>
          <span><strong>AI Interview</strong><small>面试准备工作台</small></span>
        </a>
        <button v-if="mobile" ref="drawerClose" class="icon-button drawer-close" aria-label="收起导航" @click="showMenu = false">×</button>
      </div>

      <nav class="navigation" aria-label="主要导航">
        <section v-for="group in groups" :key="group.label" class="nav-group" :aria-label="group.label">
          <h2>{{ group.label }}</h2>
          <a
            v-for="item in group.items"
            :key="item.path"
            :href="'#' + item.path"
            class="nav-link"
            :aria-current="current.navPath === item.path ? 'page' : undefined"
            @click="showMenu = false"
          >
            <span class="glyph" :class="'glyph-' + item.icon" aria-hidden="true"></span>
            <span>{{ item.title }}</span>
          </a>
        </section>
      </nav>

      <footer class="sidebar-footer">
        <small>个人工作区</small>
        <button type="button" class="nav-link theme-button" @click="dark = !dark">
          <span class="glyph" :class="dark ? 'glyph-sun' : 'glyph-moon'" aria-hidden="true"></span>
          {{ dark ? '浅色模式' : '深色模式' }}
        </button>
      </footer>
    </aside>

    <div class="workspace" :inert="showMenu">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            v-if="mobile"
            ref="menuTrigger"
            type="button"
            class="icon-button open-menu"
            aria-label="打开导航"
            :aria-expanded="showMenu"
            @click="showMenu = true"
          ><span class="glyph glyph-menu" aria-hidden="true"></span></button>
          <span>工作台</span>
          <span class="breadcrumb-divider" aria-hidden="true">›</span>
          <strong>{{ current.title }}</strong>
        </div>
        <a href="#/settings" class="topbar-settings">
          <span class="glyph glyph-settings" aria-hidden="true"></span>
          <span>模型设置</span>
        </a>
      </header>

      <main id="workspace-content" ref="main" class="workspace-content" tabindex="-1">
        <component
          :is="views[current.view]"
          v-if="views[current.view]"
          :key="path"
          v-bind="current.view === 'KnowledgeView' ? { status } : {}"
        />
        <section v-else class="panel empty-state">
          <h1>页面不存在</h1>
          <a class="button primary" href="#/knowledgebase">返回知识库</a>
        </section>
      </main>
    </div>
  </div>
</template>
