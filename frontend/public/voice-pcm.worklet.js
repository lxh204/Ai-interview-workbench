const outputRate = 16000
const samplesPerFrame = 1600

class VoicePcmProcessor extends AudioWorkletProcessor {
  position = 0
  frame = new ArrayBuffer(samplesPerFrame * 2)
  view = new DataView(this.frame)
  accumulated = 0
  count = 0
  phase = 0
  peak = 0
  stopped = false

  constructor() {
    super()
    this.port.onmessage = ({ data }) => {
      if (data === 'flush') {
        this.stopped = true
        if (this.position) this.sendFrame()
        this.port.postMessage({ finished: true })
      }
    }
  }

  sendFrame() {
    const audio = this.position === samplesPerFrame ? this.frame : this.frame.slice(0, this.position * 2)
    this.port.postMessage({ audio, volume: Math.min(100, Math.round(this.peak * 250)) }, [audio])
    this.frame = new ArrayBuffer(samplesPerFrame * 2)
    this.view = new DataView(this.frame)
    this.position = 0
    this.peak = 0
  }

  process(inputs) {
    const channel = inputs[0]?.[0]
    if (this.stopped || !channel) return true
    for (const sample of channel) {
      this.accumulated += sample
      this.count++
      this.phase += outputRate
      if (this.phase < sampleRate) continue
      this.phase -= sampleRate
      const amplitude = Math.max(-1, Math.min(1, this.accumulated / this.count))
      this.peak = Math.max(this.peak, Math.abs(amplitude))
      this.view.setInt16(this.position * 2, Math.round(amplitude * (amplitude < 0 ? 32768 : 32767)), true)
      this.accumulated = 0
      this.count = 0
      this.position++
      if (this.position === samplesPerFrame) this.sendFrame()
    }
    return true
  }
}

registerProcessor('voice-pcm', VoicePcmProcessor)
