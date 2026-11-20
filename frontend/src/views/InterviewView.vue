<script setup lang="ts">
import { computed, ref } from 'vue'
import InterviewAnswers from './InterviewAnswers.vue'
import InterviewSetup from './InterviewSetup.vue'
import { difficulties, interviewRoles, pathId, reportLink, sessionLink, topicOptions, useInterviewLocation, useInterviewSession } from './interviewFlow'

const location = useInterviewLocation()
const routeId = computed(() => pathId(location.value, '#/interview/session/'))
const { session, answer, error, pending, restore, start, submit, setState } = useInterviewSession(() => routeId.value)
const finishing = ref(false)

async function finish() {
  finishing.value = false
  await setState('finish')
}
</script>

<template>
  <header class="page-header">
    <h1>{{ routeId ? '文字面试' : '模拟面试' }}</h1>
    <div v-if="!routeId" class="page-actions">
      <a class="button secondary" href="#/interviews">面试记录</a>
      <a class="button secondary" href="#/knowledgebase-interview">知识库面试</a>
    </div>
  </header>
  <InterviewSetup v-if="!routeId" kind="text" :pending="pending" @start="start" />
  <section v-else-if="!session" class="panel field-list" :aria-busy="pending">
    <p v-if="pending" role="status">正在读取面试进度…</p>
    <div v-else class="session-actions">
      <button class="button secondary" type="button" @click="restore(routeId)">重试读取</button>
      <a class="button primary" href="#/interviews">面试记录</a>
    </div>
  </section>
  <div v-else class="panel-grid interview-grid">
    <section class="panel" aria-labelledby="interview-heading" :aria-busy="pending">
      <header class="panel-heading">
        <h2 id="interview-heading">{{ topicOptions[session.topic].label }}</h2>
        <span class="chip">{{ interviewRoles[session.role] }}</span>
      </header>
      <p class="result-context">{{ difficulties[session.difficulty] }}</p>
      <div class="progress-area">
        <label class="result-context" for="interview-progress">作答进度 <span>{{ session.index }} / {{ session.total }}</span></label>
        <progress id="interview-progress" :max="session.total" :value="session.index"></progress>
      </div>
      <div v-if="session.kind === 'voice'" class="empty-state">
        <strong>这是一场语音面试</strong>
        <a class="button primary" :href="sessionLink(session.id, session.kind)">进入语音面试</a>
      </div>
      <div v-else-if="session.completed" class="interview-complete">
        <h3>本轮面试已完成</h3>
        <a class="button primary" :href="reportLink(session.id)">查看面试报告</a>
      </div>
      <form v-else-if="session.question" class="field-list" @submit.prevent="submit">
        <div class="question-card" aria-live="polite">
          <span>第 {{ session.index + 1 }} 题</span>
          <p>{{ session.question }}</p>
        </div>
        <label class="field">
          <span>你的回答</span>
          <textarea v-model="answer" :disabled="pending" rows="7" maxlength="8000"
                    placeholder="先说结论，再用例子和验证方法说明理由。"></textarea>
        </label>
        <button type="submit" class="button primary full-width" :disabled="!answer.trim() || pending">
          {{ pending ? '正在获取反馈…' : '提交回答' }}
        </button>
      </form>
      <div v-if="!session.completed && session.kind === 'text'" class="session-actions">
        <a href="#/interviews" class="button secondary">稍后继续</a>
        <button v-if="!finishing" type="button" class="text-button" :disabled="pending" @click="finishing = true">提前交卷</button>
        <div v-else class="confirmation">
          <p>确认交卷后，可在报告中回顾已提交的内容。</p>
          <button type="button" class="text-button" :disabled="pending" @click="finish">确认交卷</button>
          <button type="button" class="text-button quiet" @click="finishing = false">取消</button>
        </div>
      </div>
    </section>
    <section class="panel output" aria-labelledby="interview-answers">
      <header class="panel-heading"><h2 id="interview-answers">答题记录</h2><span class="chip">{{ session.index }} 道已答</span></header>
      <InterviewAnswers :answers="session.answers" />
    </section>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
</template>

<style scoped>
.interview-grid { margin-top: 0; }
.page-actions, .session-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.session-actions { margin-top: 16px; }
.interview-complete h3 { margin-block: 0 12px; }
</style>
