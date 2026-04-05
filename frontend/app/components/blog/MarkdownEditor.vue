<script setup lang="ts">
const props = defineProps<{
  modelValue: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const modelValueRef = computed(() => props.modelValue)

const {
  textareaRef,
  previewPaneRef,
  headings,
  jumpToHeading,
  charCount,
  lineCount,
  previewHtml,
  wrap,
  insertLinePrefix,
  insertBlock,
  insertTocBlock,
  hasTocBlock,
  generateHeadingsFromToc,
  onKeydown,
} = useMarkdownEditor(modelValueRef, (_, v) => emit('update:modelValue', v))

const showPreview = ref(false)
const showToc = ref(true)
const showHelp = ref(false)
const isDesktop = useMediaQuery('(min-width: 1024px)')

interface ToolbarBtn {
  emoji?: string
  icon?: string
  label?: string
  title: string
  action: () => void
  color: string
  dividerAfter?: boolean
}

const toolbarButtons: ToolbarBtn[] = [
  {
    label: 'B',
    title: '太字 (Ctrl+B)',
    color: 'text-green-700',
    action: () => wrap('**', '**', '太字テキスト'),
  },
  {
    label: 'I',
    title: '斜体 (Ctrl+I)',
    color: 'text-emerald-600',
    action: () => wrap('*', '*', '斜体テキスト'),
  },
  {
    label: 'S',
    title: '打ち消し線',
    color: 'text-teal-500',
    action: () => wrap('~~', '~~', '打ち消しテキスト'),
    dividerAfter: true,
  },
  { label: 'H1', title: '見出し1', color: 'text-green-800', action: () => insertLinePrefix('# ') },
  { label: 'H2', title: '見出し2', color: 'text-green-700', action: () => insertLinePrefix('## ') },
  {
    label: 'H3',
    title: '見出し3',
    color: 'text-green-600',
    action: () => insertLinePrefix('### '),
    dividerAfter: true,
  },
  {
    icon: 'pi pi-list',
    label: '',
    title: '箇条書き',
    color: 'text-teal-600',
    action: () => insertLinePrefix('- '),
  },
  {
    emoji: '1.',
    title: '番号付きリスト',
    color: 'text-teal-700',
    action: () => insertLinePrefix('1. '),
  },
  {
    emoji: '☑',
    title: 'チェックリスト',
    color: 'text-green-600',
    action: () => insertLinePrefix('- [ ] '),
    dividerAfter: true,
  },
  {
    icon: 'pi pi-code',
    label: '',
    title: 'インラインコード',
    color: 'text-amber-600',
    action: () => wrap('`', '`', 'コード'),
  },
  {
    label: '```',
    title: 'コードブロック',
    color: 'text-amber-700',
    action: () => insertBlock('```\nコードをここに\n```\n'),
    dividerAfter: true,
  },
  { label: '❝', title: '引用', color: 'text-lime-600', action: () => insertLinePrefix('> ') },
  {
    icon: 'pi pi-link',
    label: '',
    title: 'リンク',
    color: 'text-sky-500',
    action: () => wrap('[', '](https://)', 'リンクテキスト'),
  },
  {
    icon: 'pi pi-image',
    label: '',
    title: '画像',
    color: 'text-blue-500',
    action: () => wrap('![', '](https://画像のURL)', '代替テキスト'),
  },
  {
    emoji: '⊞',
    title: '表を挿入',
    color: 'text-indigo-500',
    action: () => insertBlock('| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n| セル | セル | セル |\n'),
  },
  {
    label: '—',
    title: '区切り線',
    color: 'text-surface-400',
    action: () => insertBlock('\n---\n'),
    dividerAfter: true,
  },
  {
    label: '☰',
    title: '目次ブロックを挿入（現在の見出しから自動生成）',
    color: 'text-green-700',
    action: () => insertTocBlock(),
  },
]

const previewComponentRef = ref<{ rootRef: HTMLElement | null } | null>(null)

watch(
  () => previewComponentRef.value?.rootRef,
  (el) => {
    previewPaneRef.value = el ?? null
  },
)

function handleJumpToHeading(heading: { level: number; text: string; lineIndex: number; id: string }) {
  jumpToHeading(heading, showPreview)
}
</script>

<template>
  <div class="editor-root flex flex-col overflow-hidden rounded-2xl shadow-lg">
    <MarkdownEditorToolbar
      :buttons="toolbarButtons"
      :show-toc="showToc"
      :show-preview="showPreview"
      :show-help="showHelp"
      :has-toc-block="hasTocBlock"
      :char-count="charCount"
      :line-count="lineCount"
      @update:show-toc="showToc = $event"
      @update:show-preview="showPreview = $event"
      @update:show-help="showHelp = $event"
      @generate-headings="generateHeadingsFromToc"
    />

    <div
      class="body-area flex flex-1 overflow-hidden"
      :class="isDesktop && showPreview ? 'split-mode' : ''"
    >
      <MarkdownEditorToc
        :headings="headings"
        :show-toc="showToc"
        @update:show-toc="showToc = $event"
        @jump-to-heading="handleJumpToHeading"
      />

      <div
        class="editor-pane flex flex-1 flex-col transition-all duration-300"
        :class="{ 'pane-hidden': !isDesktop && showPreview }"
      >
        <textarea
          ref="textareaRef"
          :value="modelValue"
          placeholder="✍️ ここから書き始めよう！

# 見出しはシャープ (#) で
**太字** や *斜体* も使えます
- リストはハイフンで
```
コードブロックはバッククォート3つ
```"
          class="editor-textarea flex-1 resize-none p-5 font-mono text-sm leading-relaxed outline-none"
          style="min-height: 480px"
          @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
          @keydown="onKeydown"
        />
      </div>

      <Transition name="preview-slide">
        <MarkdownEditorPreview
          v-show="showPreview"
          ref="previewComponentRef"
          :html="previewHtml"
          :is-desktop="isDesktop"
          @close="showPreview = false"
        />
      </Transition>
    </div>

    <div class="footer px-3 py-1.5 text-right text-xs text-surface-400 lg:hidden">
      {{ charCount }} 文字
    </div>

    <MarkdownEditorHelp :show-help="showHelp" @update:show-help="showHelp = $event" />
  </div>
</template>

<style scoped>
.editor-root {
  background: #ffffff;
  border: 1.5px solid #bbf7d0;
}
:global(.dark) .editor-root {
  background: #0f1f14;
  border-color: #166534;
}

.body-area {
  position: relative;
}

.editor-textarea {
  background: #fefffe;
  color: #1a2e1e;
  caret-color: #16a34a;
}
:global(.dark) .editor-textarea {
  background: #0a160d;
  color: #d1fae5;
}
.editor-textarea::placeholder {
  color: #86efac;
  font-style: italic;
}
.editor-textarea:focus {
  background: #fffffe;
}

.pane-hidden {
  display: none;
}

.preview-slide-enter-active,
.preview-slide-leave-active {
  transition: all 0.28s cubic-bezier(0.4, 0, 0.2, 1);
}
.preview-slide-enter-from,
.preview-slide-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

.footer {
  background: #f0fdf4;
  border-top: 1px solid #bbf7d0;
}
</style>
