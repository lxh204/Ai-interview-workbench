import { ref } from 'vue'
const pcmWorkletUrl = import.meta.env.BASE_URL + 'voice-pcm.worklet.js'

export type CaptureMethod = 'browser' | 'cloud'
type CaptureState = 'idle' | 'connecting' | 'listening' | 'stopping' | 'error'
interface Capture {
  stop(): void
  abort(): void
}
interface CaptureRun {
  capture?: Capture
  timeout?: number
}
interface CaptureEvents {
  ready(): void
  result(final: string, interim: string): void
  volume(level: number): void
  end(): void
  fail(message: string): void
}

type RecognitionResult = { isFinal: boolean; [index: number]: { transcript: string } }
interface BrowserRecognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  onstart: (() => void) | null
  onresult: ((event: { results: ArrayLike<RecognitionResult> }) => void) | null
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
export const browserSpeechAvailable = Boolean(Recognition && window.isSecureContext)
export const cloudCaptureAvailable = Boolean(window.isSecureContext && typeof navigator.mediaDevices?.getUserMedia === 'function'
  && 'AudioContext' in window && 'AudioWorkletNode' in window)

const browserErrors: Record<string, string> = {
  'not-allowed': '需要麦克风权限。可以在浏览器设置中允许，或直接输入回答。',
  'service-not-allowed': '浏览器语音服务不可用，可以直接输入回答。',
  'audio-capture': '未检测到可用的麦克风，可以直接输入回答。',
  'no-speech': '没有检测到语音，可以重新录音。',
  network: '浏览器转写连接失败，已识别的文字会保留。',
}

function browserCapture(events: CaptureEvents): Capture {
  if (!Recognition) throw new Error('当前浏览器无法使用语音转写。')
  const recognition = new Recognition()
  let accepted = 0
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = true
  recognition.onstart = events.ready
  recognition.onresult = ({ results }) => {
    const final = []
    const interim = []
    for (let index = accepted; index < results.length; index++) {
      const result = results[index]
      if (result.isFinal) {
        accepted = index + 1
        final.push(result[0].transcript.trim())
      } else {
        interim.push(result[0].transcript.trim())
      }
    }
    events.result(final.filter(Boolean).join(' '), interim.filter(Boolean).join(' '))
  }
  recognition.onerror = event => events.fail(browserErrors[event.error] ?? '语音转写已中断，已识别的文字会保留。')
  recognition.onend = events.end
  recognition.start()
  return {
    stop: () => recognition.stop(),
    abort() {
      recognition.onstart = recognition.onresult = recognition.onerror = recognition.onend = null
      recognition.abort()
    },
  }
}

function cloudCapture(interviewId: string, events: CaptureEvents): Capture {
  const context = new AudioContext()
  let stream: MediaStream | undefined
  let node: AudioWorkletNode | undefined
  let source: MediaStreamAudioSourceNode | undefined
  let socket: WebSocket | undefined
  let connectionTimeout: number | undefined
  let closed = false
  let ready = false
  let stopping = false

  function stopTracks() {
    stream?.getTracks().forEach(track => track.stop())
  }

  function abort() {
    if (closed) return
    closed = true
    window.clearTimeout(connectionTimeout)
    stopTracks()
    source?.disconnect()
    node?.disconnect()
    node?.port.close()
    socket?.close()
    void context.close().catch(() => undefined)
  }

  function sendAudio(payload: ArrayBuffer) {
    if (socket?.readyState !== WebSocket.OPEN) return
    if (socket.bufferedAmount > 128_000) {
      events.fail('录音上传过慢，已识别的文字会保留。')
      return
    }
    socket.send(payload)
  }

  async function connect() {
    try {
      const media = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } })
      if (closed) {
        media.getTracks().forEach(track => track.stop())
        return
      }
      stream = media
      await context.resume()
      if (closed) return
      if (context.sampleRate < 16000) throw new Error('不支持的麦克风采样率')
      await context.audioWorklet.addModule(pcmWorkletUrl)
      if (closed) return
      source = context.createMediaStreamSource(stream)
      node = new AudioWorkletNode(context, 'voice-pcm', { channelCount: 1, channelCountMode: 'explicit' })
      node.port.onmessage = ({ data }) => {
        if (closed) return
        if (data.audio instanceof ArrayBuffer) {
          sendAudio(data.audio)
          if (!stopping) events.volume(data.volume)
        }
        if (data.finished && socket?.readyState === WebSocket.OPEN) socket.send('finish')
      }
      const origin = window.location.origin.replace(/^http/, 'ws')
      socket = new WebSocket(origin + '/api/voice/asr?session=' + encodeURIComponent(interviewId))
      connectionTimeout = window.setTimeout(() => events.fail('云端转写连接超时，可以切换浏览器转写。'), 12_000)
      socket.onmessage = ({ data }) => {
        if (closed || typeof data !== 'string') return
        let message: { type?: string; text?: string; complete?: boolean }
        try { message = JSON.parse(data) } catch { return }
        if (message.type === 'ready' && !ready && source && node) {
          ready = true
          window.clearTimeout(connectionTimeout)
          source.connect(node)
          // The worklet writes no output, so the captured microphone isn't played on the speakers.
          node.connect(context.destination)
          events.ready()
        } else if (message.type === 'transcript' && typeof message.text === 'string') {
          events.result(message.complete ? message.text : '', message.complete ? '' : message.text)
        } else if (message.type === 'done') {
          events.end()
        } else if (message.type === 'error') {
          events.fail(message.text || '云端转写已中断，已识别的文字会保留。')
        }
      }
      socket.onerror = socket.onclose = () => {
        if (!closed) events.fail('云端转写连接已断开，已识别的文字会保留。')
      }
    } catch (failure) {
      if (closed) return
      const reason = failure instanceof DOMException ? failure.name : ''
      if (reason === 'NotAllowedError' || reason === 'SecurityError') events.fail(browserErrors['not-allowed'])
      else if (reason === 'NotFoundError' || reason === 'NotReadableError') events.fail(browserErrors['audio-capture'])
      else events.fail('无法启动云端录音，可以切换浏览器转写或直接输入。')
    }
  }

  void connect()
  return {
    stop() {
      stopping = true
      stopTracks()
      if (ready) node?.port.postMessage('flush')
      else events.end()
    },
    abort,
  }
}

export function useVoiceCapture(append: (text: string) => void) {
  const state = ref<CaptureState>('idle')
  const interim = ref('')
  const message = ref('')
  const volume = ref(0)
  let run: CaptureRun | undefined

  function release(next: CaptureState = 'idle') {
    const active = run
    run = undefined
    window.clearTimeout(active?.timeout)
    try { active?.capture?.abort() } catch { /* A browser recognizer may already have stopped. */ }
    state.value = next
    interim.value = ''
    volume.value = 0
  }

  function stop() {
    const active = run
    if (!active || state.value === 'stopping') return
    if (state.value === 'connecting') {
      release()
      return
    }
    state.value = 'stopping'
    volume.value = 0
    window.clearTimeout(active.timeout)
    active.timeout = window.setTimeout(() => {
      message.value = '转写等待超时，已识别的文字会保留。'
      release('error')
    }, 5_000)
    try { active.capture?.stop() } catch { release() }
  }

  function start(method: CaptureMethod, interviewId: string) {
    if (run) return
    message.value = ''
    state.value = 'connecting'
    const active: CaptureRun = {}
    run = active
    const ownsRun = () => run === active
    const callbacks: CaptureEvents = {
      ready() {
        if (!ownsRun() || state.value !== 'connecting') return
        state.value = 'listening'
        window.clearTimeout(active.timeout)
        active.timeout = window.setTimeout(stop, 120_000)
      },
      result(final, partial) {
        if (!ownsRun()) return
        interim.value = partial
        if (final) append(final)
      },
      volume(level) { if (ownsRun()) volume.value = level },
      end() { if (ownsRun()) release() },
      fail(reason) {
        if (!ownsRun()) return
        message.value = reason
        release('error')
      },
    }
    try {
      active.capture = method === 'cloud' ? cloudCapture(interviewId, callbacks) : browserCapture(callbacks)
      if (!ownsRun()) active.capture.abort()
      if (method === 'browser' && ownsRun() && state.value === 'connecting') {
        active.timeout = window.setTimeout(() => callbacks.fail('浏览器转写启动超时，可以重新录音。'), 20_000)
      }
    } catch {
      callbacks.fail('无法开启麦克风，可以直接输入回答。')
    }
  }

  return { state, interim, message, volume, start, stop, release }
}
