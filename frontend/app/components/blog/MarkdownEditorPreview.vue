<script setup lang="ts">
defineProps<{
  html: string
  isDesktop: boolean
}>()

defineEmits<{
  close: []
}>()

const rootRef = ref<HTMLElement | null>(null)

defineExpose({ rootRef })
</script>

<template>
  <div
    ref="rootRef"
    class="preview-pane overflow-auto"
    :class="isDesktop ? 'flex-1 border-l border-green-100' : 'absolute inset-0 z-10'"
  >
    <div class="preview-header flex items-center justify-between px-5 py-3">
      <div class="flex items-center gap-2">
        <span class="h-2 w-2 rounded-full bg-green-400" />
        <span class="text-xs font-semibold text-green-600">プレビュー</span>
      </div>
      <button
        type="button"
        class="close-btn flex h-6 w-6 items-center justify-center rounded-full text-xs text-surface-400 transition-all hover:bg-green-100 hover:text-green-700"
        title="プレビューを閉じる"
        @click="$emit('close')"
      >
        <i class="pi pi-times" />
      </button>
    </div>
    <!-- html は呼び出し元で marked + sanitizeHtml により sanitize 済みである前提 -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="preview-content px-5 pb-8" v-html="html" />
  </div>
</template>

<style scoped>
.preview-pane {
  background: linear-gradient(180deg, #f0fdf4 0%, #fefffe 100%);
  min-height: 480px;
}
:global(.dark) .preview-pane {
  background: linear-gradient(180deg, #0f1f14 0%, #0a160d 100%);
}

.preview-header {
  background: rgba(22, 163, 74, 0.04);
  border-bottom: 1px dashed #bbf7d0;
  position: sticky;
  top: 0;
  backdrop-filter: blur(8px);
}

.preview-content :deep(.empty-msg) {
  color: #86efac;
  font-style: italic;
  text-align: center;
  padding: 3rem 0;
}
.preview-content :deep(h1) {
  font-size: 1.7rem;
  font-weight: 800;
  margin: 1.5rem 0 0.75rem;
  background: linear-gradient(135deg, #15803d, #0d9488);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}
.preview-content :deep(h2) {
  font-size: 1.3rem;
  font-weight: 700;
  margin: 1.2rem 0 0.5rem;
  color: #166534;
  border-bottom: 2px solid #bbf7d0;
  padding-bottom: 0.3rem;
}
.preview-content :deep(h3) {
  font-size: 1.1rem;
  font-weight: 600;
  margin: 1rem 0 0.4rem;
  color: #16a34a;
}
.preview-content :deep(p) {
  margin-bottom: 0.9rem;
  line-height: 1.8;
  color: #1a2e1e;
}
.preview-content :deep(ul) {
  list-style: none;
  margin-bottom: 0.9rem;
  padding-left: 0.5rem;
}
.preview-content :deep(ul li) {
  padding: 0.15rem 0 0.15rem 1.4rem;
  position: relative;
}
.preview-content :deep(ul li::before) {
  content: '✦';
  position: absolute;
  left: 0;
  color: #4ade80;
  font-size: 0.65rem;
  top: 0.35rem;
}
.preview-content :deep(ol) {
  padding-left: 1.5rem;
  margin-bottom: 0.9rem;
}
.preview-content :deep(ol li) {
  padding: 0.15rem 0;
  line-height: 1.7;
  color: #1a2e1e;
}
.preview-content :deep(blockquote) {
  margin: 1rem 0;
  padding: 0.75rem 1rem;
  background: linear-gradient(135deg, #f0fdf4, #ecfdf5);
  border-left: 4px solid #4ade80;
  border-radius: 0 0.75rem 0.75rem 0;
  color: #15803d;
  font-style: italic;
}
.preview-content :deep(code:not(pre code)) {
  background: #dcfce7;
  color: #15803d;
  padding: 0.1em 0.4em;
  border-radius: 0.35rem;
  font-size: 0.85em;
  font-family: 'Fira Code', 'Consolas', monospace;
  font-weight: 500;
}
.preview-content :deep(pre) {
  background: #0f2d1a;
  border-radius: 0.75rem;
  padding: 1.25rem;
  margin: 1rem 0;
  overflow-x: auto;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
  position: relative;
}
.preview-content :deep(pre)::before {
  content: '';
  position: absolute;
  top: 0.75rem;
  left: 0.9rem;
  width: 0.55rem;
  height: 0.55rem;
  border-radius: 50%;
  background: #ff5f57;
  box-shadow:
    1rem 0 0 #febc2e,
    2rem 0 0 #28c840;
}
.preview-content :deep(pre code) {
  color: #86efac;
  font-size: 0.85rem;
  line-height: 1.7;
  font-family: 'Fira Code', 'Consolas', monospace;
}
.preview-content :deep(hr) {
  margin: 1.5rem 0;
  border: none;
  height: 2px;
  background: linear-gradient(90deg, transparent, #4ade80, transparent);
}
.preview-content :deep(a) {
  color: #15803d;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.preview-content :deep(strong) {
  font-weight: 700;
  color: #14532d;
}
.preview-content :deep(em) {
  font-style: italic;
  color: #166534;
}
.preview-content :deep(del) {
  text-decoration: line-through;
  color: #9ca3af;
}
.preview-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 1rem 0;
  font-size: 0.9rem;
}
.preview-content :deep(th) {
  background: linear-gradient(135deg, #dcfce7, #d1fae5);
  color: #166534;
  font-weight: 700;
  padding: 0.6rem 0.9rem;
  text-align: left;
  border-bottom: 2px solid #4ade80;
}
.preview-content :deep(td) {
  padding: 0.5rem 0.9rem;
  border-bottom: 1px solid #dcfce7;
  color: #1a2e1e;
}
.preview-content :deep(tr:hover td) {
  background: #f0fdf4;
}
.preview-content :deep(img) {
  max-width: 100%;
  border-radius: 0.75rem;
  margin: 0.75rem 0;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}
.preview-content :deep(input[type='checkbox']) {
  accent-color: #16a34a;
  margin-right: 0.4rem;
}
.preview-content :deep(details) {
  margin: 1rem 0;
  border: 1.5px solid #bbf7d0;
  border-radius: 0.75rem;
  overflow: hidden;
}
.preview-content :deep(details summary) {
  padding: 0.65rem 1rem;
  background: linear-gradient(135deg, #dcfce7, #d1fae5);
  color: #15803d;
  font-weight: 700;
  font-size: 0.95rem;
  cursor: pointer;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  user-select: none;
}
.preview-content :deep(details summary::-webkit-details-marker) {
  display: none;
}
.preview-content :deep(details summary::before) {
  content: '▶';
  font-size: 0.6rem;
  color: #16a34a;
  transition: transform 0.2s;
  display: inline-block;
}
.preview-content :deep(details[open] summary::before) {
  transform: rotate(90deg);
}
.preview-content :deep(details > *:not(summary)) {
  padding: 0.75rem 1.25rem;
  background: #f7fef9;
}
.preview-content :deep(details ul) {
  margin-bottom: 0;
}
.preview-content :deep(details ul li::before) {
  content: none;
}
.preview-content :deep(details ul li) {
  padding-left: 0;
  list-style: disc;
  color: #166534;
}
.preview-content :deep(details a) {
  color: #15803d;
  font-weight: 500;
}
</style>
