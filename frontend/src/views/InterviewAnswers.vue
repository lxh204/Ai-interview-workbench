<script setup lang="ts">
import { computed } from 'vue'
import type { SavedInterviewAnswer } from './interviewFlow'

const props = defineProps<{ answers: SavedInterviewAnswer[]; expandAll?: boolean; bookmarkedOnly?: boolean }>()
const visible = computed(() => props.answers.map((item, index) => ({ item, index }))
  .filter(({ item }) => !props.bookmarkedOnly || item.bookmarked))
</script>

<template>
  <ol v-if="visible.length" class="history-list answers">
    <li v-for="{ item, index } in visible" :key="index">
      <details class="disclosure" :open="expandAll || index === answers.length - 1">
        <summary>
          <span>第 {{ index + 1 }} 题</span>
          <span v-if="item.followUpOf != null" class="chip">追问</span>
          <span v-if="item.bookmarked" class="chip">已收藏</span>
          <span v-if="item.skipped" class="chip">已跳过</span>
          <span v-if="item.score !== null" class="chip">{{ item.score }} 分</span>
        </summary>
        <h3 class="history-question">{{ item.question }}</h3>
        <template v-if="!item.skipped">
          <p class="result-context">你的回答</p>
          <p class="result-text">{{ item.answer }}</p>
          <p class="result-context">答题反馈</p>
          <p class="result-text">{{ item.feedback }}</p>
        </template>
        <p v-else class="result-context">本题已跳过。</p>
      </details>
    </li>
  </ol>
  <div v-else class="empty-state">
    <strong>{{ bookmarkedOnly ? '暂无收藏题目' : '暂无作答记录' }}</strong>
    <p v-if="!bookmarkedOnly">提交后可查看你的回答和逐题反馈。</p>
  </div>
</template>

<style scoped>
.answers { margin: 0; }
.result-text { overflow-wrap: anywhere; white-space: pre-wrap; }
</style>
