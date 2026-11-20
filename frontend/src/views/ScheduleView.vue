<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, errorMessage, json } from '../api'

const typeLabels = { ONLINE: '视频面试', ONSITE: '现场面试', PHONE: '电话面试' } as const
const statusLabels = { PENDING: '待进行', COMPLETED: '已完成', CANCELLED: '已取消', EXPIRED: '已过期', RESCHEDULED: '已改期' } as const
const viewLabels = { day: '日', week: '周', month: '月', list: '列表' } as const
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

interface ScheduleFields {
  company: string
  position: string
  time: string
  type: keyof typeof typeLabels
  location: string
  notes: string
  meetingUrl: string
  interviewer: string
  round: number | null
  reminderMinutes: number
  status: keyof typeof statusLabels
}

interface Appointment extends ScheduleFields {
  id: string
}

const appointments = ref<Appointment[]>([])
const view = ref<keyof typeof viewLabels>('week')
const search = ref('')
const statusFilter = ref('')
const typeFilter = ref('')
const allDates = ref(true)
const loading = ref(false)
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const dialogError = ref('')
const notice = ref('')
const dialogMode = ref<'edit' | 'delete' | 'invite' | 'move'>('edit')
const invitation = ref('')
const moveTime = ref('')
const dayLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
const pending = (item: Appointment) => item.status === 'PENDING' || item.status === 'RESCHEDULED'
const useModel = ref(false)
const importHint = ref('')
const dragType = 'application/x-workbench-appointment'
const remindersShown = new Set<string>()
let reminderTimer: number | undefined
const editing = ref<Appointment>()
const dialog = ref<HTMLDialogElement>()

function twoDigits(value: number) {
  return String(value).padStart(2, '0')
}

function dateKey(date: Date) {
  return [date.getFullYear(), twoDigits(date.getMonth() + 1), twoDigits(date.getDate())].join('-')
}

function dayOffset(date: Date, offset: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + offset)
}

function firstMonday(date: Date) {
  return dayOffset(date, -((date.getDay() + 6) % 7))
}

const today = dateKey(new Date())
const selected = ref(today)
const selectedDate = computed(() => new Date(selected.value + 'T00:00:00'))
const month = computed(() => selected.value.slice(0, 7))
const emptyForm = (day: string): ScheduleFields => ({
  company: '', position: '', time: day + 'T09:00', type: 'ONLINE', location: '', notes: '', status: 'PENDING',
  meetingUrl: '', interviewer: '', round: null, reminderMinutes: 0,
})
const form = reactive(emptyForm(today))

const filtered = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return appointments.value.filter((item) => (
    (!statusFilter.value || item.status === statusFilter.value) &&
    (!typeFilter.value || item.type === typeFilter.value) &&
    (!needle || [item.company, item.position, item.location, item.notes].join(' ').toLowerCase().includes(needle))
  )).sort((left, right) => left.time.localeCompare(right.time))
})
const pendingCount = computed(() => appointments.value.filter(pending).length)
const grouped = computed(() => {
  const days = new Map<string, Appointment[]>()
  for (const item of filtered.value) {
    const key = item.time.slice(0, 10)
    const matches = days.get(key)
    if (matches) matches.push(item)
    else days.set(key, [item])
  }
  return days
})
const selection = computed(() => grouped.value.get(selected.value) ?? [])
const listItems = computed(() => allDates.value ? filtered.value : filtered.value.filter((item) => item.time.startsWith(month.value)))
const agendaGroups = computed(() => {
  if (view.value !== 'list') return [{ key: selected.value, items: selection.value }].filter((group) => group.items.length)
  const groups = new Map<string, Appointment[]>()
  for (const item of listItems.value) {
    const key = item.time.slice(0, 10)
    const items = groups.get(key) ?? []
    items.push(item)
    groups.set(key, items)
  }
  return [...groups].map(([key, items]) => ({ key, items }))
})
const calendarDays = computed(() => {
  const focus = selectedDate.value
  const isMonth = view.value === 'month'
  const start = firstMonday(isMonth ? new Date(focus.getFullYear(), focus.getMonth(), 1) : focus)
  return Array.from({ length: isMonth ? 42 : 7 }, (_, index) => {
    const date = dayOffset(start, index)
    const key = dateKey(date)
    return { key, date, items: grouped.value.get(key) ?? [], outside: isMonth && !key.startsWith(month.value) }
  })
})
const rangeLabel = computed(() => {
  if (view.value === 'week') {
    const days = calendarDays.value
    return days[0].key + ' — ' + days[6].key
  }
  if (view.value === 'day') return selected.value
  const date = selectedDate.value
  return date.getFullYear() + ' 年 ' + (date.getMonth() + 1) + ' 月'
})

function browse(step: number) {
  const current = selectedDate.value
  if (view.value === 'month' || view.value === 'list') {
    selected.value = dateKey(new Date(current.getFullYear(), current.getMonth() + step, 1))
  } else {
    selected.value = dateKey(dayOffset(current, step * (view.value === 'week' ? 7 : 1)))
  }
}

function changeDate(event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (value && !Number.isNaN(new Date(value + 'T00:00:00').getTime())) selected.value = value
}

async function refresh() {
  if (loading.value || busy.value) return
  loading.value = true
  error.value = ''
  try {
    appointments.value = await api<Appointment[]>('/schedules')
    loaded.value = true
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function startCreate(day = selected.value) {
  if (loading.value || busy.value) return
  editing.value = undefined
  dialogMode.value = 'edit'
  dialogError.value = ''
  importHint.value = ''
  Object.assign(form, emptyForm(day))
  dialog.value?.showModal()
}

function startEdit(item: Appointment) {
  if (loading.value || busy.value) return
  editing.value = item
  dialogMode.value = 'edit'
  dialogError.value = ''
  importHint.value = ''
  const { id: _id, ...fields } = item
  Object.assign(form, { ...fields, time: item.time.slice(0, 16) })
  dialog.value?.showModal()
}

function startImport() {
  if (loading.value || busy.value) return
  editing.value = undefined
  invitation.value = ''
  useModel.value = false
  dialogMode.value = 'invite'
  dialogError.value = ''
  dialog.value?.showModal()
}

async function parseInvitation() {
  if (busy.value || !invitation.value.trim()) return
  busy.value = true
  dialogError.value = ''
  try {
    const parsed = await api<Pick<ScheduleFields, 'company' | 'position' | 'location' | 'meetingUrl' | 'interviewer' | 'round'> & {
      time: string | null
      type: ScheduleFields['type'] | null
      missing: string[]
      mode: string
    }>('/schedules/parse', json({ text: invitation.value.trim(), referenceDate: dateKey(new Date()), useModel: useModel.value }))
    const { missing, mode: _mode, time, type, ...fields } = parsed
    Object.assign(form, emptyForm(selected.value), fields, { time: time?.slice(0, 16) ?? '', type: type ?? 'ONLINE' })
    importHint.value = missing.length ? '请补充' + missing.join('、') + '，并核对识别结果。' : '已识别面试信息，请核对后保存。'
    dialogMode.value = 'edit'
  } catch (failure) {
    dialogError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function startDrag(event: DragEvent, item: Appointment) {
  if (!event.dataTransfer) return
  event.dataTransfer.setData(dragType, item.id)
  event.dataTransfer.effectAllowed = 'move'
}

function allowDrop(event: DragEvent) {
  if (!event.dataTransfer?.types.includes(dragType) || busy.value || loading.value) return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'move'
}

async function moveTo(event: DragEvent, day: string) {
  const id = event.dataTransfer?.getData(dragType)
  if (!id || busy.value || loading.value) return
  event.preventDefault()
  const item = appointments.value.find((appointment) => appointment.id === id)
  if (!item || item.time.slice(0, 10) === day) return
  await reschedule(item, day + item.time.slice(10))
}

function startMove(item: Appointment) {
  if (loading.value || busy.value) return
  editing.value = item
  moveTime.value = item.time.slice(0, 16)
  dialogMode.value = 'move'
  dialogError.value = ''
  dialog.value?.showModal()
}

async function reschedule(item: Appointment, time: string) {
  if (busy.value || !time) return
  busy.value = true
  error.value = ''
  dialogError.value = ''
  try {
    const moved = await patchAppointment(item, 'time', { time })
    selected.value = moved.time.slice(0, 10)
    notice.value = '已将「' + moved.company + '」改期到 ' + moved.time.slice(0, 16).replace('T', ' ') + '。'
    dialog.value?.close()
  } catch (failure) {
    if (dialog.value?.open) dialogError.value = errorMessage(failure)
    else error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function patchAppointment(item: Appointment, field: 'time' | 'status', data: { time: string } | { status: string }) {
  const updated = await api<Appointment>('/schedules/' + encodeURIComponent(item.id) + '/' + field,
    { ...json(data), method: 'PATCH' })
  appointments.value = appointments.value.map((appointment) => appointment.id === item.id ? updated : appointment)
  return updated
}

async function changeStatus(item: Appointment, event: Event) {
  const input = event.target as HTMLSelectElement
  const status = input.value
  input.value = item.status
  if (busy.value || status === item.status) return
  busy.value = true
  error.value = ''
  try {
    await patchAppointment(item, 'status', { status })
    notice.value = '面试状态已更新。'
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

function checkReminders() {
  const now = Date.now()
  const due = appointments.value.filter((item) => {
    const remaining = new Date(item.time).getTime() - now
    return pending(item) && item.reminderMinutes > 0 && remaining > 0 &&
      remaining <= item.reminderMinutes * 60_000 && !remindersShown.has(item.id + item.time)
  })
  if (!due.length) return
  for (const item of due) remindersShown.add(item.id + item.time)
  notice.value = due.map((item) => item.time.slice(11, 16) + ' ' + item.company + ' ' + item.position).join('、') + ' 即将开始。'
}

function startDelete(item: Appointment) {
  if (loading.value || busy.value) return
  editing.value = item
  dialogMode.value = 'delete'
  dialogError.value = ''
  if (!dialog.value?.open) dialog.value?.showModal()
}

function closeDialog() {
  if (!busy.value) dialog.value?.close()
}

async function save() {
  if (busy.value || !form.company.trim() || !form.position.trim() || !form.time) return
  busy.value = true
  dialogError.value = ''
  const id = editing.value?.id
  const body: ScheduleFields = {
    ...form,
    company: form.company.trim(), position: form.position.trim(),
    location: form.location.trim(), notes: form.notes.trim(),
  }
  try {
    const saved = await api<Appointment>(
      id ? '/schedules/' + encodeURIComponent(id) : '/schedules',
      { ...json(body), method: id ? 'PUT' : 'POST' },
    )
    appointments.value = [...appointments.value.filter((item) => item.id !== saved.id), saved]
    selected.value = saved.time.slice(0, 10)
    notice.value = id ? '已更新面试日程。' : '已添加面试日程。'
    dialog.value?.close()
  } catch (failure) {
    dialogError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

async function remove() {
  const id = editing.value?.id
  if (!id || busy.value) return
  busy.value = true
  dialogError.value = ''
  try {
    await api<void>('/schedules/' + encodeURIComponent(id), { method: 'DELETE' })
    appointments.value = appointments.value.filter((item) => item.id !== id)
    notice.value = '已删除面试日程。'
    dialog.value?.close()
  } catch (failure) {
    dialogError.value = errorMessage(failure)
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  reminderTimer = window.setInterval(checkReminders, 15_000)
  await refresh()
  checkReminders()
})
onUnmounted(() => window.clearInterval(reminderTimer))
</script>

<template>
  <section class="work-page">
    <header class="page-header">
      <div>
        <h1>面试日程</h1>
        <p>记录面试邀请，按日历查看时间、方式和地点。</p>
      </div>
      <div class="actions">
        <button type="button" class="button secondary" :disabled="busy || loading" @click="refresh">刷新</button>
        <button type="button" class="button secondary" :disabled="busy || loading || !loaded" @click="startImport">导入面试邀请</button>
        <button type="button" class="button primary" :disabled="busy || loading || !loaded" @click="startCreate()">＋ 添加面试</button>
      </div>
    </header>

    <div class="schedule-stats">
      <span><strong>{{ appointments.length }}</strong> 场面试</span>
      <span><strong>{{ pendingCount }}</strong> 场待进行</span>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <section class="panel schedule-panel" aria-label="面试日历" :aria-busy="loading">
      <header class="schedule-top">
        <div class="actions range-controls">
          <button type="button" class="icon-button" aria-label="上一个日期范围" @click="browse(-1)">‹</button>
          <button type="button" class="button secondary small" @click="selected = today">今天</button>
          <button type="button" class="icon-button" aria-label="下一个日期范围" @click="browse(1)">›</button>
          <strong>{{ rangeLabel }}</strong>
        </div>
        <div class="segmented" role="group" aria-label="日历视图">
          <button v-for="(label, mode) in viewLabels" :key="mode" type="button" :aria-pressed="view === mode" @click="view = mode">{{ label }}</button>
        </div>
      </header>

      <div class="schedule-filters">
        <input v-model="search" type="search" aria-label="搜索面试日程" placeholder="搜索公司、岗位或备注…">
        <select v-model="statusFilter" aria-label="筛选面试状态">
          <option value="">全部状态</option>
          <option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="typeFilter" aria-label="筛选面试形式">
          <option value="">全部形式</option>
          <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
        </select>
        <input :value="selected" type="date" aria-label="跳转到日期" @change="changeDate">
      </div>

      <p v-if="!loaded && loading" class="empty-state compact" role="status">正在加载日程…</p>
      <div v-else-if="!loaded" class="empty-state">
        <strong>暂时无法读取日程</strong>
        <button type="button" class="button secondary" @click="refresh">重新加载</button>
      </div>
      <template v-else>
        <div v-if="view === 'week' || view === 'month'" class="schedule-calendar" :class="'schedule-' + view">
          <template v-if="view === 'month'">
            <span v-for="day in weekdays" :key="day" class="month-weekday">{{ day }}</span>
          </template>
          <div
            v-for="(day, index) in calendarDays"
            :key="day.key"
            class="calendar-cell"
            :class="{ selected: day.key === selected, outside: day.outside }"
            @dragover="allowDrop" @drop="moveTo($event, day.key)"
          >
            <button
              type="button"
              class="calendar-date"
              :aria-label="day.key + '，' + day.items.length + ' 场'"
              :aria-current="day.key === today ? 'date' : undefined"
              :aria-pressed="day.key === selected"
              @click="selected = day.key"
            >
              <small v-if="view === 'week'">{{ weekdays[index] }}</small>
              <strong>{{ view === 'week' ? (day.date.getMonth() + 1) + '/' + day.date.getDate() : day.date.getDate() }}</strong>
              <span v-if="view === 'month' && day.items.length" class="date-count">{{ day.items.length }}</span>
            </button>
            <button
              v-if="view === 'week'"
              type="button"
              class="icon-button day-add"
              :aria-label="'在 ' + day.key + ' 添加面试'"
              :disabled="loading || busy"
              @click="startCreate(day.key)"
            >＋</button>
            <div v-if="view === 'week'" class="week-appointments">
              <button
                v-for="item in day.items" :key="item.id" type="button" class="week-item"
                :class="'item-' + item.status.toLowerCase()" :draggable="!loading && !busy"
                @dragstart="startDrag($event, item)" @click="startEdit(item)"
              >
                <time>{{ item.time.slice(11, 16) }}</time>
                <strong>{{ item.company }}</strong>
                <span>{{ item.position }}</span>
                <small>{{ typeLabels[item.type] }} · {{ statusLabels[item.status] }}</small>
              </button>
              <small v-if="!day.items.length" class="muted">暂无安排</small>
            </div>
          </div>
        </div>

        <section v-if="view === 'list' || view === 'day' || view === 'month'" class="agenda" aria-label="日程列表">
          <header class="panel-heading">
            <h2>{{ view === 'list' ? '日程列表' : selected + ' 的安排' }}</h2>
            <label v-if="view === 'list'" class="checkbox">
              <input v-model="allDates" type="checkbox"> 显示全部日期
            </label>
            <button v-else type="button" class="button secondary small" @click="startCreate()">＋ 添加面试</button>
          </header>
          <div v-if="agendaGroups.length" class="agenda-groups">
            <section v-for="group in agendaGroups" :key="group.key" class="agenda-group">
              <h3 v-if="view === 'list'">{{ dayLabel.format(new Date(group.key + 'T00:00:00')) }} <small>{{ group.items.length }} 场</small></h3>
              <ul class="agenda-list">
                <li v-for="item in group.items" :key="item.id" class="agenda-row" :class="'item-' + item.status.toLowerCase()">
                  <time :datetime="item.time">{{ item.time.slice(0, 16).replace('T', ' ') }}</time>
                  <h4>{{ item.company }}</h4>
                  <p>{{ item.position }}</p>
                  <p class="agenda-meta">
                    {{ typeLabels[item.type] }} <span v-if="item.round">第 {{ item.round }} 轮</span>
                    <span class="chip" :class="'chip-' + item.status.toLowerCase()">{{ statusLabels[item.status] }}</span>
                  </p>
                  <p v-if="item.location || item.interviewer">{{ [item.location, item.interviewer].filter(Boolean).join(' · ') }}</p>
                  <p v-if="item.notes" class="agenda-notes">{{ item.notes }}</p>
                  <a v-if="item.meetingUrl" class="text-button" :href="item.meetingUrl" target="_blank" rel="noopener noreferrer">加入会议</a>
                  <div class="actions agenda-actions">
                    <select :value="item.status" :aria-label="item.company + ' 的面试状态'" :disabled="busy" @change="changeStatus(item, $event)">
                      <option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option>
                    </select>
                    <button type="button" class="text-button" @click="startEdit(item)">编辑</button>
                    <button type="button" class="text-button" @click="startMove(item)">改期</button>
                    <button type="button" class="text-button quiet" @click="startDelete(item)">删除</button>
                  </div>
                </li>
              </ul>
            </section>
          </div>
          <div v-else class="empty-state compact"><strong>暂无符合条件的面试安排</strong></div>
        </section>
      </template>
    </section>

    <dialog ref="dialog" class="form-dialog" aria-labelledby="schedule-dialog-title" @cancel.prevent="closeDialog">
      <form v-if="dialogMode === 'edit'" class="field-list" @submit.prevent="save">
        <header class="panel-heading dialog-heading">
          <h2 id="schedule-dialog-title">{{ editing ? '编辑面试日程' : '添加面试日程' }}</h2>
          <button type="button" class="icon-button" aria-label="关闭" :disabled="busy" @click="closeDialog">×</button>
        </header>
        <p v-if="importHint" class="notice" role="status">{{ importHint }}</p>
        <fieldset class="schedule-form" :disabled="busy">
          <label class="field">
            <span>公司名称</span>
            <input v-model="form.company" type="text" required maxlength="100">
          </label>
          <label class="field">
            <span>面试岗位</span>
            <input v-model="form.position" type="text" required maxlength="100">
          </label>
          <label class="field full">
            <span>面试时间</span>
            <input v-model="form.time" type="datetime-local" required>
          </label>
          <label class="field">
            <span>面试形式</span>
            <select v-model="form.type"><option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option></select>
          </label>
          <label class="field">
            <span>状态</span>
            <select v-model="form.status"><option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option></select>
          </label>
          <label class="field full">
            <span>地点</span>
            <input v-model="form.location" type="text" maxlength="300">
          </label>
          <label class="field full">
            <span>会议链接</span>
            <input v-model="form.meetingUrl" type="url" maxlength="500" placeholder="https://">
          </label>
          <label class="field">
            <span>面试官</span>
            <input v-model="form.interviewer" type="text" maxlength="100">
          </label>
          <label class="field">
            <span>轮次</span>
            <input :value="form.round ?? ''" type="number" min="1" max="20" @input="form.round = ($event.target as HTMLInputElement).valueAsNumber || null">
          </label>
          <label class="field full">
            <span>日历提醒</span>
            <select v-model="form.reminderMinutes">
              <option :value="0">不提醒</option>
              <option v-for="minutes in [5, 10, 15, 30, 60]" :key="minutes" :value="minutes">提前 {{ minutes }} 分钟</option>
            </select>
          </label>
          <label class="field full">
            <span>备注</span>
            <textarea v-model="form.notes" maxlength="1000" rows="3"></textarea>
          </label>
        </fieldset>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <footer class="actions dialog-actions">
          <button v-if="editing" type="button" class="button danger ghost" :disabled="busy" @click="startDelete(editing)">删除日程</button>
          <button type="button" class="button secondary" :disabled="busy" @click="closeDialog">取消</button>
          <button type="submit" class="button primary" :disabled="busy">{{ busy ? '正在保存…' : '保存日程' }}</button>
        </footer>
      </form>
      <form v-else-if="dialogMode === 'invite'" class="field-list" @submit.prevent="parseInvitation">
        <header class="panel-heading dialog-heading">
          <h2 id="schedule-dialog-title">导入面试邀请</h2>
          <button type="button" class="icon-button" aria-label="关闭" :disabled="busy" @click="closeDialog">×</button>
        </header>
        <label class="field">
          <span>面试邀请</span>
          <textarea v-model="invitation" rows="8" maxlength="4000" required :disabled="busy"
            placeholder="粘贴通知中的公司、岗位、面试时间和会议地址"></textarea>
        </label>
        <label class="checkbox">
          <input v-model="useModel" type="checkbox" :disabled="busy"> 允许将邀请发送给已配置的模型，补全规则未识别的信息
        </label>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <footer class="actions dialog-actions">
          <button type="button" class="button secondary" :disabled="busy" @click="closeDialog">取消</button>
          <button type="submit" class="button primary" :disabled="busy">{{ busy ? '正在识别…' : '识别日程' }}</button>
        </footer>
      </form>
      <form v-else-if="dialogMode === 'move'" class="field-list" @submit.prevent="editing && reschedule(editing, moveTime)">
        <header class="panel-heading dialog-heading"><h2 id="schedule-dialog-title">调整面试时间</h2></header>
        <p>{{ editing?.company }} · {{ editing?.position }}</p>
        <label class="field">
          <span>新的面试时间</span>
          <input v-model="moveTime" type="datetime-local" required :disabled="busy">
        </label>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <footer class="actions dialog-actions">
          <button type="button" class="button secondary" :disabled="busy" @click="closeDialog">取消</button>
          <button type="submit" class="button primary" :disabled="busy">{{ busy ? '正在改期…' : '确认改期' }}</button>
        </footer>
      </form>
      <form v-else class="field-list" @submit.prevent="remove">
        <header class="panel-heading dialog-heading"><h2 id="schedule-dialog-title">删除面试日程</h2></header>
        <p>删除「{{ editing?.company }}」的面试安排？</p>
        <p v-if="dialogError" class="error" role="alert">{{ dialogError }}</p>
        <footer class="actions dialog-actions">
          <button type="button" class="button secondary" :disabled="busy" @click="closeDialog">取消</button>
          <button type="submit" class="button danger" :disabled="busy">{{ busy ? '正在删除…' : '确认删除' }}</button>
        </footer>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.schedule-stats { display: flex; flex-wrap: wrap; gap: 24px; color: var(--muted); margin-bottom: 18px; }
.schedule-stats strong { margin-right: 3px; color: var(--ink); font-size: 16px; }
.schedule-panel { padding: 20px; }
.schedule-top { display: flex; justify-content: space-between; flex-wrap: wrap; align-items: center; gap: 12px; }
.schedule-top .segmented { grid-template-columns: repeat(4, auto); }
.range-controls { gap: 8px; }
.range-controls strong { font-size: 14px; margin-left: 4px; }
.schedule-filters { display: grid; grid-template-columns: minmax(150px, 1fr) auto auto auto; gap: 8px; margin: 17px 0; padding-bottom: 17px; border-bottom: 1px solid var(--line); }
.schedule-filters :is(input, select) { min-width: 0; min-height: 38px; }
.schedule-calendar { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 8px; }
.calendar-cell { position: relative; min-width: 0; border: 1px solid var(--line); border-radius: 7px; background: var(--canvas); min-height: 66px; }
.calendar-cell.selected { border-color: var(--accent); }
.calendar-cell.outside .calendar-date { color: var(--muted); opacity: .68; }
.calendar-date { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; position: relative; width: 100%; padding: 9px; color: var(--ink); background: transparent; border: 0; text-align: left; }
.calendar-date small { color: var(--muted); }
.calendar-cell.selected .calendar-date strong { color: var(--accent); }
.month-weekday { padding: 0 7px 2px; text-align: center; color: var(--muted); font-size: 12px; }
.date-count { color: var(--accent); font-size: 11px; }
.schedule-week .calendar-cell { min-height: 290px; }
.day-add { position: absolute; top: 11px; right: 5px; width: 27px; min-width: 27px; height: 27px; font-size: 15px; }
.schedule-week .calendar-date { display: grid; justify-content: start; border-bottom: 1px solid var(--line); }
.week-appointments { padding: 7px; display: grid; gap: 7px; }
.week-appointments > small { text-align: center; padding-top: 10px; }
.week-item { display: grid; gap: 3px; padding: 8px; border: 1px solid var(--line); border-left: 2px solid var(--accent); border-radius: 5px; background: var(--surface); color: var(--ink); text-align: left; overflow-wrap: anywhere; }
.week-item[draggable="true"] { cursor: grab; }
.week-item time, .agenda-row time { color: var(--accent); font-size: 12px; }
.week-item span { color: var(--muted); font-size: 12px; }
.week-item small { color: var(--muted); font-size: 11px; }
.week-item.item-completed { border-inline-start-color: #19845c; }
.week-item.item-cancelled { border-inline-start-color: #8d97a4; }
.agenda { margin-top: 20px; }
.agenda .panel-heading { margin-bottom: 12px; }
.agenda .panel-heading h2 { font-size: 16px; }
.agenda-groups, .agenda-group { display: grid; gap: 11px; }
.agenda-groups { gap: 21px; }
.agenda-group h3, .agenda-row h4 { margin: 0; font-size: 14px; }
.agenda-group h3 small { margin-inline-start: 6px; color: var(--muted); font-weight: 400; }
.agenda-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr)); gap: 12px; }
.agenda-row { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; padding: 14px 15px; border: 1px solid var(--line); border-inline-start: 3px solid var(--accent); border-radius: 8px; overflow-wrap: anywhere; }
.agenda-row.item-completed { border-inline-start-color: var(--success); }
.agenda-row:is(.item-cancelled, .item-expired) { border-inline-start-color: var(--muted); }
.agenda-row p, .checkbox { color: var(--muted); font-size: 13px; }
.agenda-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.agenda-row .agenda-notes { color: var(--ink); white-space: pre-wrap; }
.agenda-actions { align-self: stretch; border-top: 1px solid var(--line); padding-top: 10px; margin-top: auto; gap: 7px; font-size: 12px; }
.agenda-actions select { min-height: 32px; padding: 4px 7px; margin-right: auto; }
.checkbox { display: flex; align-items: center; gap: 5px; }
.checkbox input { accent-color: var(--accent); }
.schedule-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; }
.schedule-form .full { grid-column: 1 / -1; }
@media (max-width: 740px) {
  .schedule-week { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .schedule-week .calendar-cell { min-height: 95px; }
  .schedule-filters { grid-template-columns: 1fr 1fr; }
  .schedule-filters :is(input[type="search"], input[type="date"]) { grid-column: 1 / -1; }
}
@media (max-width: 500px) {
  .schedule-panel { padding: 13px; }
  .schedule-month { gap: 3px; }
  .schedule-month .calendar-date { padding: 6px; }
  .schedule-form { grid-template-columns: 1fr; }
}
</style>
