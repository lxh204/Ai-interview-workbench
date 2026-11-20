<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, errorMessage, json, roles } from '../api'
import { resumeExample } from '../examples'

type Status = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
interface Review {
  id: string
  filename: string
  role: string
  status: Status
  feedback: string | null
  error: string | null
  createdAt: string
  text: string | null
}

const hash = location.hash.slice(1).split('?')[0]
const route = hash === 'resume' ? '/history' : hash
const detailId = route.startsWith('/history/') ? route.slice('/history/'.length) : ''
const uploading = route === '/upload'
const entries = ref<Review[]>([])
const activeReview = ref<Review>()
const search = ref('')
const stateFilter = ref<Status | ''>('')
const selected = ref<File[]>([])
const method = ref<'file' | 'text'>('file')
const title = ref('')
const target = ref(roles.java)
const material = ref('')
const busy = ref(false)
const error = ref('')
const success = ref('')
const loading = ref(true)
const currentItem = ref(0)
const deleting = ref('')
const downloading = ref(false)
const statuses: Record<Status, string> = {
  PENDING: '待分析', RUNNING: '分析中', COMPLETED: '已完成', FAILED: '失败',
}
const running = (state: Status) => state === 'PENDING' || state === 'RUNNING'
const filtered = computed(() => {
  const word = search.value.trim().toLowerCase()
  return entries.value.filter(row => (!stateFilter.value || row.status === stateFilter.value)
    && (!word || (row.filename + ' ' + row.role).toLowerCase().includes(word)))
})
const queued = computed(() => entries.value.filter(row => running(row.status)).length)
const completed = computed(() => entries.value.filter(row => row.status === 'COMPLETED').length)
const canSubmit = computed(() => target.value.trim().length > 0 && (method.value === 'file'
  ? selected.value.length > 0 : Boolean(title.value.trim() && material.value.trim())))
const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function refresh(showError = true) {
  if (uploading || disposed) return
  clearTimeout(timer)
  try {
    if (detailId) {
      activeReview.value = await api<Review>('/resume-reviews/' + encodeURIComponent(detailId))
    } else {
      entries.value = await api<Review[]>('/resume-reviews')
    }
    if (showError) error.value = ''
  } catch (cause) {
    if (!disposed) error.value = errorMessage(cause)
  } finally {
    loading.value = false
    if (!disposed && (detailId ? activeReview.value && running(activeReview.value.status) : queued.value > 0)) {
      timer = setTimeout(() => void refresh(false), 1100)
    }
  }
}

function choose(files: FileList | null) {
  if (!files) return
  const accepted = new Map(selected.value.map(file => [file.name + ':' + file.size, file]))
  const rejected: string[] = []
  for (const file of files) {
    if (!/\.(pdf|docx|txt|md)$/i.test(file.name) || file.size <= 0 || file.size > 5 * 1024 * 1024) {
      rejected.push(file.name)
      continue
    }
    if (accepted.size < 10 || accepted.has(file.name + ':' + file.size)) {
      accepted.set(file.name + ':' + file.size, file)
    }
  }
  selected.value = [...accepted.values()]
  error.value = rejected.length ? '以下文件不符合格式或大小限制：' + rejected.join('、') : ''
}

function fileChange(event: Event) {
  const input = event.target as HTMLInputElement
  choose(input.files)
  input.value = ''
}

function sample() {
  method.value = 'text'
  title.value = '简历示例'
  material.value = resumeExample
  error.value = ''
}

async function submit() {
  if (!canSubmit.value || busy.value) return
  busy.value = true
  error.value = ''
  success.value = ''
  currentItem.value = 0
  try {
    if (method.value === 'text') {
      const row = await api<Review>('/resume-reviews', json({
        title: title.value.trim(), role: target.value.trim(), text: material.value.trim(),
      }))
      window.location.hash = '/history/' + row.id
      return
    }
    const failures: string[] = []
    const imported: Review[] = []
    for (const file of selected.value) {
      currentItem.value++
      const body = new FormData()
      body.set('file', file)
      body.set('role', target.value.trim())
      try {
        imported.push(await api<Review>('/resume-reviews', { method: 'POST', body }))
      } catch (cause) {
        failures.push(file.name + '：' + errorMessage(cause))
      }
    }
    if (imported.length === 1 && !failures.length) {
      window.location.hash = '/history/' + imported[0]!.id
      return
    }
    if (imported.length && !failures.length) {
      window.location.hash = '/history'
      return
    }
    error.value = failures.join('\n')
    if (imported.length) success.value = '已提交 ' + imported.length + ' 份，可在简历管理查看进度。'
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

function windowConfirm(row: Review) {
  if (window.confirm('确认删除「' + row.filename + '」？')) void remove(row)
}

async function remove(row: Review) {
  if (deleting.value) return
  deleting.value = row.id
  error.value = ''
  try {
    await api('/resume-reviews/' + encodeURIComponent(row.id), { method: 'DELETE' })
    await refresh()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    deleting.value = ''
  }
}

async function download(row: Review, kind: 'original' | 'export') {
  if (downloading.value) return
  downloading.value = true
  try {
    const response = await fetch('/api/resume-reviews/' + encodeURIComponent(row.id) + '/' + kind)
    if (!response.ok) {
      const problem = await response.json().catch(() => null)
      error.value = problem?.message || '下载失败，请稍后重试。'
      return
    }
    const attachment = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = attachment
    link.download = kind === 'export' ? '简历分析报告.pdf' : row.filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(attachment), 1_000)
  } catch {
    error.value = '下载失败，请稍后重试。'
  } finally {
    downloading.value = false
  }
}

async function retry(row: Review) {
  if (busy.value) return
  busy.value = true
  try {
    await api('/resume-reviews/' + encodeURIComponent(row.id) + '/retry', { method: 'POST' })
    await refresh()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}

onMounted(() => void refresh())
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>

<template>
  <section v-if="uploading" class="resume-work">
    <header class="page-header">
      <div><h1>上传简历</h1><p>上传简历后，系统按目标岗位生成分析建议。</p></div>
      <a class="button secondary" href="#/history">返回简历管理</a>
    </header>
    <form class="panel intake" @submit.prevent="submit">
      <header class="panel-heading"><h2>简历材料</h2><button type="button" class="text-button" :disabled="busy" @click="sample">填入示例</button></header>
      <label class="field"><span>目标岗位</span><input v-model="target" maxlength="100" :disabled="busy" required></label>
      <div class="segmented" role="group" aria-label="简历录入方式">
        <button type="button" :aria-pressed="method === 'file'" :disabled="busy" @click="method = 'file'">上传文件</button>
        <button type="button" :aria-pressed="method === 'text'" :disabled="busy" @click="method = 'text'">粘贴文本</button>
      </div>
      <template v-if="method === 'file'">
        <div class="upload-zone" @dragover.prevent @drop.prevent="!busy && choose($event.dataTransfer?.files ?? null)">
          <span class="glyph glyph-resume" aria-hidden="true"></span>
          <strong>选择或拖入简历文件</strong>
          <small>PDF、DOCX、TXT 或 Markdown，单份不超过 5 MB，最多 10 份</small>
          <label class="button secondary upload-button">选择文件<input type="file" multiple accept=".pdf,.docx,.txt,.md" :disabled="busy" aria-label="选择简历文件" @change="fileChange"></label>
        </div>
        <ul v-if="selected.length" class="selected-files">
          <li v-for="(file, index) in selected" :key="file.name + file.size">
            <span>{{ file.name }}</span>
            <button class="text-button" type="button" :disabled="busy" @click="selected.splice(index, 1)">移除</button>
          </li>
        </ul>
      </template>
      <div v-else class="field-list">
        <label class="field"><span>简历名称</span><input v-model="title" required maxlength="150" :disabled="busy"></label>
        <label class="field"><span>简历文本</span><textarea v-model="material" rows="9" maxlength="30000" required :disabled="busy"></textarea></label>
      </div>
      <p v-if="success" role="status">{{ success }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button type="submit" class="button primary" :disabled="!canSubmit || busy">
        {{ busy ? (method === 'file' ? '正在上传 ' + currentItem + ' / ' + selected.length : '正在提交…') : '开始分析' }}
      </button>
    </form>
  </section>

  <section v-else-if="detailId" class="resume-work">
    <header class="page-header">
      <div><h1>简历详情</h1><p v-if="activeReview">{{ activeReview.filename }} · {{ activeReview.role }}</p></div>
      <a class="button secondary" href="#/history">返回简历管理</a>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading" class="panel">正在加载简历…</p>
    <article v-else-if="activeReview" class="panel">
      <header class="panel-heading">
        <div><h2>分析报告</h2><small>{{ formatTime(activeReview.createdAt) }}</small></div>
        <span class="chip" :class="'state-' + activeReview.status.toLowerCase()">{{ statuses[activeReview.status] }}</span>
      </header>
      <div class="actions">
        <button class="button secondary" type="button" :disabled="downloading" @click="download(activeReview, 'original')">下载原文件</button>
        <a v-if="activeReview.status === 'COMPLETED'" class="button secondary"
          :href="'#/interview-hub?resumeId=' + encodeURIComponent(activeReview.id)">开始模拟面试</a>
        <button v-if="activeReview.status === 'COMPLETED'" class="button primary" type="button" :disabled="downloading" @click="download(activeReview, 'export')">导出分析报告</button>
        <button v-if="activeReview.status === 'FAILED'" type="button" :disabled="busy" class="button primary" @click="retry(activeReview)">重新分析</button>
      </div>
      <p v-if="running(activeReview.status)" class="processing" role="status">正在处理简历，分析结果将在此显示。</p>
      <p v-if="activeReview.error" class="error" role="alert">{{ activeReview.error }}</p>
      <div v-if="activeReview.feedback" class="report result-text">{{ activeReview.feedback }}</div>
      <details v-if="activeReview.text" class="disclosure"><summary>查看简历原文</summary><p class="result-text">{{ activeReview.text }}</p></details>
    </article>
  </section>

  <section v-else class="resume-work">
    <header class="page-header">
      <div><h1>简历管理</h1><p>管理简历文件，查看分析结果。</p></div>
      <a class="button primary" href="#/upload">上传简历</a>
    </header>
    <div class="resume-stats">
      <div class="panel"><span>简历总数</span><strong>{{ entries.length }}</strong></div>
      <div class="panel"><span>已完成</span><strong>{{ completed }}</strong></div>
      <div class="panel"><span>处理中</span><strong>{{ queued }}</strong></div>
    </div>
    <section class="panel resume-table">
      <header class="panel-heading"><h2>简历列表</h2></header>
      <div class="toolbar">
        <label class="sr-only" for="resume-search">搜索简历</label>
        <input id="resume-search" v-model="search" placeholder="搜索文件名或目标岗位">
        <label class="sr-only" for="resume-status">分析状态</label>
        <select id="resume-status" v-model="stateFilter">
          <option value="">全部状态</option>
          <option v-for="(label, value) in statuses" :key="value" :value="value">{{ label }}</option>
        </select>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div v-if="loading" class="empty-state" role="status">正在加载简历…</div>
      <div v-else-if="filtered.length" class="table-scroll">
        <table>
          <thead><tr><th>文件名称</th><th>目标岗位</th><th>状态</th><th>上传时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in filtered" :key="row.id">
              <td><a :href="'#/history/' + row.id">{{ row.filename }}</a></td>
              <td>{{ row.role }}</td>
              <td><span class="chip" :class="'state-' + row.status.toLowerCase()">{{ statuses[row.status] }}</span></td>
              <td>{{ formatTime(row.createdAt) }}</td>
              <td class="row-actions">
                <a :href="'#/history/' + row.id" class="text-button">查看</a>
                <button type="button" class="text-button danger" :disabled="Boolean(deleting)" @click="windowConfirm(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">
        <strong>{{ entries.length ? '没有匹配的简历' : '还没有简历' }}</strong>
        <a v-if="!entries.length" href="#/upload" class="button secondary">上传简历</a>
      </div>
    </section>
  </section>
</template>

<style scoped>
.resume-work { display:grid; gap:18px; }
.intake { max-width:760px; width:100%; display:grid; gap:16px; }
.intake .button { justify-self:start; }
.resume-stats { display:grid; grid-template-columns:repeat(3,minmax(0, 1fr)); gap:14px; }
.resume-stats .panel { display:grid; gap:9px; padding:16px 20px; }
.resume-stats strong { font-size:25px; line-height:1; }
.resume-stats span,.panel-heading small { color:var(--muted); font-size:13px; }
.resume-table { display:grid; gap:14px; }
.toolbar { display:flex; flex-wrap:wrap; gap:10px; }
.toolbar input { flex:1 1 210px; }
.table-scroll { overflow:auto; }
table { width:100%; min-width:610px; border-collapse:collapse; text-align:left; font-size:14px; }
th,td { padding:12px 10px; border-bottom:1px solid var(--line); }
th { color:var(--muted); font-weight:550; }
td:first-child { max-width:260px; overflow-wrap:anywhere; }
.row-actions { white-space:nowrap; }
.row-actions .text-button { margin-inline-end:8px; }
.danger { color:#b84040; }
.state-running,.state-pending { color:#9b6700; }
.state-completed { color:#207248; }
.state-failed { color:#b64040; }
.selected-files { padding:0; margin:0; list-style:none; }
.selected-files li { display:flex; align-items:center; justify-content:space-between; gap:10px; border-bottom:1px solid var(--line); padding:10px 0; }
.selected-files span { overflow-wrap:anywhere; }
.report { border-top:1px solid var(--line); padding-top:16px; padding-bottom:12px; }
.processing { color:var(--muted); padding:16px 0; }
.error { white-space:pre-line; }
@media(max-width:540px) {
  .resume-stats { gap:6px; }
  .resume-stats .panel { padding:12px 9px; }
  .resume-stats strong { font-size:21px; }
}
</style>
