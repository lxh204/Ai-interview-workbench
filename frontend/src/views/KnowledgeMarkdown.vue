<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ text: string; sources?: number }>()
const emit = defineEmits<{ cite: [number: number] }>()

type Inline = { text: string; kind?: 'code' | 'strong' | 'em' | 'link' | 'citation'; href?: string; number?: number }
type Block = { kind: 'p' | 'h3' | 'h4' | 'h5' | 'ul' | 'ol' | 'pre' | 'blockquote' | 'hr'; lines: string[] }

const syntax = /(`[^`\n]{1,2048}`)|\*\*([^*\n]{1,500})\*\*|\*([^*\n]{1,500})\*|\[([^\]\n]{1,300})\]\(([^)\n]{1,2048})\)|\[(?:S)?([0-9]{1,6})\]/g
const heading = /^(#{1,6})\s+(.+)$/
const listItem = /^(?:[-*+]\s+|\d{1,5}\.\s+)(.*)$/
const ordered = /^\d{1,5}\.\s+/
const fence = '~~~'
const backtickFence = '\x60\x60\x60'

function safeUrl(raw: string) {
  try {
    const url = new URL(raw.trim())
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password ? url.href : ''
  } catch {
    return ''
  }
}

function inline(line: string): Inline[] {
  const segments: Inline[] = []
  let offset = 0
  for (const token of line.matchAll(syntax)) {
    const [raw, code, strong, emphasis, label, url, citation] = token
    const index = token.index ?? 0
    if (index > offset) segments.push({ text: line.slice(offset, index) })
    const href = url ? safeUrl(url) : ''
    const number = Number(citation)
    if (code) segments.push({ text: code.slice(1, -1), kind: 'code' })
    else if (strong) segments.push({ text: strong, kind: 'strong' })
    else if (emphasis) segments.push({ text: emphasis, kind: 'em' })
    else if (href && label) segments.push({ text: label, href, kind: 'link' })
    else if (number >= 1 && number <= (props.sources ?? 0)) segments.push({ text: raw, number, kind: 'citation' })
    else segments.push({ text: raw })
    offset = index + raw.length
  }
  if (offset < line.length) segments.push({ text: line.slice(offset) })
  return segments
}

const blocks = computed(() => {
  const result: Block[] = []
  let delimiter = ''
  for (const original of props.text.replace(/\r\n?/g, '\n').split('\n')) {
    const line = original.trim()
    const last = result.at(-1)
    if (delimiter) {
      if (line === delimiter) delimiter = ''
      else last?.lines.push(original)
      continue
    }
    if (line.startsWith(fence) || line.startsWith(backtickFence)) {
      delimiter = line.slice(0, 3)
      result.push({ kind: 'pre', lines: [] })
      continue
    }
    if (!line) {
      if (last?.lines.length && last.kind === 'p') last.lines.push('')
      continue
    }
    const title = heading.exec(line)
    const list = listItem.exec(line)
    if (title?.[1] && title[2]) {
      result.push({ kind: title[1].length === 1 ? 'h3' : title[1].length === 2 ? 'h4' : 'h5', lines: [title[2]] })
    } else if (list?.[1]) {
      const kind = ordered.test(line) ? 'ol' : 'ul'
      if (last?.kind === kind) last.lines.push(list[1])
      else result.push({ kind, lines: [list[1]] })
    } else if (line.startsWith('>')) {
      const quoted = line.slice(1).trimStart()
      if (last?.kind === 'blockquote') last.lines.push(quoted)
      else result.push({ kind: 'blockquote', lines: [quoted] })
    } else if (line === '---' || line === '***') {
      result.push({ kind: 'hr', lines: [] })
    } else if (last?.kind === 'p' && last.lines.at(-1) !== '') {
      last.lines.push(original)
    } else {
      result.push({ kind: 'p', lines: [original] })
    }
  }
  return result
})
</script>

<template>
  <div class="knowledge-markdown">
    <template v-for="(block, index) in blocks" :key="index">
      <pre v-if="block.kind === 'pre'"><code>{{ block.lines.join('\n') }}</code></pre>
      <hr v-else-if="block.kind === 'hr'">
      <component :is="block.kind" v-else>
        <component :is="block.kind === 'ul' || block.kind === 'ol' ? 'li' : 'span'" v-for="(line, lineIndex) in block.lines" :key="lineIndex">
          <template v-for="(part, partIndex) in inline(line)" :key="partIndex">
            <code v-if="part.kind === 'code'">{{ part.text }}</code>
            <strong v-else-if="part.kind === 'strong'">{{ part.text }}</strong>
            <em v-else-if="part.kind === 'em'">{{ part.text }}</em>
            <a v-else-if="part.kind === 'link'" :href="part.href" target="_blank" rel="noopener noreferrer">{{ part.text }}</a>
            <button v-else-if="part.kind === 'citation' && part.number" class="knowledge-citation" type="button"
              :aria-label="'查看引用 ' + part.number" @click="emit('cite', part.number)">{{ part.text }}</button>
            <template v-else>{{ part.text }}</template>
          </template>
        </component>
      </component>
    </template>
  </div>
</template>
