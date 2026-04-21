import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useQaSearch, type QaItem } from '~/composables/useQaSearch'

/**
 * F12.6 useQaSearch のユニットテスト。
 *
 * <p>対象機能:
 * - filteredItems: question / answer への部分一致フィルタ（NFKC + 小文字正規化）
 * - highlightedText: マッチ部分を &lt;mark&gt; で囲みつつ HTML を必ずエスケープ（XSS対策）
 * - hasResults / resultCount: 検索結果有無と件数
 * </p>
 */

function createItems(): QaItem[] {
  return [
    {
      id: 'basic-q1',
      category: 'basic',
      question: 'PCとスマートフォンで機能の違いはありますか？',
      answer: 'スマートフォンはPWAとしてホーム画面に追加できます。',
    },
    {
      id: 'pwa-q1',
      category: 'pwa',
      question: 'PWAとは何ですか？',
      answer: 'Progressive Web App の略称で、アプリのように使えるウェブの仕組みです。',
    },
    {
      id: 'offline-q1',
      category: 'offline',
      question: 'オフラインでも使えますか？',
      answer: 'はい、行動メモなどはオフラインで入力でき、復帰時に自動同期されます。',
    },
  ]
}

describe('useQaSearch', () => {
  describe('filteredItems', () => {
    it('空クエリのとき全件返す', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('')
      const { filteredItems } = useQaSearch(items, query)

      expect(filteredItems.value).toHaveLength(3)
    })

    it('question内の部分一致でフィルタされる', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('スマートフォン')
      const { filteredItems } = useQaSearch(items, query)

      expect(filteredItems.value).toHaveLength(1)
      expect(filteredItems.value[0]?.id).toBe('basic-q1')
    })

    it('answer内の部分一致でフィルタされる', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('Progressive Web App')
      const { filteredItems } = useQaSearch(items, query)

      expect(filteredItems.value).toHaveLength(1)
      expect(filteredItems.value[0]?.id).toBe('pwa-q1')
    })

    it('大文字小文字を区別せずマッチする', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('pwa')
      const { filteredItems } = useQaSearch(items, query)

      // 大文字の "PWA" を含む項目（question: "PWAとは..."、answer: "PWA..."）が少なくとも1件マッチする
      expect(filteredItems.value.length).toBeGreaterThanOrEqual(1)
      expect(filteredItems.value.some((item) => item.id === 'pwa-q1')).toBe(true)
    })

    it('全角英数字と半角英数字を同一視する（NFKC正規化）', () => {
      const items = ref<QaItem[]>([
        {
          id: 'test-1',
          category: 'basic',
          question: 'PWA のインストール方法',
          answer: 'ブラウザからインストールできます',
        },
      ])
      // 全角英字でクエリ
      const query = ref('ＰＷＡ')
      const { filteredItems } = useQaSearch(items, query)

      expect(filteredItems.value).toHaveLength(1)
      expect(filteredItems.value[0]?.id).toBe('test-1')
    })

    it('マッチしないクエリで空配列を返す', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('まったく存在しない文字列XYZ123')
      const { filteredItems } = useQaSearch(items, query)

      expect(filteredItems.value).toHaveLength(0)
    })
  })

  describe('highlightedText', () => {
    it('空クエリのとき元テキストをエスケープのみ返す', () => {
      const items = ref<QaItem[]>([])
      const query = ref('')
      const { highlightedText } = useQaSearch(items, query)

      expect(highlightedText('hello <b>world</b>')).toBe('hello &lt;b&gt;world&lt;/b&gt;')
    })

    it('マッチ部分が<mark>で囲まれる', () => {
      const items = ref<QaItem[]>([])
      const query = ref('PWA')
      const { highlightedText } = useQaSearch(items, query)

      const result = highlightedText('PWA はとても便利です')
      expect(result).toContain('<mark>PWA</mark>')
      expect(result).toContain(' はとても便利です')
    })

    it('HTMLタグは必ずエスケープされる (XSS対策)', () => {
      const items = ref<QaItem[]>([])
      const query = ref('hello')
      const { highlightedText } = useQaSearch(items, query)

      // 入力に生HTMLが含まれていても出力ではエスケープされる
      const result = highlightedText('<script>alert(1)</script> hello')

      // 生の <script タグは出力に含まれない
      expect(result).not.toContain('<script')
      expect(result).not.toContain('</script>')
      // エスケープ済みの形で含まれる
      expect(result).toContain('&lt;script&gt;')
      expect(result).toContain('&lt;/script&gt;')
      // マッチ部分は <mark> で囲まれる
      expect(result).toContain('<mark>hello</mark>')
    })

    it('スクリプトタグ入りクエリでも出力はエスケープされる (XSS対策)', () => {
      const items = ref<QaItem[]>([])
      // クエリ自体に危険文字列
      const query = ref('<script>')
      const { highlightedText } = useQaSearch(items, query)

      // 入力テキストに同じ文字列があってもタグは生で出ない
      const result = highlightedText('危険: <script> が入っています')

      expect(result).not.toContain('<script>')
      expect(result).toContain('&lt;script&gt;')
      // <mark> はクエリ部分のみ付与される（<mark> タグ自体は生のまま出力、中身はエスケープ済み）
      expect(result).toContain('<mark>&lt;script&gt;</mark>')
    })

    it('正規表現特殊文字 (.*+) をリテラル扱いする', () => {
      const items = ref<QaItem[]>([])
      const query = ref('.*+')
      const { highlightedText } = useQaSearch(items, query)

      // ".*+" をリテラルとして扱うため、任意の文字列すべてに誤爆マッチしない
      const result = highlightedText('abc def')
      expect(result).not.toContain('<mark>')
      expect(result).toBe('abc def')

      // 逆に、".*+" という文字列を含むテキストだけマッチする
      const matched = highlightedText('これは .*+ を含むテキスト')
      expect(matched).toContain('<mark>.*+</mark>')
    })
  })

  describe('hasResults / resultCount', () => {
    it('結果あり時 hasResults=true, resultCount=N', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('PWA')
      const { hasResults, resultCount } = useQaSearch(items, query)

      expect(hasResults.value).toBe(true)
      // PWA を含む項目（question または answer）の件数
      expect(resultCount.value).toBeGreaterThanOrEqual(1)
    })

    it('結果なし時 hasResults=false, resultCount=0', () => {
      const items = ref<QaItem[]>(createItems())
      const query = ref('絶対にヒットしない文字列_ZZZ_999')
      const { hasResults, resultCount } = useQaSearch(items, query)

      expect(hasResults.value).toBe(false)
      expect(resultCount.value).toBe(0)
    })
  })
})
