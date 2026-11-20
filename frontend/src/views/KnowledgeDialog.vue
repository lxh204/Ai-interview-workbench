<script setup lang="ts">
import { onMounted, ref, useId } from 'vue'

const props = defineProps<{ title: string; busy?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const heading = useId()

onMounted(() => dialog.value?.showModal())

function close() {
  if (!props.busy) emit('close')
}
</script>

<template>
  <dialog ref="dialog" class="knowledge-dialog" :aria-labelledby="heading" @cancel.prevent="close" @click.self="close">
    <div class="knowledge-dialog-inner">
      <header class="knowledge-row">
        <h2 :id="heading">{{ title }}</h2>
        <button class="button small secondary" type="button" aria-label="关闭" :disabled="busy" @click="close">关闭</button>
      </header>
      <slot />
    </div>
  </dialog>
</template>
