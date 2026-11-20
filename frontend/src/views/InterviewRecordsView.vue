<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, errorMessage } from '../api'
import InterviewAnswers from './InterviewAnswers.vue'
import { difficulties, interviewRoles, interviewTitle, formatInterviewDate, pathId, reportLink, sessionLink, useInterviewLocation, type InterviewDetail, type InterviewKind, type InterviewRole, type InterviewSummary } from './interviewFlow'

const location = useInterviewLocation()
const reportId = computed(() => pathId(location.value, '#/interview/report/'))
const recordRoute = computed(() => location.value.split('?')[0].replace(/\/$/, '') === '#/interviews')
const entries = ref<InterviewSummary[]>([])
const report = ref<InterviewDetail>()
const pending = ref(false)
const error = ref('')
const exporting = ref(false)
const keyword = ref('')
const kind = ref<InterviewKind | ''>('')
const role = ref<InterviewRole | ''>('')
const state = ref('')
const page = ref(1)
const bookmarkedOnly = ref(false)
const pageSize = 10
let requestVersion = 0

const filtered = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return entries.value.filter(item => {
    const matchesSearch = [interviewTitle(item.role, item.topic), interviewRoles[item.role], item.resumeName ?? '', item.id].some(value => value.toLowerCase().includes(search))
    return matchesSearch && (!kind.value || item.kind === kind.value) && (!role.value || item.role === role.value)
      && (!state.value || state.value === stateOf(item))
  })
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visible = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch([keyword, kind, role, state], () => { page.value = 1 })
watch(pageCount, maximum => { if (page.value > maximum) page.value = maximum })

function stateOf(item: InterviewSummary) {
  return item.completed ? 'completed' : item.paused ? 'paused' : 'active'
}

function stateLabel(item: InterviewSummary) {
  if (item.completed) return item.index === item.total ? '已完成' : '提前交卷'
  return item.paused ? '已暂停' : '进行中'
}

async function load() {
  const id = reportId.value
  const version = ++requestVersion
  if (!id && !recordRoute.value) return
  pending.value = true
  error.value = ''
  try {
    if (id) {
      const updated = await api<InterviewDetail>('/interviews/' + encodeURIComponent(id))
      if (version === requestVersion) report.value = updated
    } else {
      const updated = await api<InterviewSummary[]>('/interviews')
      if (version === requestVersion) entries.value = updated
    }
  } catch (failure) {
    if (version === requestVersion) error.value = errorMessage(failure)
  } finally {
    if (version === requestVersion) pending.value = false
  }
}

async function exportPdf() {
  const id = report.value?.id
  if (!id || exporting.value) return
  exporting.value = true
  error.value = ''
  try {
    const response = await fetch('/api/interviews/' + encodeURIComponent(id) + '/report.pdf')
    if (!response.ok) {
      const details = await response.json().catch(() => ({}))
      throw new Error(typeof details.message === 'string' ? details.message : '报告导出失败，请稍后重试。')
    }
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = 'interview-' + id + '.pdf'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '报告导出失败，请稍后重试。'
  } finally {
    exporting.value = false
  }
}

watch([reportId, recordRoute], () => {
  report.value = undefined
  bookmarkedOnly.value = false
  void load()
}, { immediate: true })
</script>

<template>
  <div class="records" :aria-busy="pending">
    <header class="page-header">
      <h1>{{ reportId ? '面试报告' : '面试记录' }}</h1>
      <div class="records-actions">
        <a v-if="reportId" class="button secondary" href="#/interviews">返回面试记录</a>
        <a v-else class="button primary" href="#/interview-hub">开始练习</a>
        <button class="button secondary" type="button" :disabled="pending" @click="load">刷新</button>
        <button v-if="report?.completed" class="button primary" type="button" :disabled="exporting" @click="exportPdf">
          {{ exporting ? '正在导出…' : '导出 PDF' }}
        </button>
      </div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="reportId">
      <p v-if="pending && !report" class="panel" role="status">正在读取面试报告…</p>
      <template v-else-if="report">
        <section class="panel report-overview">
          <header class="panel-heading">
            <h2>{{ interviewTitle(report.role, report.topic) }}</h2>
            <span class="chip">{{ stateLabel(report) }}</span>
          </header>
          <dl class="report-facts">
            <div><dt>方向</dt><dd>{{ interviewRoles[report.role] }}</dd></div>
            <div><dt>面试方式</dt><dd>{{ report.kind === 'voice' ? '语音' : '文字' }}</dd></div>
            <div><dt>难度</dt><dd>{{ difficulties[report.difficulty] }}</dd></div>
            <div><dt>已作答</dt><dd>{{ report.index - report.skippedCount }} / {{ report.total }} 道</dd></div>
            <div><dt>跳过</dt><dd>{{ report.skippedCount }} 道</dd></div>
            <div><dt>未进行</dt><dd>{{ report.total - report.index }} 道</dd></div>
            <div v-if="report.resumeName"><dt>关联简历</dt><dd>{{ report.resumeName }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ formatInterviewDate(report.createdAt) }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ formatInterviewDate(report.completedAt) }}</dd></div>
          </dl>
          <a v-if="!report.completed" class="button primary" :href="sessionLink(report.id, report.kind)">继续面试</a>
        </section>
        <section class="panel">
          <header class="panel-heading">
            <h2>逐题回顾</h2>
            <button v-if="report.answers.some(item => item.bookmarked)" type="button" class="button secondary"
                    :aria-pressed="bookmarkedOnly" @click="bookmarkedOnly = !bookmarkedOnly">
              {{ bookmarkedOnly ? '查看全部' : '只看收藏' }}
            </button>
          </header>
          <InterviewAnswers :answers="report.answers" :bookmarked-only="bookmarkedOnly" expand-all />
        </section>
      </template>
    </template>
    <section v-else class="panel">
      <div class="records-filters">
        <label class="field"><span>搜索记录</span><input v-model="keyword" type="search" placeholder="主题、方向或编号"></label>
        <label class="field"><span>面试类型</span><select v-model="kind"><option value="">全部类型</option><option value="text">文字面试</option><option value="voice">语音面试</option></select></label>
        <label class="field"><span>岗位方向</span><select v-model="role"><option value="">全部方向</option><option v-for="(label, id) in interviewRoles" :key="id" :value="id">{{ label }}</option></select></label>
        <label class="field"><span>状态</span><select v-model="state"><option value="">全部状态</option><option value="active">进行中</option><option value="paused">已暂停</option><option value="completed">已完成</option></select></label>
      </div>
      <p v-if="pending && !entries.length" role="status">正在读取面试记录…</p>
      <div v-else-if="!filtered.length && !error" class="empty-state">
        <strong>{{ entries.length ? '没有符合条件的记录' : '还没有面试记录' }}</strong>
        <a v-if="!entries.length" class="button secondary" href="#/interview-hub">前往模拟面试</a>
      </div>
      <template v-else-if="filtered.length">
        <div class="records-table" tabindex="0" aria-label="面试记录表格，可横向滚动">
          <table>
            <thead><tr><th scope="col">主题 / 方向</th><th scope="col">类型</th><th scope="col">开始时间</th><th scope="col">进度</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
            <tbody>
              <tr v-for="item in visible" :key="item.id">
                <td><strong>{{ interviewTitle(item.role, item.topic) }}</strong><small>{{ interviewRoles[item.role] }}</small><small v-if="item.resumeName">{{ item.resumeName }}</small></td>
                <td>{{ item.kind === 'voice' ? '语音' : '文字' }}</td>
                <td>{{ formatInterviewDate(item.createdAt) }}</td>
                <td>{{ item.index }} / {{ item.total }} 题</td>
                <td><span class="chip">{{ stateLabel(item) }}</span></td>
                <td><a class="button secondary" :href="item.completed ? reportLink(item.id) : sessionLink(item.id, item.kind)">
                  {{ item.completed ? '查看报告' : '继续面试' }}
                </a></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <small>共 {{ filtered.length }} 条</small>
          <div class="records-actions">
            <button type="button" class="button secondary" :disabled="page <= 1" @click="page--">上一页</button>
            <span>{{ page }} / {{ pageCount }}</span>
            <button type="button" class="button secondary" :disabled="page >= pageCount" @click="page++">下一页</button>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.records { display: grid; gap: 14px; }
.records .page-header { margin-bottom: 10px; }
.records-actions, .pagination { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.pagination { justify-content: space-between; margin-top: 15px; color: var(--muted); }
.records-filters { display: grid; grid-template-columns: minmax(180px, 2fr) repeat(3, minmax(120px, 1fr)); gap: 12px; margin-bottom: 14px; }
.records-table { overflow: auto; }
table { width: 100%; border-collapse: collapse; min-width: 595px; font-size: 14px; }
th, td { text-align: left; padding: 12px 9px; border-bottom: 1px solid var(--line); }
th { white-space: nowrap; color: var(--muted); }
td small { display: block; margin-top: 4px; color: var(--muted); }
td .button { white-space: nowrap; padding: 6px 10px; min-height: 34px; }
.report-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px; margin: 0 0 16px; }
.report-facts dt { color: var(--muted); font-size: 13px; }
.report-facts dd { margin: 4px 0 0; font-weight: 650; }
@media (max-width: 720px) { .records-filters, .report-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 440px) { .records-filters { grid-template-columns: minmax(0, 1fr); } }
</style>
