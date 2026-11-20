<script setup lang="ts">
import { computed, ref } from 'vue'
import { difficulties, interviewRoles, roleTopics, topicOptions, type Difficulty, type InterviewConfig, type InterviewKind, type InterviewRole, type Topic } from './interviewFlow'

const props = defineProps<{ kind: InterviewKind; pending: boolean }>()
const emit = defineEmits<{ start: [settings: InterviewConfig] }>()
const role = ref<InterviewRole>('java')
const topic = ref<Topic>('general')
const difficulty = ref<Difficulty>('junior')
const count = ref(3)
const jdText = ref('')
const jdReady = computed(() => topic.value !== 'jd' || jdText.value.trim().length >= 30)
const options = computed(() => roleTopics[role.value])

function selectRole(id: InterviewRole) {
  if (role.value !== id) topic.value = 'general'
  role.value = id
}

function submit() {
  if (!jdReady.value) return
  emit('start', { role: role.value, topic: topic.value, difficulty: difficulty.value, questionCount: count.value,
    kind: props.kind, jdText: topic.value === 'jd' ? jdText.value.trim() : undefined })
}
</script>

<template>
  <form class="setup" @submit.prevent="submit">
    <div class="setup-main">
      <section class="panel setup-panel">
        <div class="panel-heading"><h2>练习方式</h2></div>
        <div class="mode-options">
          <a href="#/interview-hub" class="setup-option" :class="{ selected: kind === 'text' }"
             :aria-current="kind === 'text' ? 'page' : undefined">
            <strong>文字面试</strong><small>逐题填写回答，随时回看反馈。</small>
          </a>
          <a href="#/voice-interview" class="setup-option" :class="{ selected: kind === 'voice' }"
             :aria-current="kind === 'voice' ? 'page' : undefined">
            <strong>语音面试</strong><small>读题、口述答题与转写校对。</small>
          </a>
        </div>
      </section>
      <section class="panel setup-panel">
        <div class="panel-heading"><h2>面试主题</h2></div>
        <fieldset class="setup-fieldset" :disabled="pending">
          <legend>岗位方向</legend>
          <div class="setup-roles">
            <button v-for="(label, id) in interviewRoles" :key="id" type="button" class="setup-option"
                    :aria-pressed="role === id" :class="{ selected: role === id }" @click="selectRole(id)">
              <strong>{{ label }}</strong>
            </button>
          </div>
        </fieldset>
        <fieldset class="setup-fieldset topic-fieldset" :disabled="pending">
          <legend>考察主题</legend>
          <div class="topic-options">
            <button v-for="id in options" :key="id" type="button" class="setup-option" :aria-pressed="topic === id"
                    :class="{ selected: topic === id }" @click="topic = id">
              <strong>{{ topicOptions[id].label }}</strong><small>{{ topicOptions[id].description }}</small>
            </button>
          </div>
        </fieldset>
        <label v-if="topic === 'jd'" class="field jd-field">
          <span>岗位描述 JD</span>
          <textarea v-model="jdText" rows="5" minlength="30" maxlength="3000" required :disabled="pending"
                    placeholder="粘贴岗位职责和任职要求，至少 30 字。"></textarea>
          <small>按岗位中的技术关键词匹配考察内容，适合练习举证和表达。</small>
        </label>
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
          <input v-model.number="count" type="number" required min="3" max="6" step="1" :disabled="pending">
        </label>
        <div class="result-context">{{ topicOptions[topic].label }} · {{ count }} 题</div>
        <button type="submit" class="button primary full-width" :disabled="pending || !jdReady">
          {{ pending ? '正在创建面试…' : kind === 'voice' ? '开始语音面试' : '开始面试' }}
        </button>
      </div>
    </section>
  </form>
</template>

<style scoped>
.setup { display: grid; grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr); gap: 18px; align-items: start; }
.setup-main { display: grid; gap: 18px; }
.setup-panel { padding: clamp(16px, 2vw, 24px); }
.setup-fieldset { padding: 0; margin: 0; border: 0; min-width: 0; }
.setup-fieldset > legend { margin-bottom: 9px; font-size: 14px; font-weight: 650; }
.mode-options, .topic-options, .setup-roles { display: grid; gap: 10px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.setup-roles { grid-template-columns: repeat(auto-fit, minmax(125px, 1fr)); }
.topic-options { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.topic-fieldset, .jd-field { margin-top: 18px; }
.setup-option {
  display: grid; min-height: 52px; align-content: start; gap: .35rem; padding: .8rem;
  text-align: left; text-decoration: none; border-radius: 10px; color: var(--ink);
  border: 1px solid var(--line); background: var(--surface); font: inherit;
}
.setup-option.selected { background: var(--accent-soft); border-color: var(--accent); }
.setup-option small { color: var(--muted); line-height: 1.5; }
.setup-config select, .setup-config input { width: 100%; }
@media (max-width: 850px) { .setup { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 480px) { .topic-options { grid-template-columns: minmax(0, 1fr); } }
</style>
