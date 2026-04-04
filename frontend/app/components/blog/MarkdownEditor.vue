<script setup lang="ts">
import { marked } from 'marked'

const props = defineProps<{
  modelValue: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const previewPaneRef = ref<HTMLElement | null>(null)
const showPreview = ref(false)
const showToc = ref(true)
const showHelp = ref(false)
const isDesktop = useMediaQuery('(min-width: 1024px)')

// ──────────────── 目次 ────────────────
interface Heading {
  level: number
  text: string
  lineIndex: number
  id: string
}

const headings = computed<Heading[]>(() => {
  return props.modelValue
    .split('\n')
    .map((line, i) => {
      const m = line.match(/^(#{1,3})\s+(.+)/)
      if (!m) return null
      const text = m[2].trim()
      const id =
        'h-' +
        text
          .replace(/[^\w\u3040-\u9fff]+/g, '-')
          .toLowerCase()
          .replace(/^-|-$/g, '')
      return { level: m[1].length, text, lineIndex: i, id }
    })
    .filter((h): h is Heading => h !== null)
})

function jumpToHeading(heading: Heading) {
  if (showPreview.value && previewPaneRef.value) {
    const escaped = typeof CSS !== 'undefined' ? CSS.escape(heading.id) : heading.id
    const el = previewPaneRef.value.querySelector(`#${escaped}`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      return
    }
  }
  const el = textareaRef.value
  if (!el) return
  const lines = props.modelValue.split('\n')
  let pos = 0
  for (let i = 0; i < heading.lineIndex; i++) pos += lines[i].length + 1
  el.focus()
  el.setSelectionRange(pos, pos + lines[heading.lineIndex].length)
  const ratio = heading.lineIndex / Math.max(lines.length - 1, 1)
  el.scrollTop = ratio * el.scrollHeight
}

// ──────────────── 文字数・行数 ────────────────
const charCount = computed(() => props.modelValue.replace(/^\.$/m, '').length)
const lineCount = computed(() => (props.modelValue || '').split('\n').length)

// ──────────────── プレビュー HTML ────────────────
function addHeadingIds(html: string): string {
  return html.replace(/<(h[1-3])>(.+?)<\/h[1-3]>/gi, (_, tag, inner) => {
    const plain = inner.replace(/<[^>]+>/g, '')
    const id =
      'h-' +
      plain
        .replace(/[^\w\u3040-\u9fff]+/g, '-')
        .toLowerCase()
        .replace(/^-|-$/g, '')
    return `<${tag} id="${id}">${inner}</${tag}>`
  })
}

const previewHtml = computed(() => {
  const val = props.modelValue
  if (!val || val === '.') {
    return '<p class="empty-msg">✍️ まだ本文がありません。左のエディタで書いてみましょう！</p>'
  }
  return addHeadingIds(marked(val) as string)
})

// ──────────────── テキスト挿入ヘルパー ────────────────
function wrap(prefix: string, suffix: string, placeholder = 'テキスト') {
  const el = textareaRef.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = props.modelValue.substring(start, end) || placeholder
  const newVal =
    props.modelValue.substring(0, start) +
    prefix +
    selected +
    suffix +
    props.modelValue.substring(end)
  emit('update:modelValue', newVal)
  nextTick(() => {
    el.focus()
    el.setSelectionRange(start + prefix.length, start + prefix.length + selected.length)
  })
}

function insertLinePrefix(prefix: string) {
  const el = textareaRef.value
  if (!el) return
  const start = el.selectionStart
  const lineStart = props.modelValue.lastIndexOf('\n', start - 1) + 1
  const newVal =
    props.modelValue.substring(0, lineStart) + prefix + props.modelValue.substring(lineStart)
  emit('update:modelValue', newVal)
  nextTick(() => {
    el.focus()
    const pos = start + prefix.length
    el.setSelectionRange(pos, pos)
  })
}

function insertBlock(template: string) {
  const el = textareaRef.value
  if (!el) return
  const start = el.selectionStart
  const before = props.modelValue.substring(0, start)
  const after = props.modelValue.substring(start)
  const sep = before.endsWith('\n') || before === '' ? '' : '\n'
  const newVal = before + sep + template + after
  emit('update:modelValue', newVal)
  nextTick(() => {
    el.focus()
    const pos = start + sep.length + template.length
    el.setSelectionRange(pos, pos)
  })
}

// ──────────────── 目次ブロック挿入 ────────────────
function insertTocBlock() {
  const items = headings.value
  let block: string

  if (items.length === 0) {
    block =
      '<details>\n<summary>目次</summary>\n\n- 見出しを追加すると自動で目次が入ります\n\n</details>\n'
  } else {
    const lines = items
      .map((h) => {
        const indent = '  '.repeat(h.level - 1)
        return `${indent}- [${h.text}](#${h.id})`
      })
      .join('\n')
    block = `<details>\n<summary>目次</summary>\n\n${lines}\n\n</details>\n`
  }

  insertBlock(block)
}

// ──────────────── 目次ブロック → 見出し生成 ────────────────
const hasTocBlock = computed(() => /<details>/i.test(props.modelValue))

function generateHeadingsFromToc() {
  const content = props.modelValue
  const detailsMatch = /<details>([\s\S]*?)<\/details>/i.exec(content)
  if (!detailsMatch) return

  const detailsEnd = detailsMatch.index + detailsMatch[0].length

  // ── Step1: 目次ブロックのリストをパース ──
  const tocItems: { level: number; text: string }[] = []
  for (const line of detailsMatch[1].split('\n')) {
    const m = line.match(/^(\s*)-\s+(.+)$/)
    if (!m) continue
    const text = m[2].replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').trim()
    const level = Math.min(Math.floor(m[1].length / 2) + 1, 3)
    if (text) tocItems.push({ level, text })
  }
  if (tocItems.length === 0) return

  // ── Step2: </details> 以降の既存セクションをパース ──
  // セクション = 見出し行 + その直下の本文（次の見出しまたは末尾まで）
  interface Section {
    headingLine: string
    key: string
    body: string
  }
  const afterDetails = content.substring(detailsEnd).replace(/^\n+/, '')
  const existingSections: Section[] = []
  const headingRe = /^(#{1,3} .+)$/gm
  let prev: { line: string; key: string; end: number } | null = null
  let m: RegExpExecArray | null

  while ((m = headingRe.exec(afterDetails)) !== null) {
    if (prev) {
      existingSections.push({
        headingLine: prev.line,
        key: prev.key,
        body: afterDetails.slice(prev.end, m.index),
      })
    }
    prev = { line: m[1], key: m[1].replace(/^#+\s+/, '').toLowerCase(), end: m.index + m[0].length }
  }
  if (prev) {
    existingSections.push({
      headingLine: prev.line,
      key: prev.key,
      body: afterDetails.slice(prev.end),
    })
  }

  // ── Step3: 目次の順に並べ直す（本文は見出し名で照合して保持） ──
  const used = new Set<number>()
  const rebuilt = tocItems.map(({ level, text }) => {
    const heading = '#'.repeat(level) + ' ' + text
    const idx = existingSections.findIndex((s, i) => !used.has(i) && s.key === text.toLowerCase())
    let body = '\n'
    if (idx >= 0) {
      used.add(idx)
      // 本文が空白のみなら空行を入れてスペースを確保
      body = existingSections[idx].body.trim() ? existingSections[idx].body : '\n'
    }
    return heading + '\n' + body.replace(/^\n/, '')
  })

  // 目次から消えたセクションは区切り線付きで末尾に退避
  const orphanSections = existingSections.filter(
    (_, i) => !used.has(i) && existingSections[i].body.trim(),
  )
  const orphans =
    orphanSections.length > 0
      ? [
          '---\n\n**── 削除(退避) ──**\n\n> 目次から削除されたセクションです。内容を確認して不要であれば削除してください。\n',
          ...orphanSections.map((s) => s.headingLine + '\n' + s.body.replace(/^\n/, '')),
        ]
      : []

  const newDoc = [...rebuilt, ...orphans]
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd()

  emit('update:modelValue', content.substring(0, detailsEnd) + '\n\n' + newDoc + '\n')
  nextTick(() => textareaRef.value?.focus())
}

// ──────────────── ツールバー定義 ────────────────
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

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
    e.preventDefault()
    wrap('**', '**', '太字テキスト')
  } else if ((e.ctrlKey || e.metaKey) && e.key === 'i') {
    e.preventDefault()
    wrap('*', '*', '斜体テキスト')
  }
}
</script>

<template>
  <div class="editor-root flex flex-col overflow-hidden rounded-2xl shadow-lg">
    <!-- ツールバー -->
    <div class="toolbar flex flex-wrap items-center gap-0.5 px-3 py-2">
      <!-- 目次トグル -->
      <button
        type="button"
        class="toc-toggle mr-1 flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs font-semibold transition-all"
        :class="showToc ? 'toc-toggle--active' : ''"
        title="目次を表示/非表示"
        @click="showToc = !showToc"
      >
        <i class="pi pi-list" />
        <span class="hidden sm:inline">目次</span>
      </button>
      <div class="divider mr-1.5 h-5 w-px rounded-full" />

      <template v-for="(btn, i) in toolbarButtons" :key="i">
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

      <!-- 右側ボタン群 -->
      <div class="ml-auto flex items-center gap-1.5">
        <span class="hidden text-xs text-surface-400 lg:block"
          >{{ charCount }} 文字 · {{ lineCount }} 行</span
        >

        <!-- 目次から構成生成ボタン -->
        <button
          v-if="hasTocBlock"
          type="button"
          class="gen-btn flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs font-semibold transition-all"
          title="目次ブロックのリストから見出しを生成"
          @click="generateHeadingsFromToc"
        >
          <i class="pi pi-bolt" />
          <span class="hidden sm:inline">構成を生成</span>
        </button>

        <!-- ヘルプボタン -->
        <button
          type="button"
          class="help-btn flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold transition-all"
          :class="showHelp ? 'help-btn--active' : ''"
          title="使い方ガイド"
          @click="showHelp = !showHelp"
        >
          ?
        </button>

        <!-- プレビュートグル -->
        <button
          type="button"
          class="preview-toggle flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold transition-all"
          :class="showPreview ? 'preview-toggle--active' : ''"
          :title="showPreview ? 'プレビューを閉じる' : 'プレビューを表示'"
          @click="showPreview = !showPreview"
        >
          <i :class="showPreview ? 'pi pi-times' : 'pi pi-eye'" />
          <span class="hidden sm:inline">{{ showPreview ? '閉じる' : 'プレビュー' }}</span>
        </button>
      </div>
    </div>

    <!-- エディタ本体 -->
    <div
      class="body-area flex flex-1 overflow-hidden"
      :class="isDesktop && showPreview ? 'split-mode' : ''"
    >
      <!-- 目次パネル -->
      <Transition name="toc-slide">
        <div v-show="showToc" class="toc-pane flex flex-col overflow-hidden">
          <!-- 目次ヘッダー（閉じるボタン付き） -->
          <div class="toc-header flex items-center gap-2 px-3 py-2">
            <span class="h-1.5 w-1.5 rounded-full bg-green-500" />
            <span class="text-xs font-bold tracking-wide text-green-700">目次</span>
            <span class="ml-auto text-xs text-surface-400">{{ headings.length }}項目</span>
            <button
              type="button"
              class="toc-close-btn flex h-5 w-5 items-center justify-center rounded text-surface-400 transition-all hover:bg-green-100 hover:text-green-700"
              title="目次を閉じる"
              @click="showToc = false"
            >
              <i class="pi pi-chevron-left text-xs" />
            </button>
          </div>

          <div class="flex-1 overflow-y-auto px-2 pb-4">
            <!-- 空の状態ガイド -->
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

            <!-- 目次リスト -->
            <nav v-else class="pt-1">
              <button
                v-for="h in headings"
                :key="h.lineIndex"
                type="button"
                class="toc-item flex w-full items-start gap-1.5 rounded-lg py-1.5 text-left text-xs transition-all"
                :style="{ paddingLeft: `${(h.level - 1) * 12 + 8}px`, paddingRight: '8px' }"
                :title="h.text"
                @click="jumpToHeading(h)"
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

      <!-- 編集ペイン -->
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
\`\`\`
コードブロックはバッククォート3つ
\`\`\`"
          class="editor-textarea flex-1 resize-none p-5 font-mono text-sm leading-relaxed outline-none"
          style="min-height: 480px"
          @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
          @keydown="onKeydown"
        />
      </div>

      <!-- プレビューペイン -->
      <Transition name="preview-slide">
        <div
          v-show="showPreview"
          ref="previewPaneRef"
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
              @click="showPreview = false"
            >
              <i class="pi pi-times" />
            </button>
          </div>
          <div class="preview-content px-5 pb-8" v-html="previewHtml" />
        </div>
      </Transition>
    </div>

    <!-- フッター (モバイルのみ) -->
    <div class="footer px-3 py-1.5 text-right text-xs text-surface-400 lg:hidden">
      {{ charCount }} 文字
    </div>

    <!-- ヘルプバックドロップ -->
    <Transition name="fade">
      <div v-if="showHelp" class="help-backdrop absolute inset-0 z-20" @click="showHelp = false" />
    </Transition>

    <!-- ヘルプパネル -->
    <Transition name="help-slide">
      <div
        v-if="showHelp"
        class="help-panel absolute inset-y-0 right-0 z-30 flex flex-col overflow-hidden"
      >
        <div class="help-header flex items-center justify-between px-4 py-3">
          <div class="flex items-center gap-2">
            <span
              class="flex h-5 w-5 items-center justify-center rounded-full bg-green-500 text-xs font-bold text-white"
              >?</span
            >
            <span class="text-sm font-bold text-green-800">使い方ガイド</span>
          </div>
          <button
            type="button"
            class="flex h-6 w-6 items-center justify-center rounded-full text-surface-400 transition-all hover:bg-green-100 hover:text-green-700"
            @click="showHelp = false"
          >
            <i class="pi pi-times text-xs" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto px-4 pb-6 text-xs leading-relaxed text-surface-700">
          <!-- 目次ファーストの書き方 -->
          <section class="help-section">
            <h4>☰ 目次ファーストで書く</h4>
            <ol class="help-ol">
              <li><strong>☰ ボタン</strong>を押して目次ブロックを挿入</li>
              <li>
                ブロック内のリストに見出しを書く<br />
                <code>- 大見出し（H1）</code><br />
                <code> - 中見出し（H2）</code><br />
                <code> - 小見出し（H3）</code>
              </li>
              <li><strong>⚡ 構成を生成</strong>ボタンを押すと見出しが自動挿入される</li>
              <li>各見出しの下に本文を書く</li>
              <li>目次ブロックを編集して再度押すと差分が反映される</li>
            </ol>
            <div class="help-example">
              <div class="help-example-label">入力例</div>
              <pre>
- はじめに
  - 背景
  - 目的
- 本題
- まとめ</pre
              >
              <div class="help-example-label mt-2">↓ 生成される構成</div>
              <pre>
# はじめに
## 背景
## 目的
# 本題
# まとめ</pre
              >
            </div>
          </section>

          <!-- ⚡ ボタン詳細 -->
          <section class="help-section">
            <h4>⚡ 構成を生成 — 押すたびに差分反映</h4>
            <p class="mb-2 text-surface-500">
              目次ブロックの内容を「正」として、ドキュメント側を自動更新します。
            </p>
            <table class="help-table">
              <thead>
                <tr>
                  <td style="font-weight: 700; color: #15803d">目次への操作</td>
                  <td style="font-weight: 700; color: #15803d">ドキュメントの変化</td>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>項目を追加</td>
                  <td>新しい見出しセクションが追加される</td>
                </tr>
                <tr>
                  <td>項目を削除</td>
                  <td>「削除(退避)」区切りの下に移動（本文は失われない）</td>
                </tr>
                <tr>
                  <td>順番を変更</td>
                  <td>見出し順が目次の順に組み替わる</td>
                </tr>
                <tr>
                  <td>変更なし</td>
                  <td>何も変わらない（何度押しても安全）</td>
                </tr>
              </tbody>
            </table>
            <p class="mt-2 text-surface-400">
              ※ 退避されたセクションは
              <code>── 削除(退避) ──</code>
              の区切り線の下にまとめて表示されます。内容を確認して不要なら削除してください。
            </p>
          </section>

          <!-- マークダウン早見表 -->
          <section class="help-section">
            <h4>✍️ マークダウン早見表</h4>
            <table class="help-table">
              <tr>
                <td><code>**テキスト**</code></td>
                <td><strong>太字</strong></td>
              </tr>
              <tr>
                <td><code>*テキスト*</code></td>
                <td><em>斜体</em></td>
              </tr>
              <tr>
                <td><code>~~テキスト~~</code></td>
                <td><del>打ち消し</del></td>
              </tr>
              <tr>
                <td><code># 見出し</code></td>
                <td>大見出し (H1)</td>
              </tr>
              <tr>
                <td><code>## 見出し</code></td>
                <td>中見出し (H2)</td>
              </tr>
              <tr>
                <td><code>### 見出し</code></td>
                <td>小見出し (H3)</td>
              </tr>
              <tr>
                <td><code>- 項目</code></td>
                <td>箇条書き</td>
              </tr>
              <tr>
                <td><code>1. 項目</code></td>
                <td>番号付き</td>
              </tr>
              <tr>
                <td><code>- [ ] タスク</code></td>
                <td>チェックボックス</td>
              </tr>
              <tr>
                <td><code>`コード`</code></td>
                <td>インラインコード</td>
              </tr>
              <tr>
                <td><code>> 引用</code></td>
                <td>引用ブロック</td>
              </tr>
              <tr>
                <td><code>[文字](URL)</code></td>
                <td>リンク</td>
              </tr>
              <tr>
                <td><code>![説明](URL)</code></td>
                <td>画像</td>
              </tr>
              <tr>
                <td><code>---</code></td>
                <td>区切り線</td>
              </tr>
            </table>
          </section>

          <!-- ツールバー説明 -->
          <section class="help-section">
            <h4>🛠 ツールバー</h4>
            <div class="space-y-1">
              <div class="help-row">
                <span class="help-badge">B I S</span>太字・斜体・打ち消し線
              </div>
              <div class="help-row"><span class="help-badge">H1 H2 H3</span>見出しを挿入</div>
              <div class="help-row"><span class="help-badge">リスト / 1. / ☑</span>各種リスト</div>
              <div class="help-row">
                <span class="help-badge">` / ```</span>コード（インライン / ブロック）
              </div>
              <div class="help-row"><span class="help-badge">❝</span>引用</div>
              <div class="help-row"><span class="help-badge">🔗 🖼</span>リンク・画像を挿入</div>
              <div class="help-row"><span class="help-badge">⊞</span>表を挿入</div>
              <div class="help-row">
                <span class="help-badge">☰</span>目次ブロックを挿入（現在の見出しから自動生成）
              </div>
              <div class="help-row">
                <span class="help-badge">⚡</span
                >目次ブロックから見出しを生成・更新（何度でも押せる）
              </div>
            </div>
          </section>

          <!-- ショートカット -->
          <section class="help-section">
            <h4>⌨️ キーボードショートカット</h4>
            <div class="space-y-1">
              <div class="help-row"><span class="help-badge">Ctrl+B</span>太字</div>
              <div class="help-row"><span class="help-badge">Ctrl+I</span>斜体</div>
              <div class="help-row">
                <span class="help-badge">Ctrl+S</span>保存（エディタ外で有効）
              </div>
            </div>
          </section>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* ──── エディタ全体 ──── */
.editor-root {
  background: #ffffff;
  border: 1.5px solid #bbf7d0;
}
:global(.dark) .editor-root {
  background: #0f1f14;
  border-color: #166534;
}

/* ──── ツールバー ──── */
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

/* ──── 目次トグルボタン ──── */
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

/* ──── プレビュートグル ──── */
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

/* ──── エディタ本体 ──── */
.body-area {
  position: relative;
}

/* ──── 目次パネル ──── */
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

/* ──── テキストエリア ──── */
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

/* ──── プレビューペイン ──── */
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

/* ──── プレビューコンテンツ ──── */
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

/* ──── 構成生成ボタン ──── */
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

/* ──── ヘルプボタン ──── */
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

/* ──── ヘルプパネル ──── */
.help-panel {
  width: 300px;
  background: #ffffff;
  border-left: 1.5px solid #bbf7d0;
  box-shadow: -4px 0 24px rgba(22, 163, 74, 0.1);
}
:global(.dark) .help-panel {
  background: #0f1f14;
  border-left-color: #166534;
}

.help-header {
  background: linear-gradient(135deg, #f0fdf4, #ecfdf5);
  border-bottom: 1px solid #bbf7d0;
  flex-shrink: 0;
}

.help-section {
  margin-top: 1.25rem;
}
.help-section h4 {
  font-size: 0.75rem;
  font-weight: 700;
  color: #15803d;
  margin-bottom: 0.5rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px dashed #bbf7d0;
}
.help-ol {
  padding-left: 1.1rem;
  margin: 0;
  color: #374151;
  line-height: 1.9;
}
.help-ol li {
  margin-bottom: 0.25rem;
}
.help-ol code {
  background: #dcfce7;
  color: #15803d;
  padding: 0.05em 0.3em;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-family: monospace;
}
.help-example {
  margin-top: 0.5rem;
  border: 1px solid #bbf7d0;
  border-radius: 0.5rem;
  overflow: hidden;
}
.help-example-label {
  background: #dcfce7;
  color: #166534;
  font-size: 0.65rem;
  font-weight: 600;
  padding: 0.2rem 0.5rem;
}
.help-example pre {
  margin: 0;
  padding: 0.4rem 0.6rem;
  background: #f7fef9;
  color: #166534;
  font-size: 0.72rem;
  font-family: monospace;
  line-height: 1.7;
  white-space: pre;
}
.help-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 0.25rem;
}
.help-table tr:nth-child(even) td {
  background: #f0fdf4;
}
.help-table td {
  padding: 0.25rem 0.4rem;
  font-size: 0.72rem;
  color: #374151;
}
.help-table td:first-child {
  font-family: monospace;
  color: #15803d;
  white-space: nowrap;
  width: 55%;
}
.help-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.2rem 0;
  font-size: 0.72rem;
  color: #374151;
}
.help-badge {
  background: #dcfce7;
  color: #15803d;
  border: 1px solid #86efac;
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
  font-size: 0.65rem;
  font-family: monospace;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}

/* ──── アニメーション ──── */
.preview-slide-enter-active,
.preview-slide-leave-active {
  transition: all 0.28s cubic-bezier(0.4, 0, 0.2, 1);
}
.preview-slide-enter-from,
.preview-slide-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

.help-backdrop {
  background: rgba(0, 0, 0, 0.15);
  cursor: pointer;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.help-slide-enter-active,
.help-slide-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.help-slide-enter-from,
.help-slide-leave-to {
  opacity: 0;
  transform: translateX(20px);
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

/* ──── フッター ──── */
.footer {
  background: #f0fdf4;
  border-top: 1px solid #bbf7d0;
}
</style>
