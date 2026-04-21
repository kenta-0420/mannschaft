<script setup lang="ts">
import type { QaItem } from '~/composables/useQaSearch'

interface Props {
  items: QaItem[]
  searchQuery?: string
  highlightFn?: (text: string) => string
  openDefault?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  searchQuery: '',
  highlightFn: undefined,
  openDefault: false,
})

const openItems = ref<Record<string, boolean>>({})

function toggle(id: string) {
  openItems.value[id] = !openItems.value[id]
}

// 親コンポーネントから特定の項目を開くために公開する
function openItem(id: string) {
  openItems.value[id] = true
}

defineExpose({ openItem })

// URLハッシュ（#qa-xxx）で指定された項目を自動展開＋スクロール
onMounted(() => {
  if (!props.openDefault) return
  if (typeof window === 'undefined') return

  const hash = window.location.hash
  if (!hash || !hash.startsWith('#qa-')) return

  const targetId = hash.slice(4) // "#qa-" を除去
  const exists = props.items.some((item) => item.id === targetId)
  if (!exists) return

  openItems.value[targetId] = true

  // DOM 反映後にスクロール
  nextTick(() => {
    const element = document.getElementById(`qa-${targetId}`)
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  })
})
</script>

<template>
  <div class="space-y-2">
    <div
      v-for="item in items"
      :id="`qa-${item.id}`"
      :key="item.id"
      class="overflow-hidden rounded-xl border border-surface-200 bg-white dark:border-surface-700 dark:bg-surface-800"
    >
      <button
        :id="`qa-button-${item.id}`"
        type="button"
        :aria-expanded="openItems[item.id] ? 'true' : 'false'"
        :aria-controls="`qa-panel-${item.id}`"
        class="flex w-full items-center justify-between gap-4 px-5 py-4 text-left font-semibold text-surface-800 transition-colors hover:bg-surface-50 dark:text-white dark:hover:bg-surface-700"
        @click="toggle(item.id)"
      >
        <span class="flex-1">
          <!-- highlightFn はXSSエスケープ済みHTMLを返すため v-html で安全に描画可能 -->
          <span v-if="highlightFn" v-html="highlightFn(item.question)" />
          <span v-else>{{ item.question }}</span>
        </span>
        <i
          :class="openItems[item.id] ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
          class="shrink-0 text-sm text-surface-400"
        />
      </button>
      <Transition name="qa">
        <div
          v-if="openItems[item.id]"
          :id="`qa-panel-${item.id}`"
          role="region"
          :aria-labelledby="`qa-button-${item.id}`"
          class="qa-answer border-t border-surface-100 px-5 pb-5 pt-4 text-sm leading-relaxed text-surface-600 dark:border-surface-700 dark:text-surface-300"
        >
          <span v-if="highlightFn" v-html="highlightFn(item.answer)" />
          <span v-else>{{ item.answer }}</span>
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.qa-enter-active,
.qa-leave-active {
  transition: all 0.25s ease;
  max-height: 500px;
  overflow: hidden;
}
.qa-enter-from,
.qa-leave-to {
  max-height: 0;
  opacity: 0;
  padding-top: 0;
  padding-bottom: 0;
}

/* 改行を含む回答文を見やすく表示 */
.qa-answer {
  white-space: pre-wrap;
}

/* ハイライト用 <mark> のスタイル調整（highlightFn が描画） */
.qa-answer :deep(mark),
button :deep(mark) {
  background-color: #fef08a;
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}
:global(.dark) .qa-answer :deep(mark),
:global(.dark) button :deep(mark) {
  background-color: #854d0e;
  color: #fef9c3;
}
</style>
