<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { difficulties, interviewRoles, roleTopics, topicOptions, type Difficulty, type InterviewConfig, type InterviewKind, type InterviewRole, type Topic } from './interviewFlow'

type ResumeOption = { id: string; filename: string; status: string }
type ModelOption = { id: string; name: string; chatModel: string; hasKey: boolean }
const props = defineProps<{ kind: InterviewKind; pending: boolean }>()
const emit = defineEmits<{ start: [settings: InterviewConfig] }>()
const role = ref<InterviewRole>('java')
const topic = ref<Topic>('general')
const difficulty = ref<Difficulty>('junior')
const count = ref(6)
const jdText = ref('')
const resumeId = ref('')
const providerId = ref('')
const followUps = ref(false)
const resumes = ref<ResumeOption[]>([])
const models = ref<ModelOption[]>([])
const extras = ref(false)
const available = ref(false)
const optionsIssue = ref(false)
const topics: Topic[] = ['agent', 'algorithm', 'ali', 'byte', 'frontend', 'general', 'tencent', 'python', 'system', 'testing', 'jd']
const extraTopics: Topic[] = ['spring', 'storage', 'rag', 'model']
const preferred: Partial<Record<Topic, InterviewRole>> = {
  general: 'java', agent: 'ai', ali: 'java', byte: 'java', frontend: 'frontend', tencent: 'java', python: 'python',
  testing: 'qa', spring: 'java', storage: 'java', rag: 'ai', model: 'ai',
}
const jdReady = computed(() => topic.value !== 'jd' || jdText.value.trim().length >= 30)
const validCount = computed(() => Number.isInteger(count.value) && count.value >= 3 && count.value <= 20)

onMounted(async () => {
  if (props.kind !== 'text') return
  const [resumeList, modelList] = await Promise.allSettled([
    api<ResumeOption[]>('/resume-reviews'), api<ModelOption[]>('/settings/models'),
  ])
  if (resumeList.status === 'fulfilled') {
    resumes.value = resumeList.value.filter(item => item.status === 'COMPLETED')
    const query = window.location.hash.split('?')[1] ?? window.location.search
    const requestedResume = new URLSearchParams(query).get('resumeId')
    if (requestedResume && resumes.value.some(item => item.id === requestedResume)) resumeId.value = requestedResume
  }
  if (modelList.status === 'fulfilled') models.value = modelList.value.filter(item => item.hasKey && item.chatModel)
  optionsIssue.value = resumeList.status === 'rejected' || modelList.status === 'rejected'
  available.value = true
})

function selectTopic(id: Topic) {
  const suggestedRole = preferred[id]
  if (suggestedRole) role.value = suggestedRole
  else if (!roleTopics[role.value].includes(id)) role.value = 'java'
  topic.value = id
}

function submit() {
  if (!jdReady.value || !validCount.value || props.pending) return
  const text = props.kind === 'text'
  emit('start', { role: role.value, topic: topic.value, difficulty: difficulty.value, questionCount: count.value,
    kind: props.kind, jdText: topic.value === 'jd' ? jdText.value.trim() : undefined,
    resumeId: text && resumeId.value ? resumeId.value : undefined, providerId: text && providerId.value ? providerId.value : undefined,
    followUps: text && followUps.value })
}
</script>

<template>
  <form class="setup" @submit.prevent="submit">
    <div class="setup-main">
      <section class="panel setup-panel">
        <div class="panel-heading"><h2>练习方式</h2></div>
        <div class="mode-options">
          <a href="#/interview-hub" class="setup-option mode-option" :class="{ selected: kind === 'text' }"
             :aria-current="kind === 'text' ? 'page' : undefined">
            <span class="glyph glyph-files" aria-hidden="true"></span>
            <span><strong>文字面试</strong><small>逐题作答，自动暂存并生成反馈。</small></span>
          </a>
          <a href="#/voice-interview" class="setup-option mode-option" :class="{ selected: kind === 'voice' }"
             :aria-current="kind === 'voice' ? 'page' : undefined">
            <span class="glyph glyph-chat" aria-hidden="true"></span>
            <span><strong>语音面试</strong><small>通过语音练习临场表达。</small></span>
          </a>
        </div>
      </section>
      <section class="panel setup-panel">
        <div class="panel-heading"><h2>面试主题</h2><small class="muted">{{ topics.length + (extras ? extraTopics.length : 0) }} 个主题</small></div>
        <fieldset class="setup-fieldset" :disabled="pending">
          <legend class="sr-only">选择面试主题</legend>
          <div class="topic-options">
            <button v-for="id in (extras ? [...topics, ...extraTopics] : topics)" :key="id" type="button" class="setup-option topic-option"
                    :aria-pressed="topic === id" :class="{ selected: topic === id }" @click="selectTopic(id)">
              <strong><span class="glyph glyph-sparkle" aria-hidden="true"></span>{{ topicOptions[id].label }}</strong>
              <small>{{ topicOptions[id].description }}</small>
            </button>
          </div>
          <button class="text-button more-topics" type="button" :aria-expanded="extras" @click="extras = !extras">
            {{ extras ? '收起专项主题' : '更多专项主题' }}
          </button>
        </fieldset>
        <div v-if="topic === 'jd'" class="field-list jd-field">
          <label class="field">
            <span>岗位方向</span>
            <select v-model="role" :disabled="pending">
              <option v-for="(label, id) in interviewRoles" :key="id" :value="id">{{ label }}</option>
            </select>
          </label>
          <label class="field">
            <span>岗位描述 JD</span>
            <textarea v-model="jdText" rows="5" minlength="30" maxlength="3000" required :disabled="pending"
                      placeholder="粘贴岗位职责和任职要求，至少 30 字。"></textarea>
            <small>按岗位中的技术关键词匹配考察内容。</small>
          </label>
        </div>
      </section>
    </div>
    <section class="panel setup-panel setup-config" :aria-busy="pending">
      <div class="panel-heading"><h2>本次配置</h2></div>
      <div class="field-list">
        <label class="field">
          <span>面试难度</span>
          <select v-model="difficulty" :disabled="pending">
            <option v-for="(label, value) in difficulties" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>
        <label class="field">
          <span>题目数量</span>
          <input v-model.number="count" type="number" required min="3" max="20" step="1" :disabled="pending">
        </label>
        <template v-if="kind === 'text'">
          <label class="field">
            <span>关联简历</span>
            <select v-model="resumeId" :disabled="pending || !available">
              <option value="">通用面试 · 不关联简历</option>
              <option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.filename }}</option>
            </select>
            <small>选择已分析的简历后，首题会结合其中提到的技能或项目经历。</small>
          </label>
          <label class="field">
            <span>模型服务</span>
            <select v-model="providerId" :disabled="pending || !available">
              <option value="">使用默认聊天服务</option>
              <option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }} · {{ model.chatModel }}</option>
            </select>
          </label>
          <label class="follow-up-option">
            <input v-model="followUps" type="checkbox" :disabled="pending">
            <span>加入练习追问</span>
          </label>
          <small v-if="followUps" class="muted">追问计入本次题量。</small>
          <p v-if="optionsIssue" class="error" role="status">部分配置读取失败，可使用默认选项开始。</p>
        </template>
        <div class="summary">
          <strong>{{ topicOptions[topic].label }}</strong><small>{{ count }} 道题 · 完成后查看报告</small>
        </div>
        <button type="submit" class="button primary full-width" :disabled="pending || !jdReady || !validCount">
          {{ pending ? '正在创建面试…' : kind === 'voice' ? '开始语音面试' : '开始文字面试' }}
        </button>
      </div>
    </section>
  </form>
</template>

<style scoped>
.setup { display: grid; grid-template-columns: minmax(0, 2.2fr) minmax(265px, .95fr); gap: 18px; align-items: start; }
.setup-main { display: grid; gap: 18px; }
.setup-panel { padding: clamp(16px, 2vw, 24px); }
.setup-fieldset { padding: 0; margin: 0; border: 0; min-width: 0; }
.mode-options, .topic-options { display: grid; gap: 11px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.jd-field { margin-top: 18px; }
.setup-option {
  display: grid; min-height: 82px; align-content: start; gap: .4rem; padding: .95rem 1rem;
  text-align: left; text-decoration: none; border-radius: 9px; color: var(--ink);
  border: 1px solid var(--line); background: var(--surface); font: inherit;
}
.setup-option.selected { background: var(--accent-soft); border-color: var(--accent); }
.setup-option small, .setup-config small, .summary small { color: var(--muted); line-height: 1.5; display: block; font-weight: 400; }
.mode-option { grid-template-columns: auto 1fr; align-items: center; align-content: center; gap: 12px; }
.mode-option small { margin-top: 4px; }
.topic-option strong { display: flex; align-items: center; gap: 9px; }
.setup-option .glyph { width: 18px; height: 18px; }
.setup-option.selected .glyph { color: var(--accent); }
.more-topics { margin-top: 10px; }
.setup-config select, .setup-config input[type=number] { width: 100%; }
.follow-up-option { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.summary { padding: 10px 13px; border-radius: 8px; background: var(--soft); border: 1px solid var(--line); }
.summary small { margin-top: 4px; }
@media (max-width: 850px) { .setup { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 480px) { .topic-options { grid-template-columns: minmax(0, 1fr); } }
</style>
