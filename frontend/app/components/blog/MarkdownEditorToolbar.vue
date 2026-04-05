<script setup lang="ts">
interface ToolbarBtn {
  emoji?: string
  icon?: string
  label?: string
  title: string
  action: () => void
  color: string
  dividerAfter?: boolean
}

defineProps<{
  buttons: ToolbarBtn[]
  showToc: boolean
  showPreview: boolean
  showHelp: boolean
  hasTocBlock: boolean
  charCount: number
  lineCount: number
}>()

defineEmits<{
  'update:showToc': [value: boolean]
  'update:showPreview': [value: boolean]
  'update:showHelp': [value: boolean]
  generateHeadings: []
}>()
</script>

<template>
  <div class="toolbar flex flex-wrap items-center gap-0.5 px-3 py-2">
    <button
      type="button"
      class="toc-toggle mr-1 flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs font-semibold transition-all"
      :class="showToc ? 'toc-toggle--active' : ''"
      title="目次を表示/非表示"
      @click="$emit('update:showToc', !showToc)"
    >
      <i class="pi pi-list" />
      <span class="hidden sm:inline">目次</span>
    </button>
    <div class="divider mr-1.5 h-5 w-px rounded-full" />

    <template v-for="(btn, i) in buttons" :key="i">
      <button
        type="button"
        :title="btn.title"
        class="toolbar-btn flex h-8 min-w-8 items-center justify-center rounded-lg px-2 text-sm font-bold transition-all"
        :class="btn.color"
        @click="btn.action"
      >
        <i v-if="btn.icon" :class="btn.icon" />
        <span v-else-if="btn.emoji">{{ btn.emoji }}</span>
        <span v-else class="font-mono">{{ btn.label }}</span>
      </button>
      <div v-if="btn.dividerAfter" class="divider mx-1.5 h-5 w-px rounded-full" />
    </template>

    <div class="ml-auto flex items-center gap-1.5">
      <span class="hidden text-xs text-surface-400 lg:block"
        >{{ charCount }} 文字 · {{ lineCount }} 行</span
      >

      <button
        v-if="hasTocBlock"
        type="button"
        class="gen-btn flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs font-semibold transition-all"
        title="目次ブロックのリストから見出しを生成"
        @click="$emit('generateHeadings')"
      >
        <i class="pi pi-bolt" />
        <span class="hidden sm:inline">構成を生成</span>
      </button>

      <button
        type="button"
        class="help-btn flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold transition-all"
        :class="showHelp ? 'help-btn--active' : ''"
        title="使い方ガイド"
        @click="$emit('update:showHelp', !showHelp)"
      >
        ?
      </button>

      <button
        type="button"
        class="preview-toggle flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold transition-all"
        :class="showPreview ? 'preview-toggle--active' : ''"
        :title="showPreview ? 'プレビューを閉じる' : 'プレビューを表示'"
        @click="$emit('update:showPreview', !showPreview)"
      >
        <i :class="showPreview ? 'pi pi-times' : 'pi pi-eye'" />
        <span class="hidden sm:inline">{{ showPreview ? '閉じる' : 'プレビュー' }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  background: linear-gradient(135deg, #f0fdf4 0%, #ecfdf5 50%, #f0fdf4 100%);
  border-bottom: 1.5px solid #bbf7d0;
}
:global(.dark) .toolbar {
  background: linear-gradient(135deg, #14291c 0%, #0f1f14 50%, #14291c 100%);
  border-bottom-color: #166534;
}

.toolbar-btn {
  background: transparent;
  cursor: pointer;
}
.toolbar-btn:hover {
  background: rgba(22, 163, 74, 0.08);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(22, 163, 74, 0.15);
}
.toolbar-btn:active {
  transform: translateY(0);
}

.divider {
  background: #bbf7d0;
}

.toc-toggle {
  background: rgba(22, 163, 74, 0.06);
  color: #15803d;
  border: 1px solid rgba(22, 163, 74, 0.2);
}
.toc-toggle:hover {
  background: rgba(22, 163, 74, 0.12);
}
.toc-toggle--active {
  background: linear-gradient(135deg, #16a34a, #15803d);
  color: white;
  border-color: transparent;
  box-shadow: 0 2px 8px rgba(22, 163, 74, 0.35);
}

.preview-toggle {
  background: rgba(22, 163, 74, 0.06);
  color: #15803d;
  border: 1px solid rgba(22, 163, 74, 0.2);
}
.preview-toggle:hover {
  background: rgba(22, 163, 74, 0.12);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(22, 163, 74, 0.2);
}
.preview-toggle--active {
  background: linear-gradient(135deg, #16a34a, #0d9488);
  color: white;
  border-color: transparent;
  box-shadow: 0 2px 12px rgba(22, 163, 74, 0.35);
}

.gen-btn {
  background: linear-gradient(135deg, #dcfce7, #d1fae5);
  color: #15803d;
  border: 1px solid #86efac;
}
.gen-btn:hover {
  background: linear-gradient(135deg, #bbf7d0, #a7f3d0);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(22, 163, 74, 0.25);
}

.help-btn {
  background: rgba(22, 163, 74, 0.08);
  color: #15803d;
  border: 1px solid rgba(22, 163, 74, 0.2);
  font-family: 'Georgia', serif;
}
.help-btn:hover {
  background: rgba(22, 163, 74, 0.15);
}
.help-btn--active {
  background: #16a34a;
  color: white;
  border-color: transparent;
}
</style>
