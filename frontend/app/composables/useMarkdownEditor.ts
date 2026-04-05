import { marked } from 'marked'
import { sanitizeHtml } from '~/utils/sanitizeHtml'

interface Heading {
  level: number
  text: string
  lineIndex: number
  id: string
}

function toHeadingId(text: string): string {
  return (
    'h-' +
    text
      .replace(/[^\w\u3040-\u9fff]+/g, '-')
      .toLowerCase()
      .replace(/^-|-$/g, '')
  )
}

export function useMarkdownEditor(
  modelValue: Ref<string>,
  emit: (event: 'update:modelValue', value: string) => void,
) {
  const textareaRef = ref<HTMLTextAreaElement | null>(null)
  const previewPaneRef = ref<HTMLElement | null>(null)

  // ──────────────── 目次 ────────────────
  const headings = computed<Heading[]>(() => {
    return modelValue.value
      .split('\n')
      .map((line, i) => {
        const m = line.match(/^(#{1,3})\s+(.+)/)
        if (!m) return null
        const text = m[2]!.trim()
        return { level: m[1]!.length, text, lineIndex: i, id: toHeadingId(text) }
      })
      .filter((h): h is Heading => h !== null)
  })

  function jumpToHeading(heading: Heading, showPreview: Ref<boolean>) {
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
    const lines = modelValue.value.split('\n')
    let pos = 0
    for (let i = 0; i < heading.lineIndex; i++) pos += lines[i]!.length + 1
    el.focus()
    el.setSelectionRange(pos, pos + lines[heading.lineIndex]!.length)
    const ratio = heading.lineIndex / Math.max(lines.length - 1, 1)
    el.scrollTop = ratio * el.scrollHeight
  }

  // ──────────────── 文字数・行数 ────────────────
  const charCount = computed(() => modelValue.value.replace(/^\.$/m, '').length)
  const lineCount = computed(() => (modelValue.value || '').split('\n').length)

  // ──────────────── プレビュー HTML ────────────────
  function addHeadingIds(html: string): string {
    return html.replace(/<(h[1-3])>(.+?)<\/h[1-3]>/gi, (_, tag, inner) => {
      const plain = inner.replace(/<[^>]+>/g, '')
      return `<${tag} id="${toHeadingId(plain)}">${inner}</${tag}>`
    })
  }

  const previewHtml = computed(() => {
    const val = modelValue.value
    if (!val || val === '.') {
      return '<p class="empty-msg">✍️ まだ本文がありません。左のエディタで書いてみましょう！</p>'
    }
    return sanitizeHtml(addHeadingIds(marked(val) as string))
  })

  // ──────────────── テキスト挿入ヘルパー ────────────────
  function wrap(prefix: string, suffix: string, placeholder = 'テキスト') {
    const el = textareaRef.value
    if (!el) return
    const start = el.selectionStart
    const end = el.selectionEnd
    const selected = modelValue.value.substring(start, end) || placeholder
    const newVal =
      modelValue.value.substring(0, start) +
      prefix +
      selected +
      suffix +
      modelValue.value.substring(end)
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
    const lineStart = modelValue.value.lastIndexOf('\n', start - 1) + 1
    const newVal =
      modelValue.value.substring(0, lineStart) + prefix + modelValue.value.substring(lineStart)
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
    const before = modelValue.value.substring(0, start)
    const after = modelValue.value.substring(start)
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
  const hasTocBlock = computed(() => /<details>/i.test(modelValue.value))

  function generateHeadingsFromToc() {
    const content = modelValue.value
    const detailsMatch = /<details>([\s\S]*?)<\/details>/i.exec(content)
    if (!detailsMatch) return

    const detailsEnd = detailsMatch.index + detailsMatch[0].length

    interface Section {
      headingLine: string
      key: string
      body: string
    }

    const tocItems: { level: number; text: string }[] = []
    for (const line of detailsMatch[1]!.split('\n')) {
      const m = line.match(/^(\s*)-\s+(.+)$/)
      if (!m) continue
      const text = m[2]!.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').trim()
      const level = Math.min(Math.floor(m[1]!.length / 2) + 1, 3)
      if (text) tocItems.push({ level, text })
    }
    if (tocItems.length === 0) return

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
      prev = { line: m[1]!, key: m[1]!.replace(/^#+\s+/, '').toLowerCase(), end: m.index + m[0].length }
    }
    if (prev) {
      existingSections.push({
        headingLine: prev.line,
        key: prev.key,
        body: afterDetails.slice(prev.end),
      })
    }

    const used = new Set<number>()
    const rebuilt = tocItems.map(({ level, text }) => {
      const heading = '#'.repeat(level) + ' ' + text
      const idx = existingSections.findIndex((s, i) => !used.has(i) && s.key === text.toLowerCase())
      let body = '\n'
      if (idx >= 0) {
        used.add(idx)
        body = existingSections[idx]!.body.trim() ? existingSections[idx]!.body : '\n'
      }
      return heading + '\n' + body.replace(/^\n/, '')
    })

    const orphanSections = existingSections.filter(
      (_, i) => !used.has(i) && existingSections[i]!.body.trim(),
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

  // ──────────────── キーボードショートカット ────────────────
  function onKeydown(e: KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
      e.preventDefault()
      wrap('**', '**', '太字テキスト')
    } else if ((e.ctrlKey || e.metaKey) && e.key === 'i') {
      e.preventDefault()
      wrap('*', '*', '斜体テキスト')
    }
  }

  return {
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
  }
}
