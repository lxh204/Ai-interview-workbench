<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import { browserSpeechAvailable, cloudCaptureAvailable, useVoiceCapture, type CaptureMethod } from '../voice/voiceCapture'
import InterviewAnswers from './InterviewAnswers.vue'
import InterviewSetup from './InterviewSetup.vue'
import { difficulties, interviewRoles, reportLink, sessionLink, topicOptions, useInterviewLocation, useInterviewSession, voicePlanKey, type InterviewConfig } from './interviewFlow'

const location = useInterviewLocation()
const routeId = computed(() => {
  const [path, search] = location.value.split('?')
  if (path.replace(/\/$/, '') !== '#/voice-interview') return ''
  const query = new URLSearchParams(search)
  return query.get('session') || query.get('sessionId') || ''
})
const { session, answer, error, pending, restore, start, submit, setState } = useInterviewSession(() => routeId.value)
const configuredCloud = ref(false)
const method = ref<CaptureMethod>(browserSpeechAvailable ? 'browser' : 'cloud')
const cloudAvailable = computed(() => configuredCloud.value && cloudCaptureAvailable)
const voiceAvailable = computed(() => method.value === 'cloud' ? cloudAvailable.value : browserSpeechAvailable)
const canSpeak = 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window
const speaking = ref(false)
const playbackError = ref('')
const recordingMode = ref<'toggle' | 'hold'>('toggle')
const finishing = ref(false)
const plannedMinutes = ref(30)
const elapsed = ref(0)
const transcript = ref<HTMLElement>()
const viewActive = ref(true)
let activeUtterance: SpeechSynthesisUtterance | undefined
let holdPointer: number | null = null
let clock: number | undefined
let clockId = ''
let startedAt = 0
let elapsedBefore = 0

function appendTranscript(text: string) {
  const draft = answer.value.trimEnd()
  // Chinese sentences already contain their own punctuation. Add spacing for other word boundaries.
  const separator = draft && /[A-Za-z0-9]$/.test(draft) && /^[A-Za-z0-9]/.test(text) ? ' ' : ''
  answer.value = (draft + separator + text).slice(0, 8000)
  if (answer.value.length >= 8000) capture.stop()
}
const capture = useVoiceCapture(appendTranscript)
const { state: captureState, interim, message: speechError, volume } = capture
const capturing = computed(() => ['connecting', 'listening', 'stopping'].includes(captureState.value))
const availableToAnswer = computed(() => Boolean(session.value?.question && !session.value.paused && !session.value.completed && !pending.value && !finishing.value))
const canRecord = computed(() => availableToAnswer.value && voiceAvailable.value && !speaking.value && answer.value.length < 8000)
const captureLabel = computed(() => ({
  idle: voiceAvailable.value ? '待录音' : '文字作答',
  connecting: method.value === 'cloud' ? '正在连接云端转写' : '正在开启浏览器转写',
  listening: '正在聆听',
  stopping: '正在完成转写',
  error: '转写已中断',
}[captureState.value]))
const microphoneLabel = computed(() => {
  if (captureState.value === 'connecting') return '取消录音'
  if (captureState.value === 'listening') return '停止录音'
  return recordingMode.value === 'hold' ? '按住说话' : '开始录音'
})
const timerText = computed(() => [Math.floor(elapsed.value / 60), elapsed.value % 60]
  .map(value => String(value).padStart(2, '0')).join(':'))
const elapsedLimit = computed(() => elapsed.value >= plannedMinutes.value * 60)

function storeRead(key: string) {
  try { return sessionStorage.getItem(key) } catch { return null }
}

function storeWrite(key: string, value: string) {
  try { sessionStorage.setItem(key, value) } catch { /* The open page keeps its own draft and timer. */ }
}

function storeRemove(key: string) {
  try { sessionStorage.removeItem(key) } catch { /* Session storage can be disabled. */ }
}

function draftKey() {
  const active = session.value
  return active && active.id === routeId.value && active.kind === 'voice' && !active.completed
    ? `interview-voice:${active.id}:draft:${active.index}` : ''
}

watch(draftKey, (key, previous) => {
  if (previous && (session.value?.completed || (key && key.split(':')[1] === previous.split(':')[1]))) storeRemove(previous)
  const saved = key ? storeRead(key) : null
  if (saved !== null) answer.value = saved.slice(0, 8000)
})
watch(answer, value => {
  const key = draftKey()
  if (key) storeWrite(key, value)
})

function updateClock() {
  const current = elapsedBefore + (startedAt ? Math.max(0, Date.now() - startedAt) : 0)
  elapsed.value = Math.floor(current / 1000)
}

function stopClock() {
  if (!startedAt) return
  elapsedBefore += Math.max(0, Date.now() - startedAt)
  startedAt = 0
  window.clearInterval(clock)
  clock = undefined
  updateClock()
  storeWrite(`interview-voice:${clockId}:time`, String(elapsedBefore))
}

function applyClock() {
  const active = session.value
  const id = active?.id === routeId.value && active.kind === 'voice' ? active.id : ''
  if (id !== clockId) {
    stopClock()
    clockId = id
    if (id) {
      elapsedBefore = Math.max(0, Number(storeRead(`interview-voice:${id}:time`)) || 0)
      const planned = Number(storeRead(voicePlanKey(id)))
      if (Number.isInteger(planned) && planned >= 5 && planned <= 120) plannedMinutes.value = planned
      else plannedMinutes.value = 30
    } else {
      elapsedBefore = 0
    }
    updateClock()
  }
  if (!id || !active || active.paused || active.completed || !viewActive.value) {
    stopClock()
  } else if (!startedAt) {
    startedAt = Date.now()
    clock = window.setInterval(updateClock, 250)
  }
}

function stopPlayback() {
  if (activeUtterance) {
    activeUtterance = undefined
    speechSynthesis.cancel()
  }
  speaking.value = false
}

function playQuestion() {
  if (speaking.value) {
    stopPlayback()
    return
  }
  const question = session.value?.question
  if (!canSpeak || !availableToAnswer.value || !question || capturing.value) return
  playbackError.value = ''
  const utterance = new SpeechSynthesisUtterance(question)
  utterance.lang = 'zh-CN'
  utterance.rate = 0.95
  activeUtterance = utterance
  speaking.value = true
  utterance.onend = () => {
    if (activeUtterance !== utterance) return
    activeUtterance = undefined
    speaking.value = false
  }
  utterance.onerror = () => {
    if (activeUtterance !== utterance) return
    activeUtterance = undefined
    speaking.value = false
    playbackError.value = '浏览器无法朗读，可以继续查看题目文字。'
  }
  speechSynthesis.speak(utterance)
}

function stopMedia() {
  holdPointer = null
  capture.release()
  stopPlayback()
}

function record() {
  if (capturing.value) capture.stop()
  else if (canRecord.value && session.value) capture.start(method.value, session.value.id)
}

function holdStart(event: PointerEvent) {
  if (recordingMode.value !== 'hold' || !canRecord.value || capturing.value || event.button !== 0) return
  event.preventDefault()
  holdPointer = event.pointerId
  const button = event.currentTarget as HTMLButtonElement
  button.focus()
  button.setPointerCapture(event.pointerId)
  record()
}

function holdEnd(event?: PointerEvent) {
  if (recordingMode.value !== 'hold' || (event && holdPointer !== event.pointerId)) return
  holdPointer = null
  capture.stop()
}

function microphoneKeyDown(event: KeyboardEvent) {
  if (recordingMode.value !== 'hold' || !['Enter', ' '].includes(event.key)) return
  event.preventDefault()
  if (!event.repeat && !capturing.value) record()
}

function microphoneKeyUp(event: KeyboardEvent) {
  if (recordingMode.value !== 'hold' || !['Enter', ' '].includes(event.key)) return
  event.preventDefault()
  holdEnd()
}

function stopForPageHide() {
  stopMedia()
  stopClock()
}

function checkVisibility() {
  if (document.hidden) stopMedia()
}

async function startVoice(config: InterviewConfig) {
  const planned = plannedMinutes.value
  if (!Number.isInteger(planned) || planned < 5 || planned > 120) {
    error.value = '请输入 5 到 120 分钟的计划时长。'
    return
  }
  await start(config, created => {
    storeWrite(voicePlanKey(created.id), String(planned))
  })
}

async function pauseOrResume() {
  stopMedia()
  await setState(session.value?.paused ? 'resume' : 'pause')
}

async function finish() {
  stopMedia()
  finishing.value = false
  await setState('finish')
}

function confirmFinish() {
  stopMedia()
  finishing.value = true
}

async function submitAnswer() {
  if (capturing.value || !availableToAnswer.value) return
  stopPlayback()
  await submit()
}

watch([routeId, () => session.value?.id, () => session.value?.paused, () => session.value?.completed, viewActive], applyClock, { immediate: true })
watch([routeId, () => session.value?.index], async () => {
  stopMedia()
  speechError.value = playbackError.value = ''
  finishing.value = false
  await nextTick()
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
})
watch([recordingMode, method], () => {
  stopMedia()
  speechError.value = ''
})

onMounted(() => {
  document.addEventListener('visibilitychange', checkVisibility)
  window.addEventListener('pagehide', stopForPageHide)
  window.addEventListener('pageshow', applyClock)
  void api<{ cloudRecognition: boolean }>('/voice/capabilities').then(result => {
    configuredCloud.value = result.cloudRecognition
    if (cloudAvailable.value && !browserSpeechAvailable) method.value = 'cloud'
  }).catch(() => undefined)
})
onActivated(() => { viewActive.value = true })
onDeactivated(() => { viewActive.value = false; stopForPageHide() })
onUnmounted(() => {
  viewActive.value = false
  stopForPageHide()
  document.removeEventListener('visibilitychange', checkVisibility)
  window.removeEventListener('pagehide', stopForPageHide)
  window.removeEventListener('pageshow', applyClock)
})
</script>

<template>
  <header class="page-header">
    <div>
      <h1>语音模拟面试</h1>
      <p v-if="!routeId">口述回答会实时转成字幕，确认后再提交。</p>
    </div>
    <a class="button secondary" href="#/interviews">面试记录</a>
  </header>
  <div v-if="!routeId" class="voice-setup">
    <section class="panel voice-preferences" aria-labelledby="voice-preferences">
      <div>
        <h2 id="voice-preferences">语音设置</h2>
        <p class="result-context">点击录音后才会申请麦克风权限。{{ browserSpeechAvailable || cloudAvailable ? '也可以直接输入回答。' : '当前环境可用文字作答。' }}</p>
      </div>
      <label class="field voice-duration">
        <span>计划时长（分钟）</span>
        <input v-model.number="plannedMinutes" type="number" min="5" max="120" step="1" :disabled="pending">
      </label>
    </section>
    <InterviewSetup kind="voice" :pending="pending" @start="startVoice" />
  </div>
  <section v-else-if="!session" class="panel field-list" :aria-busy="pending">
    <p v-if="pending" role="status">正在读取语音面试…</p>
    <div v-else class="voice-actions">
      <button class="button secondary" type="button" @click="restore(routeId)">重试读取</button>
    </div>
  </section>
  <div v-else-if="session.kind !== 'voice'" class="panel">
    <h2>这是一场文字面试</h2>
    <a class="button primary" :href="sessionLink(session.id, session.kind)">继续文字面试</a>
  </div>
  <template v-else>
    <section class="panel voice-session" aria-label="语音会话状态">
      <div>
        <h2>{{ topicOptions[session.topic].label }}</h2>
        <p class="result-context"><template v-if="topicOptions[session.topic].label !== interviewRoles[session.role]">{{ interviewRoles[session.role] }} · </template>{{ difficulties[session.difficulty] }}</p>
      </div>
      <div class="voice-session-progress">
        <label for="voice-progress">已提交 {{ session.index }} / {{ session.total }} 题</label>
        <progress id="voice-progress" :max="session.total" :value="session.index"></progress>
      </div>
      <div class="voice-time">
        <span>本标签页用时</span>
        <strong>{{ timerText }}</strong>
        <span :class="{ 'voice-limit': elapsedLimit }">{{ elapsedLimit ? '已达计划时长' : `计划 ${plannedMinutes} 分钟` }}</span>
      </div>
      <span class="chip" role="status">{{ session.completed ? '已结束' : session.paused ? '已暂停' : '进行中' }}</span>
    </section>
    <div class="voice-grid">
      <section class="panel voice-dialogue" aria-labelledby="voice-dialogue">
        <header class="panel-heading">
          <h2 id="voice-dialogue">面试对话</h2>
          <button v-if="canSpeak && session.question" class="button secondary" type="button"
                  :disabled="!availableToAnswer || capturing" @click="playQuestion">{{ speaking ? '停止读题' : '播放题目' }}</button>
        </header>
        <div ref="transcript" class="voice-transcript">
          <template v-if="session.answers.length">
            <h3>已提交的回答</h3>
            <InterviewAnswers :answers="session.answers" />
          </template>
          <div v-if="session.question" class="question-card" aria-live="polite">
            <span>面试官 · 第 {{ session.index + 1 }} 题</span>
            <p>{{ session.question }}</p>
          </div>
          <p v-if="speaking" class="result-context" role="status">正在播放题目，播放结束后可开启麦克风。</p>
          <p v-if="playbackError" class="result-context" role="status">{{ playbackError }}</p>
        </div>
      </section>
      <section class="panel voice-answer" aria-labelledby="voice-answer" :aria-busy="pending">
        <header class="panel-heading">
          <h2 id="voice-answer">我的回答</h2>
          <span v-if="!session.completed" class="voice-state" :class="{ 'voice-live': captureState === 'listening' }" role="status">
            {{ session.paused ? '面试已暂停' : captureLabel }}
          </span>
        </header>
        <div v-if="session.completed" class="empty-state">
          <a class="button primary" :href="reportLink(session.id)">查看面试报告</a>
        </div>
        <template v-else>
          <form class="field-list" @submit.prevent="submitAnswer">
            <div v-if="voiceAvailable" class="voice-options">
              <label class="field">
                <span>录音方式</span>
                <select v-model="recordingMode" :disabled="!availableToAnswer || capturing">
                  <option value="toggle">点击录音</option>
                  <option value="hold">按住说话</option>
                </select>
              </label>
              <label v-if="browserSpeechAvailable && cloudAvailable" class="field">
                <span>转写服务</span>
                <select v-model="method" :disabled="!availableToAnswer || capturing">
                  <option value="browser">浏览器转写</option>
                  <option value="cloud">百炼实时转写</option>
                </select>
              </label>
            </div>
            <div v-if="voiceAvailable" class="voice-microphone">
              <button class="voice-recorder" type="button" :class="{ recording: captureState === 'listening' }" data-testid="voice-recorder-toggle"
                      :aria-label="microphoneLabel" :aria-pressed="captureState === 'listening'"
                      :disabled="captureState === 'stopping' || (!canRecord && !capturing)" @click="recordingMode === 'toggle' && record()"
                      @pointerdown="holdStart" @pointerup="holdEnd" @pointercancel="holdEnd" @lostpointercapture="holdEnd"
                      @keydown="microphoneKeyDown" @keyup="microphoneKeyUp" @blur="holdEnd()">
                <span class="voice-mic-icon" aria-hidden="true"></span>
              </button>
              <p v-if="method === 'cloud'" class="voice-meter-label">
                <meter min="0" max="100" :value="volume" aria-label="麦克风音量"></meter>
                <span>百炼转写</span>
              </p>
              <p v-else-if="captureState === 'idle'" class="result-context">{{ microphoneLabel }}</p>
            </div>
            <p v-if="!voiceAvailable" class="result-context">语音转写不可用，可以输入回答。</p>
            <p v-else-if="method === 'cloud' && !capturing" class="result-context">录音时，麦克风音频会发送至阿里云百炼转写。</p>
            <p v-else-if="method === 'browser' && !capturing" class="result-context">浏览器的语音服务负责转写。</p>
            <p v-if="interim" class="voice-interim" role="status">{{ interim }}</p>
            <p v-if="speechError" class="voice-error" role="status">{{ speechError }}</p>
            <label class="field">
              <span>识别字幕 / 文字回答</span>
              <textarea v-model="answer" rows="6" maxlength="8000" :disabled="!availableToAnswer"
                        placeholder="点击麦克风说话，或在这里直接输入。"
                        @keydown.ctrl.enter.prevent="submitAnswer" @keydown.meta.enter.prevent="submitAnswer"></textarea>
            </label>
            <div class="voice-submit">
              <span class="result-context">{{ answer.length }} / 8000</span>
              <button type="submit" class="button primary" :disabled="!answer.trim() || !availableToAnswer || capturing">
                {{ pending ? '正在保存…' : '提交回答' }}
              </button>
            </div>
          </form>
          <div class="voice-actions voice-controls">
            <button type="button" class="button secondary" :disabled="pending" @click="pauseOrResume">
              {{ session.paused ? '恢复面试' : '暂停面试' }}
            </button>
            <button v-if="!finishing" type="button" class="text-button" :disabled="pending" @click="confirmFinish">结束面试</button>
          </div>
          <div v-if="finishing" class="confirmation" role="group" aria-label="确认结束面试">
            <p>报告将保留已提交的回答。</p>
            <button type="button" class="text-button" :disabled="pending" @click="finish">确认结束</button>
            <button type="button" class="text-button quiet" @click="finishing = false">继续面试</button>
          </div>
        </template>
      </section>
    </div>
  </template>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

<style scoped>
.voice-setup, .voice-answer { display: grid; gap: 16px; }
.voice-preferences, .voice-session { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px 28px; }
.voice-preferences h2, .voice-session h2 { margin-bottom: 5px; font-size: 16px; }
.voice-duration { min-width: 140px; }
.voice-session { margin-bottom: 16px; }
.voice-session-progress { display: grid; gap: 4px; min-width: min(100%, 140px); }
.voice-session-progress label, .voice-time span { color: var(--muted); font-size: 12px; }
.voice-session-progress progress { width: 100%; accent-color: var(--accent); }
.voice-time { display: grid; grid-template-columns: auto auto; align-items: center; gap: 0 8px; }
.voice-time strong { font-variant-numeric: tabular-nums; font-size: 18px; }
.voice-time span:last-child { grid-column: 1 / -1; }
.voice-time .voice-limit { color: var(--danger); }
.voice-grid { display: grid; align-items: start; grid-template-columns: minmax(0, 1.36fr) minmax(325px, 1fr); gap: 16px; }
.voice-grid .panel-heading { margin: 0; }
.voice-transcript { display: grid; align-content: start; gap: 12px; min-height: 240px; max-height: 63vh; margin-top: 16px; overflow: auto; scrollbar-gutter: stable; }
.voice-transcript h3 { font-size: 14px; color: var(--muted); }
.voice-dialogue .question-card { margin: 0; }
.voice-actions, .voice-submit { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.voice-submit { justify-content: space-between; }
.voice-controls { padding-top: 15px; border-top: 1px solid var(--line); }
.voice-options { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 10px; }
.voice-state { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 600; }
.voice-state::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.voice-state.voice-live { color: var(--success); }
.voice-microphone { display: grid; justify-items: center; gap: 9px; padding-block: 7px; }
.voice-recorder { display: grid; place-items: center; width: 64px; height: 64px; border: 1px solid var(--line); border-radius: 50%; background: var(--soft); color: var(--accent); touch-action: none; user-select: none; }
.voice-recorder:not(:disabled):hover { border-color: var(--accent); }
.voice-recorder:disabled { opacity: .52; }
.voice-recorder.recording { color: var(--surface); background: var(--accent); border-color: var(--accent); box-shadow: 0 0 0 5px var(--accent-soft); }
.voice-mic-icon { position: relative; display: block; width: 21px; height: 18px; border: 2px solid currentColor; border-top: 0; border-radius: 0 0 12px 12px; }
.voice-mic-icon::before { position: absolute; content: ''; left: 3px; top: -7px; width: 11px; height: 18px; background: currentColor; border-radius: 6px; }
.voice-mic-icon::after { position: absolute; content: ''; left: 7px; bottom: -8px; width: 3px; height: 7px; background: currentColor; }
.voice-meter-label { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 12px; }
.voice-meter-label meter { width: 90px; height: 10px; }
.voice-interim, .voice-error { padding: 8px 10px; border-left: 3px solid var(--accent); background: var(--soft); overflow-wrap: anywhere; }
.voice-error { border-color: var(--danger); color: var(--danger); }
.voice-answer textarea { resize: vertical; }
@media (max-width: 850px) { .voice-grid { grid-template-columns: minmax(0, 1fr); } .voice-transcript { min-height: 0; } }
@media (max-width: 540px) { .voice-session-progress { order: 2; width: 100%; } .voice-preferences { align-items: flex-start; } }
</style>
