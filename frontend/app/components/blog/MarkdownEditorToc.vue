<script setup lang="ts">
interface Heading {
  level: number
  text: string
  lineIndex: number
  id: string
}

defineProps<{
  headings: Heading[]
  showToc: boolean
}>()

defineEmits<{
  'update:showToc': [value: boolean]
  jumpToHeading: [heading: Heading]
}>()
</script>

<template>
  <Transition name="toc-slide">
    <div v-show="showToc" class="toc-pane flex flex-col overflow-hidden">
      <div class="toc-header flex items-center gap-2 px-3 py-2">
        <span class="h-1.5 w-1.5 rounded-full bg-green-500" />
        <span class="text-xs font-bold tracking-wide text-green-700">目次</span>
        <span class="ml-auto text-xs text-surface-400">{{ headings.length }}項目</span>
        <button
          type="button"
          class="toc-close-btn flex h-5 w-5 items-center justify-center rounded text-surface-400 transition-all hover:bg-green-100 hover:text-green-700"
          title="目次を閉じる"
          @click="$emit('update:showToc', false)"
        >
          <i class="pi pi-chevron-left text-xs" />
        </button>
      </div>

      <div class="flex-1 overflow-y-auto px-2 pb-4">
        <div v-if="headings.length === 0" class="toc-empty px-2 py-4 text-center">
          <div class="mb-2 text-xl">☰</div>
          <p class="mb-1 text-xs font-semibold text-green-700">まず目次を作りましょう！</p>
          <p class="text-xs leading-relaxed text-surface-400">
            <code class="rounded bg-green-50 px-1 text-green-700">#</code> 大見出し<br />
            <code class="rounded bg-green-50 px-1 text-green-700">##</code> 中見出し<br />
            <code class="rounded bg-green-50 px-1 text-green-700">###</code> 小見出し
          </p>
          <div
            class="mt-3 rounded-lg border border-dashed border-green-200 bg-green-50/60 p-2 text-left"
          >
            <p class="font-mono text-xs leading-relaxed text-green-600">
              # はじめに<br />
              ## 背景<br />
              ## 目的<br />
              # 本題<br />
              ## ポイント1<br />
              # まとめ
            </p>
          </div>
        </div>

        <nav v-else class="pt-1">
          <button
            v-for="h in headings"
            :key="h.lineIndex"
            type="button"
            class="toc-item flex w-full items-start gap-1.5 rounded-lg py-1.5 text-left text-xs transition-all"
            :style="{ paddingLeft: `${(h.level - 1) * 12 + 8}px`, paddingRight: '8px' }"
            :title="h.text"
            @click="$emit('jumpToHeading', h)"
          >
            <span class="mt-0.5 shrink-0 text-[0.5rem]">
              <span v-if="h.level === 1" class="text-green-600">●</span>
              <span v-else-if="h.level === 2" class="text-green-400">◆</span>
              <span v-else class="text-teal-400">▸</span>
            </span>
            <span
              class="truncate leading-relaxed"
              :class="{
                'font-semibold text-green-800': h.level === 1,
                'text-green-700': h.level === 2,
                'text-green-600': h.level === 3,
              }"
              >{{ h.text }}</span
            >
          </button>
        </nav>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.toc-pane {
  width: 210px;
  min-width: 210px;
  max-width: 210px;
  background: linear-gradient(180deg, #f0fdf4 0%, #f7fef9 100%);
  border-right: 1.5px solid #bbf7d0;
}
:global(.dark) .toc-pane {
  background: linear-gradient(180deg, #0f1f14 0%, #0a160d 100%);
  border-right-color: #166534;
}

.toc-header {
  background: rgba(22, 163, 74, 0.05);
  border-bottom: 1px dashed #bbf7d0;
}

.toc-close-btn {
  cursor: pointer;
}

.toc-item {
  cursor: pointer;
  font-size: 0.72rem;
  line-height: 1.5;
}
.toc-item:hover {
  background: rgba(22, 163, 74, 0.08);
}

.toc-empty {
  background: transparent;
}

.toc-slide-enter-active,
.toc-slide-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}
.toc-slide-enter-from,
.toc-slide-leave-to {
  width: 0;
  min-width: 0;
  opacity: 0;
}
</style>
