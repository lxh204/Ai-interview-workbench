<script setup lang="ts">
import type { InterviewAnswer } from '../api'

defineProps<{ answers: InterviewAnswer[]; expandAll?: boolean }>()
</script>

<template>
  <ol v-if="answers.length" class="history-list answers">
    <li v-for="(item, index) in answers" :key="index">
      <details class="disclosure" :open="expandAll || index === answers.length - 1">
        <summary><span>第 {{ index + 1 }} 题</span><span v-if="item.score !== null" class="chip">{{ item.score }} 分</span></summary>
        <h3 class="history-question">{{ item.question }}</h3>
        <p class="result-context">你的回答</p>
        <p class="result-text">{{ item.answer }}</p>
        <p class="result-context">答题反馈</p>
        <p class="result-text">{{ item.feedback }}</p>
      </details>
    </li>
  </ol>
  <div v-else class="empty-state">
    <strong>暂无作答记录</strong>
    <p>提交后可查看你的回答和逐题反馈。</p>
  </div>
</template>

<style scoped>
.answers { margin: 0; }
.result-text { overflow-wrap: anywhere; white-space: pre-wrap; }
</style>
