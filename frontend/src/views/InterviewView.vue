<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, api, errorMessage, json, roles, type Interview, type Role } from '../api'
import { interviewExamples } from '../examples'

const storageKey = 'houchang.interview'
const role = ref<Role>('java')
const answer = ref('')
const session = ref<Interview>()
const savedId = ref<string>()
const pending = ref(false)
const error = ref('')
const abandonPrompt = ref(false)
const completedCount = computed(() => session.value?.answers.length ?? 0)

function remember(id?: string) {
  savedId.value = id
  try {
    if (id) localStorage.setItem(storageKey, id)
    else localStorage.removeItem(storageKey)
  } catch {
    // The session remains available for this visit when storage is disabled.
  }
}

async function restore(id: string) {
  pending.value = true
  error.value = ''
  try {
    session.value = await api<Interview>('/interviews/' + encodeURIComponent(id))
    role.value = session.value.role
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) {
      session.value = undefined
      remember()
      error.value = '这轮面试已不可用，可以开始新一轮。'
    } else {
      error.value = errorMessage(failure)
    }
  } finally {
    pending.value = false
  }
}

onMounted(() => {
  try {
    const id = localStorage.getItem(storageKey)
    if (id) {
      savedId.value = id
      void restore(id)
    }
  } catch {
    // Storage is optional. The start button remains usable.
  }
})

async function start() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  try {
    session.value = await api<Interview>('/interviews', json({ role: role.value }))
    remember(session.value.id)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    pending.value = false
  }
}

function clearSession() {
  session.value = undefined
  answer.value = ''
  error.value = ''
  abandonPrompt.value = false
  remember()
}

async function submitAnswer() {
  const active = session.value
  if (!active || active.completed || !answer.value.trim() || pending.value) return
  pending.value = true
  error.value = ''
  try {
    session.value = await api<Interview>(
      '/interviews/' + encodeURIComponent(active.id) + '/answers',
      json({ index: active.index, answer: answer.value.trim() }),
    )
    answer.value = ''
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 409) {
      await restore(active.id)
      if (session.value?.index !== active.index || session.value?.completed) answer.value = ''
      if (!error.value) error.value = '面试进度已更新，请查看当前题目与反馈。'
    } else {
      error.value = errorMessage(failure)
    }
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <div class="panel-grid">
    <section class="panel" aria-labelledby="interview-heading" :aria-busy="pending">
      <header class="panel-heading">
        <h2 id="interview-heading">{{ session ? '本轮面试' : '开始面试' }}</h2>
        <span v-if="session" class="chip">{{ roles[session.role] }}</span>
      </header>

      <form v-if="!session && !savedId" class="field-list" @submit.prevent="start">
        <fieldset class="role-options" :disabled="pending">
          <legend>选择练习方向</legend>
          <label class="role-option">
            <input v-model="role" type="radio" value="java" name="interview-role">
            <span>
              <strong>{{ roles.java }}</strong>
              <small>Java 基础、Spring、数据库与缓存</small>
            </span>
          </label>
          <label class="role-option">
            <input v-model="role" type="radio" value="ai" name="interview-role">
            <span>
              <strong>{{ roles.ai }}</strong>
              <small>模型调用、知识检索与结果评估</small>
            </span>
          </label>
        </fieldset>
        <button type="submit" class="button primary full-width" :disabled="pending">
          {{ pending ? '正在创建面试…' : '开始面试' }}
        </button>
      </form>

      <div v-else-if="!session && savedId" class="field-list">
        <p v-if="pending" role="status">正在恢复面试进度…</p>
        <template v-else>
          <button type="button" class="button primary" @click="restore(savedId)">重试恢复</button>
          <button type="button" class="text-button" @click="clearSession">开始新一轮</button>
        </template>
      </div>

      <template v-else-if="session">
        <div class="progress-area">
          <label class="result-context" for="interview-progress">
            {{ session.completed ? '练习完成' : '作答进度' }}
            <span>{{ completedCount }} / {{ session.total }}</span>
          </label>
          <progress id="interview-progress" :max="session.total" :value="completedCount"></progress>
        </div>

        <div v-if="session.completed" class="interview-complete">
          <h3>本轮面试已完成</h3>
          <p>展开答题记录，回看你的回答和逐题反馈。</p>
          <button type="button" class="button primary" @click="clearSession">开始新一轮</button>
        </div>

        <form v-else-if="session.question" class="field-list" @submit.prevent="submitAnswer">
          <div class="question-card" aria-live="polite">
            <span>第 {{ session.index + 1 }} 题</span>
            <p>{{ session.question }}</p>
          </div>
          <label class="field">
            <span>你的回答</span>
            <textarea
              v-model="answer"
              :disabled="pending"
              rows="6"
              placeholder="可以先说结论，再用你理解的例子解释理由。"
            ></textarea>
          </label>
          <button
            v-if="!answer.trim()"
            type="button"
            class="text-button align-start"
            :disabled="pending"
            @click="answer = interviewExamples[session.role]"
          >填入练习示例</button>
          <button type="submit" class="button primary full-width" :disabled="!answer.trim() || pending">
            {{ pending ? '正在获取反馈…' : '提交回答' }}
          </button>
        </form>

        <div v-if="!session.completed" class="session-controls">
          <div v-if="abandonPrompt" class="confirmation">
            <p>结束当前练习并清除本机的恢复记录？</p>
            <button type="button" class="text-button" :disabled="pending" @click="clearSession">确认结束</button>
            <button type="button" class="text-button quiet" @click="abandonPrompt = false">取消</button>
          </div>
          <button v-else type="button" class="text-button quiet" :disabled="pending" @click="abandonPrompt = true">
            结束当前练习
          </button>
        </div>
      </template>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </section>

    <section class="panel output" aria-labelledby="history-heading">
      <header class="panel-heading">
        <h2 id="history-heading">答题记录</h2>
        <span v-if="completedCount" class="chip">{{ completedCount }} 道已答</span>
      </header>
      <ol v-if="session && completedCount" class="history-list">
        <li v-for="(item, index) in session.answers" :key="index">
          <details class="disclosure" :open="index === completedCount - 1">
            <summary>
              <span>第 {{ index + 1 }} 题</span>
              <span v-if="item.score !== null" class="chip">{{ item.score }} 分</span>
            </summary>
            <h3 class="history-question">{{ item.question }}</h3>
            <p class="result-context">你的回答</p>
            <p class="result-text">{{ item.answer }}</p>
            <p class="result-context">答题反馈</p>
            <p class="result-text">{{ item.feedback }}</p>
          </details>
        </li>
      </ol>
      <div v-else class="empty-state">
        <span class="empty-icon"><span class="glyph glyph-interview" aria-hidden="true"></span></span>
        <strong>暂无作答记录</strong>
        <p>每次提交后，回答和反馈都会保存在这里。</p>
      </div>
    </section>
  </div>
</template>
