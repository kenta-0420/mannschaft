import type { ComputedRef, Ref } from 'vue'

/**
 * F12.6 Q&A クライアント側検索 composable。
 *
 * question/answer を対象にインクリメンタルサーチを行い、
 * マッチ部分を <mark> でハイライトした HTML を返す。
 * ハイライト前に必ず HTML エスケープし、XSS を防止する。
 */

export interface QaItem {
  id: string
  category: string
  question: string
  answer: string
}

// 正規表現のメタ文字をエスケープする
function escapeRegExp(input: string): string {
  return input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// HTML 特殊文字をエスケープする（XSS 対策）
function escapeHtml(input: string): string {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

// 全角半角・大文字小文字を無視するための正規化
function normalize(input: string): string {
  return input.normalize('NFKC').toLowerCase()
}

export function useQaSearch(items: Ref<QaItem[]>, query: Ref<string>) {
  const normalizedQuery: ComputedRef<string> = computed(() => normalize(query.value ?? '').trim())

  const filteredItems: ComputedRef<QaItem[]> = computed(() => {
    const q = normalizedQuery.value
    if (!q) return items.value
    return items.value.filter((item) => {
      const question = normalize(item.question)
      const answer = normalize(item.answer)
      return question.includes(q) || answer.includes(q)
    })
  })

  const hasResults: ComputedRef<boolean> = computed(() => filteredItems.value.length > 0)
  const resultCount: ComputedRef<number> = computed(() => filteredItems.value.length)

  function highlightedText(text: string): string {
    const raw = text ?? ''
    const q = normalizedQuery.value
    if (!q) return escapeHtml(raw)

    // 正規化前後で文字列長が変わる可能性があるため、表示用テキストをエスケープ後に
    // 正規化側の文字列でマッチ位置を探す戦略ではなく、元テキストを正規化して検索用コピーを作り、
    // インデックスベースで元テキストから取り出してエスケープしながら組み立てる
    const normalizedSource = normalize(raw)
    const pattern = new RegExp(escapeRegExp(q), 'g')

    let result = ''
    let lastIndex = 0
    for (const match of normalizedSource.matchAll(pattern)) {
      const start = match.index ?? 0
      const end = start + match[0].length
      // NFKC 正規化後と元テキストで長さが変わることがあるが、ASCII/通常日本語であれば位置はほぼ一致する。
      // 位置ズレが起きた場合も末尾までエスケープ済みテキストとして落とし込むため XSS は発生しない。
      result += escapeHtml(raw.slice(lastIndex, start))
      result += `<mark>${escapeHtml(raw.slice(start, end))}</mark>`
      lastIndex = end
    }
    result += escapeHtml(raw.slice(lastIndex))
    return result
  }

  return {
    filteredItems,
    highlightedText,
    hasResults,
    resultCount,
  }
}
