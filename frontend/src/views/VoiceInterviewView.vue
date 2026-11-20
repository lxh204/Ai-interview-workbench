<script setup lang="ts">
import { computed, onDeactivated, onUnmounted, ref, watch } from 'vue'
import InterviewAnswers from './InterviewAnswers.vue'
import InterviewSetup from './InterviewSetup.vue'
import { difficulties, interviewRoles, reportLink, sessionLink, topicOptions, useInterviewLocation, useInterviewSession } from './interviewFlow'

type RecognitionResult = { isFinal: boolean; [index: number]: { transcript: string } }
type RecognitionEvent = { resultIndex: number; results: ArrayLike<RecognitionResult> }
interface BrowserRecognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  onstart: (() => void) | null
  onresult: ((event: RecognitionEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

const speechWindow = window as Window & {
  SpeechRecognition?: new () => BrowserRecognition
  webkitSpeechRecognition?: new () => BrowserRecognition
}
const Recognition = speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition
const recognitionAvailable = Boolean(Recognition && window.isSecureContext)
const synthesisAvailable = 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window
const location = useInterviewLocation()
const routeId = computed(() => location.value.startsWith('#/voice-interview')
  ? new URLSearchParams(location.value.split('?')[1] ?? '').get('session') ?? '' : '')
const { session, answer, error, pending, restore, start, submit, setState } = useInterviewSession(() => routeId.value)
const recognizing = ref(false)
const stopping = ref(false)
const speaking = ref(false)
const interim = ref('')
const speechMessage = ref('')
const finishing = ref(false)
let recognizer: BrowserRecognition | undefined
let utterance: SpeechSynthesisUtterance | undefined

const speechErrors: Record<string, string> = {
  'not-allowed': '未取得麦克风权限。可以检查浏览器权限或直接输入回答。',
  'service-not-allowed': '浏览器语音服务不可用。你仍可手动输入回答。',
  'audio-capture': '未检测到可用的麦克风。你仍可手动输入回答。',
  'no-speech': '没有检测到语音，可以重试或直接输入回答。',
  network: '浏览器转写服务连接失败，已识别的内容会保留。',
}

function stopSpeech() {
  if (utterance) {
    utterance = undefined
    speechSynthesis.cancel()
  }
  speaking.value = false
}

function speakQuestion() {
  const text = session.value?.question
  if (!text || !synthesisAvailable || recognizer) return
  if (speaking.value) {
    stopSpeech()
    return
  }
  speechMessage.value = ''
  const active = new SpeechSynthesisUtterance(text)
  active.lang = 'zh-CN'
  utterance = active
  speaking.value = true
  active.onend = () => {
    if (utterance === active) {
      speaking.value = false
      utterance = undefined
    }
  }
  active.onerror = () => {
    if (utterance === active) {
      speaking.value = false
      utterance = undefined
      speechMessage.value = '浏览器无法播放语音，可以直接阅读题目。'
    }
  }
  speechSynthesis.speak(active)
}

function stopRecognition() {
  const active = recognizer
  if (!active) return
  recognizer = undefined
  active.abort()
  recognizing.value = false
  stopping.value = false
  interim.value = ''
}

function toggleMicrophone() {
  if (recognizer) {
    stopping.value = true
    recognizer.stop()
    return
  }
  if (!Recognition || !recognitionAvailable) return
  stopSpeech()
  speechMessage.value = ''
  const active = new Recognition()
  active.lang = 'zh-CN'
  active.continuous = true
  active.interimResults = true
  recognizer = active
  recognizing.value = true
  const draft = answer.value.trimEnd()
  active.onresult = event => {
    if (recognizer !== active) return
    const final = [draft]
    const partial = []
    for (let index = 0; index < event.results.length; index++) {
      const result = event.results[index]
      const transcript = result[0].transcript.trim()
      if (!transcript) continue
      if (result.isFinal) final.push(transcript)
      else partial.push(transcript)
    }
    answer.value = final.filter(Boolean).join(' ').slice(0, 8000)
    interim.value = partial.join(' ')
    if (answer.value.length >= 8000) {
      speechMessage.value = '回答已达到 8000 字符，可以编辑后提交。'
      stopping.value = true
      active.stop()
    }
  }
  function cleanUp() {
    if (recognizer !== active) return
    recognizer = undefined
    recognizing.value = false
    stopping.value = false
    interim.value = ''
  }
  active.onerror = event => {
    if (recognizer !== active) return
    speechMessage.value = speechErrors[event.error] ?? '语音识别已中断，已识别的内容会保留。'
    cleanUp()
  }
  active.onend = cleanUp
  try {
    active.start()
  } catch {
    cleanUp()
    speechMessage.value = '浏览器无法启动语音识别，可以手动输入。'
  }
}

function releaseVoice() {
  stopRecognition()
  stopSpeech()
}

watch([routeId, () => session.value?.index], releaseVoice)
onDeactivated(releaseVoice)
onUnmounted(releaseVoice)

async function pauseOrResume() {
  releaseVoice()
  await setState(session.value?.paused ? 'resume' : 'pause')
}

async function finish() {
  releaseVoice()
  finishing.value = false
  await setState('finish')
}

async function submitAnswer() {
  stopSpeech()
  await submit()
}
</script>

<template>
  <header class="page-header">
    <h1>语音面试</h1>
    <a v-if="!routeId" class="button secondary" href="#/interviews">面试记录</a>
  </header>
  <div v-if="!routeId" class="voice-start">
    <div class="panel speech-capabilities" role="status">
      <span class="chip">{{ synthesisAvailable ? '浏览器读题可用' : '可阅读题目' }}</span>
      <span class="chip">{{ recognitionAvailable ? '浏览器语音转写可尝试' : '当前浏览器可用文字作答' }}</span>
      <p v-if="recognitionAvailable">转写由浏览器语音服务处理，提交前可以编辑。</p>
    </div>
    <InterviewSetup kind="voice" :pending="pending" @start="start" />
  </div>
  <section v-else-if="!session" class="panel field-list" :aria-busy="pending">
    <p v-if="pending" role="status">正在读取语音面试…</p>
    <div v-else class="voice-actions">
      <button class="button secondary" type="button" @click="restore(routeId)">重试读取</button>
      <a class="button primary" href="#/interviews">面试记录</a>
    </div>
  </section>
  <div v-else-if="session.kind !== 'voice'" class="panel">
    <h2>这是一场文字面试</h2>
    <a class="button primary" :href="sessionLink(session.id, session.kind)">继续文字面试</a>
  </div>
  <div v-else class="voice-grid">
    <section class="panel" aria-labelledby="voice-transcript">
      <header class="panel-heading">
        <h2 id="voice-transcript">面试对话</h2>
        <span class="chip">{{ session.index }} / {{ session.total }} 道已答</span>
      </header>
      <p class="result-context">{{ interviewRoles[session.role] }} · {{ topicOptions[session.topic].label }} · {{ difficulties[session.difficulty] }}</p>
      <div v-if="session.question" class="question-card" aria-live="polite">
        <span>面试官 · 第 {{ session.index + 1 }} 题</span>
        <p>{{ session.question }}</p>
      </div>
      <h3 class="transcript-history">已保存的对话</h3>
      <InterviewAnswers :answers="session.answers" />
    </section>
    <section class="panel" aria-labelledby="voice-answer" :aria-busy="pending">
      <header class="panel-heading">
        <h2 id="voice-answer">我的回答</h2>
        <span class="chip" role="status">{{ session.completed ? '已结束' : session.paused ? '已暂停' : '进行中' }}</span>
      </header>
      <div v-if="session.completed" class="empty-state">
        <a class="button primary" :href="reportLink(session.id)">查看面试报告</a>
      </div>
      <template v-else>
        <form class="field-list" @submit.prevent="submitAnswer">
          <div class="voice-actions">
            <button v-if="synthesisAvailable" class="button secondary" type="button"
                    :disabled="pending || session.paused || recognizing" @click="speakQuestion">{{ speaking ? '停止读题' : '播放题目' }}</button>
            <button v-if="recognitionAvailable" class="button primary" type="button" :aria-pressed="recognizing"
                    :disabled="pending || session.paused || stopping" @click="toggleMicrophone">
              {{ stopping ? '正在结束识别…' : recognizing ? '停止语音识别' : '开始语音识别' }}
            </button>
          </div>
          <p v-if="recognizing" class="listening" role="status">正在聆听…</p>
          <p v-else-if="session.paused" class="result-context" role="status">恢复面试后，可以继续本题。</p>
          <p v-else-if="!recognitionAvailable" class="result-context">当前浏览器不支持语音转写，可以直接输入回答。</p>
          <p v-if="interim" class="voice-interim">{{ interim }}</p>
          <p v-if="speechMessage" class="result-context" role="status">{{ speechMessage }}</p>
          <label class="field">
            <span>回答或转写内容</span>
            <textarea v-model="answer" rows="6" maxlength="8000" :disabled="pending || recognizing || session.paused"
                      placeholder="可以口述，也可以在这里输入。"></textarea>
          </label>
          <button type="submit" class="button primary full-width" :disabled="!answer.trim() || pending || recognizing || session.paused">
            {{ pending ? '正在保存…' : '提交回答' }}
          </button>
        </form>
        <div class="voice-actions voice-controls">
          <button type="button" class="button secondary" :disabled="pending" @click="pauseOrResume">
            {{ session.paused ? '恢复面试' : '暂停面试' }}
          </button>
          <a class="button secondary" href="#/interviews">面试记录</a>
          <button v-if="!finishing" type="button" class="text-button" :disabled="pending" @click="finishing = true">结束面试</button>
        </div>
        <div v-if="finishing" class="confirmation">
          <p>已提交的回答会保留在面试报告里。</p>
          <button type="button" class="text-button" :disabled="pending" @click="finish">确认结束</button>
          <button type="button" class="text-button quiet" @click="finishing = false">取消</button>
        </div>
      </template>
    </section>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

<style scoped>
.voice-start { display: grid; gap: 14px; }
.speech-capabilities { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-block: 13px; }
.speech-capabilities p { margin: 0; color: var(--muted); font-size: 13px; }
.voice-grid { display: grid; align-items: start; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; }
.voice-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.voice-controls { margin-top: 16px; }
.transcript-history { margin: 17px 0 10px; font-size: 16px; }
.listening { color: var(--accent); font-weight: 650; }
.voice-interim { border-left: 3px solid var(--accent); padding-left: 10px; color: var(--muted); overflow-wrap: anywhere; }
@media (max-width: 850px) { .voice-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
