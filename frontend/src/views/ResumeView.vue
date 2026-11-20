<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, errorMessage, json, roles, type ResumeResult, type Role } from '../api'
import { resumeExample } from '../examples'

const method = ref<'text' | 'file'>('text')
const role = ref<Role>('java')
const text = ref('')
const file = ref<File>()
const fileInput = ref<HTMLInputElement>()
const result = ref<ResumeResult>()
const resultRole = ref<Role>()
const error = ref('')
const pending = ref(false)

const canAnalyze = computed(() => method.value === 'text' ? Boolean(text.value.trim()) : Boolean(file.value))

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const next = input.files?.[0]
  error.value = ''
  if (next && !/\.(pdf|txt|md)$/i.test(next.name)) {
    error.value = '请选择 PDF、TXT 或 Markdown 文件。'
    input.value = ''
    file.value = undefined
    return
  }
  file.value = next
}

function useSample() {
  method.value = 'text'
  text.value = resumeExample
  error.value = ''
}

async function analyze() {
  if (!canAnalyze.value || pending.value) return
  pending.value = true
  error.value = ''
  result.value = undefined
  try {
    if (method.value === 'text') {
      result.value = await api<ResumeResult>('/resumes/analyze', json({ text: text.value.trim(), role: role.value }))
    } else if (file.value) {
      const body = new FormData()
      body.set('file', file.value)
      body.set('role', role.value)
      result.value = await api<ResumeResult>('/resumes/upload', { method: 'POST', body })
    }
    resultRole.value = role.value
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <div class="panel-grid">
    <form class="panel" @submit.prevent="analyze">
      <header class="panel-heading">
        <h2>简历材料</h2>
        <button type="button" class="text-button" :disabled="pending" @click="useSample">填入示例</button>
      </header>

      <fieldset :disabled="pending" class="field-list">
        <label class="field">
          <span>目标岗位</span>
          <select v-model="role" name="role">
            <option v-for="(name, value) in roles" :key="value" :value="value">{{ name }}</option>
          </select>
        </label>

        <div class="segmented" role="group" aria-label="简历录入方式">
          <button type="button" :aria-pressed="method === 'text'" @click="method = 'text'; error = ''">粘贴文本</button>
          <button type="button" :aria-pressed="method === 'file'" @click="method = 'file'; error = ''">上传文件</button>
        </div>

        <div v-show="method === 'text'" class="field">
          <label class="sr-only" for="resume-text">简历文本</label>
          <textarea
            id="resume-text"
            v-model="text"
            maxlength="30000"
            rows="9"
            placeholder="粘贴与目标岗位相关的项目经历和技能。"
          ></textarea>
          <small class="counter">{{ text.length.toLocaleString() }} / 30,000</small>
        </div>

        <div v-show="method === 'file'" class="upload-zone">
          <span class="glyph glyph-resume" aria-hidden="true"></span>
          <strong>{{ file?.name ?? '选择一份简历' }}</strong>
          <small>支持 PDF、TXT、Markdown</small>
          <label class="button secondary upload-button">
            选择文件
            <input
              ref="fileInput"
              type="file"
              name="file"
              accept=".pdf,.txt,.md"
              aria-label="选择简历文件"
              @change="selectFile"
            >
          </label>
          <button
            v-if="file"
            type="button"
            class="text-button"
            @click="file = undefined; fileInput && (fileInput.value = '')"
          >移除文件</button>
        </div>
      </fieldset>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="button primary full-width" type="submit" :disabled="!canAnalyze || pending">
        {{ pending ? '正在诊断…' : '开始诊断' }}
      </button>
    </form>

    <section class="panel output" aria-labelledby="resume-result-heading" :aria-busy="pending">
      <header class="panel-heading">
        <h2 id="resume-result-heading">诊断报告</h2>
        <span v-if="result" class="chip">{{ result.mode === 'demo' ? '离线演示' : '模型反馈' }}</span>
      </header>

      <div v-if="result" class="result-stack" aria-live="polite">
        <div v-if="resultRole" class="result-context">{{ roles[resultRole] }}</div>
        <p class="result-text">{{ result.feedback }}</p>
        <details v-if="result.sampleText" class="disclosure">
          <summary>查看用于本次诊断的文本</summary>
          <p class="result-text">{{ result.sampleText }}</p>
        </details>
      </div>
      <div v-else class="empty-state" role="status">
        <span class="empty-icon"><span class="glyph glyph-resume" aria-hidden="true"></span></span>
        <strong>{{ pending ? '正在生成报告' : '等待诊断' }}</strong>
        <p>{{ pending ? '生成后会在这里显示反馈。' : '提交简历后，在这里查看诊断建议。' }}</p>
      </div>
    </section>
  </div>
</template>
