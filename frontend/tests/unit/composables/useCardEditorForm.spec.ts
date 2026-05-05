import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { CorkboardCardDetail, CorkboardReferenceType } from '~/types/corkboard'
import { useCardEditorForm } from '~/composables/useCardEditorForm'

/**
 * F09.8 Phase 4-β useCardEditorForm — フォームロジックのユニットテスト。
 *
 * テストケース一覧:
 *
 * [validate — MEMO]
 *  CEFORM-001: MEMO / body が空なら errors.body が設定され false を返す
 *  CEFORM-002: MEMO / body に値があれば validate が true を返す
 *
 * [validate — URL]
 *  CEFORM-003: URL / url が空なら errors.url が設定され false を返す
 *  CEFORM-004: URL / 不正な URL 形式で errors.url が設定される
 *  CEFORM-005: URL / http:// URL で validate が true を返す
 *  CEFORM-006: URL / https:// URL でも validate が true を返す
 *
 * [validate — REFERENCE]
 *  CEFORM-007: REFERENCE / referenceId が null なら errors.referenceId が設定される
 *  CEFORM-008: REFERENCE / referenceId が 0 の場合 errors.referenceId が設定される
 *  CEFORM-009: REFERENCE / referenceId が負の場合 errors.referenceId が設定される
 *  CEFORM-010: REFERENCE / 両フィールド正常で validate が true を返す
 *
 * [validate — SECTION_HEADER]
 *  CEFORM-011: SECTION_HEADER / title が空なら errors.title が設定される
 *  CEFORM-012: SECTION_HEADER / title に値があれば validate が true を返す
 *
 * [buildCreatePayload]
 *  CEFORM-013: MEMO カード — body/title/colorLabel/positionX,Y を含む正しいペイロード
 *  CEFORM-014: URL カード — url を含む正しいペイロード
 *  CEFORM-015: REFERENCE カード — referenceType/referenceId を含む正しいペイロード
 *  CEFORM-016: SECTION_HEADER カード — title のみ含む正しいペイロード
 *
 * [buildUpdatePayload]
 *  CEFORM-017: MEMO カード — cardType を含まず colorLabel/positionX,Y/title/body を含む
 *  CEFORM-018: URL カード — url/title/colorLabel を含む更新ペイロード
 *  CEFORM-019: REFERENCE カード — userNote のみ更新、referenceType/referenceId を含まない
 *  CEFORM-020: createPayload と updatePayload の差異 — cardType の有無
 *
 * [extractIdFromUrl]
 *  CEFORM-021: TIMELINE_POST パターン URL から ID が正しく抽出される
 *  CEFORM-022: BLOG_POST パターン URL から ID が正しく抽出される
 *  CEFORM-023: パターンにない referenceType でも末尾数字からフォールバック抽出される
 *  CEFORM-024: 不正 URL（数字なし）で null が返る
 *  CEFORM-025: 空文字で null が返る
 *  CEFORM-026: クエリパラメータ付き URL から末尾 ID が抽出される
 *  CEFORM-027: TEAM パターン URL から ID が正しく抽出される
 *
 * [resetForm]
 *  CEFORM-028: create モードではフォームが初期値（MEMO / WHITE / position=0）にリセットされる
 *  CEFORM-029: edit モードでは card の値がフォームに反映される
 *  CEFORM-030: edit モードで card が null なら create モードと同じ初期値になる
 *
 * [applyReferenceUrlPaste]
 *  CEFORM-031: 有効な URL を貼り付けると referenceId が設定され success メッセージが出る
 *  CEFORM-032: 無効な URL を貼り付けると referenceId は変わらず error メッセージが出る
 */

// ============================================================
// useI18n のモック
// useCardEditorForm は tFn 引数でダミー t 関数を注入できるため、
// Nuxt auto-import のモック設定は不要。
// ============================================================

// ============================================================
// テスト用ヘルパー
// ============================================================

/**
 * テスト用ダミー t 関数。キーをそのまま返す。
 * useCardEditorForm の tFn 引数に渡して useI18n の setup 制約を回避する。
 */
const mockT = (key: string) => key

function makeCardDetail(overrides: Partial<CorkboardCardDetail> = {}): CorkboardCardDetail {
  return {
    id: 1,
    corkboardId: 10,
    sectionId: null,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: '既存タイトル',
    body: '既存本文',
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: 'YELLOW',
    cardSize: null,
    positionX: 100,
    positionY: 200,
    zIndex: null,
    userNote: '既存メモ',
    noteColor: null,
    autoArchiveAt: null,
    isArchived: false,
    isPinned: false,
    pinnedAt: null,
    isRefDeleted: false,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  }
}

/** useCardEditorForm をテスト用設定で生成するファクトリ */
function makeForm(options: {
  mode?: 'create' | 'edit'
  card?: CorkboardCardDetail | null
  boardId?: number
  defaultPosition?: { x: number; y: number }
}) {
  const mode = ref<'create' | 'edit'>(options.mode ?? 'create')
  const card = ref<CorkboardCardDetail | null>(options.card ?? null)
  const boardId = ref(options.boardId ?? 1)
  const defaultPosition = ref(options.defaultPosition ?? { x: 0, y: 0 })
  return useCardEditorForm(mode, card, boardId, defaultPosition, mockT)
}

// ============================================================
// テスト本体
// ============================================================

describe('useCardEditorForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // ----------------------------------------------------------
  // validate — MEMO
  // ----------------------------------------------------------
  describe('validate — MEMO カード', () => {
    it('CEFORM-001: body が空なら errors.body が設定され false を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'MEMO'
      form.body.value = ''

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.body).toBe('corkboard.validation.memoBodyRequired')
    })

    it('CEFORM-002: body に値があれば validate が true を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'MEMO'
      form.body.value = '本文あり'

      const result = form.validate()

      expect(result).toBe(true)
      expect(form.errors.value.body).toBeUndefined()
    })
  })

  // ----------------------------------------------------------
  // validate — URL
  // ----------------------------------------------------------
  describe('validate — URL カード', () => {
    it('CEFORM-003: url が空なら errors.url が設定され false を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'URL'
      form.url.value = ''

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.url).toBe('corkboard.validation.required')
    })

    it('CEFORM-004: 不正な URL 形式で errors.url が設定され false を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'URL'
      form.url.value = 'not-a-valid-url'

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.url).toBe('corkboard.validation.urlInvalid')
    })

    it('CEFORM-005: http:// URL で validate が true を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'URL'
      form.url.value = 'http://example.com'

      const result = form.validate()

      expect(result).toBe(true)
      expect(form.errors.value.url).toBeUndefined()
    })

    it('CEFORM-006: https:// URL で validate が true を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'URL'
      form.url.value = 'https://example.com/path?q=1'

      const result = form.validate()

      expect(result).toBe(true)
    })
  })

  // ----------------------------------------------------------
  // validate — REFERENCE
  // ----------------------------------------------------------
  describe('validate — REFERENCE カード', () => {
    it('CEFORM-007: referenceId が null なら errors.referenceId が設定される', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceId.value = null

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.referenceId).toBe('corkboard.validation.referenceIdRequired')
    })

    it('CEFORM-008: referenceId が 0 なら errors.referenceId が設定される', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceId.value = 0

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.referenceId).toBe('corkboard.validation.referenceIdRequired')
    })

    it('CEFORM-009: referenceId が負の場合 errors.referenceId が設定される', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceId.value = -5

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.referenceId).toBe('corkboard.validation.referenceIdRequired')
    })

    it('CEFORM-010: referenceType と正の referenceId があれば validate が true を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceId.value = 42

      const result = form.validate()

      expect(result).toBe(true)
      expect(form.errors.value.referenceType).toBeUndefined()
      expect(form.errors.value.referenceId).toBeUndefined()
    })
  })

  // ----------------------------------------------------------
  // validate — SECTION_HEADER
  // ----------------------------------------------------------
  describe('validate — SECTION_HEADER カード', () => {
    it('CEFORM-011: title が空なら errors.title が設定され false を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'SECTION_HEADER'
      form.title.value = ''

      const result = form.validate()

      expect(result).toBe(false)
      expect(form.errors.value.title).toBe('corkboard.validation.sectionHeaderTitleRequired')
    })

    it('CEFORM-012: title に値があれば validate が true を返す', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'SECTION_HEADER'
      form.title.value = 'セクション名'

      const result = form.validate()

      expect(result).toBe(true)
    })
  })

  // ----------------------------------------------------------
  // buildCreatePayload
  // ----------------------------------------------------------
  describe('buildCreatePayload', () => {
    it('CEFORM-013: MEMO カード — body/title/colorLabel/positionX,Y を含む正しいペイロード', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'MEMO'
      form.title.value = 'テストタイトル'
      form.body.value = 'テスト本文'
      form.colorLabel.value = 'YELLOW'
      form.positionX.value = 50
      form.positionY.value = 100
      form.userNote.value = 'メモ'

      const payload = form.buildCreatePayload()

      expect(payload.cardType).toBe('MEMO')
      expect(payload.title).toBe('テストタイトル')
      expect(payload.body).toBe('テスト本文')
      expect(payload.colorLabel).toBe('YELLOW')
      expect(payload.positionX).toBe(50)
      expect(payload.positionY).toBe(100)
      expect(payload.userNote).toBe('メモ')
      // MEMO には url は含まれない
      expect('url' in payload).toBe(false)
    })

    it('CEFORM-014: URL カード — url を含む正しいペイロード', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'URL'
      form.url.value = 'https://example.com'
      form.title.value = 'リンクタイトル'
      form.colorLabel.value = 'BLUE'
      form.positionX.value = 10
      form.positionY.value = 20

      const payload = form.buildCreatePayload()

      expect(payload.cardType).toBe('URL')
      expect(payload.url).toBe('https://example.com')
      expect(payload.title).toBe('リンクタイトル')
      expect(payload.colorLabel).toBe('BLUE')
    })

    it('CEFORM-015: REFERENCE カード — referenceType/referenceId を含む正しいペイロード', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'BLOG_POST'
      form.referenceId.value = 99
      form.userNote.value = '参照メモ'
      form.colorLabel.value = 'GREEN'

      const payload = form.buildCreatePayload()

      expect(payload.cardType).toBe('REFERENCE')
      expect(payload.referenceType).toBe('BLOG_POST')
      expect(payload.referenceId).toBe(99)
      expect(payload.userNote).toBe('参照メモ')
      expect(payload.colorLabel).toBe('GREEN')
    })

    it('CEFORM-016: SECTION_HEADER カード — title のみ含む正しいペイロード（body/userNote なし）', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'SECTION_HEADER'
      form.title.value = 'ヘッダータイトル'
      form.colorLabel.value = 'GRAY'

      const payload = form.buildCreatePayload()

      expect(payload.cardType).toBe('SECTION_HEADER')
      expect(payload.title).toBe('ヘッダータイトル')
      // SECTION_HEADER には body / userNote は含まれない
      expect('body' in payload).toBe(false)
      expect('userNote' in payload).toBe(false)
    })
  })

  // ----------------------------------------------------------
  // buildUpdatePayload
  // ----------------------------------------------------------
  describe('buildUpdatePayload', () => {
    it('CEFORM-017: MEMO カード — cardType を含まず colorLabel/positionX,Y/title/body を含む', () => {
      const form = makeForm({
        mode: 'edit',
        card: makeCardDetail({ cardType: 'MEMO' }),
      })
      form.colorLabel.value = 'RED'
      form.positionX.value = 30
      form.positionY.value = 60
      form.title.value = '更新タイトル'
      form.body.value = '更新本文'
      form.userNote.value = ''

      const payload = form.buildUpdatePayload()

      expect(payload.colorLabel).toBe('RED')
      expect(payload.positionX).toBe(30)
      expect(payload.positionY).toBe(60)
      expect(payload.title).toBe('更新タイトル')
      expect(payload.body).toBe('更新本文')
      // cardType は UpdateCardRequest に存在しない
      expect('cardType' in payload).toBe(false)
      // 空文字は null に正規化される
      expect(payload.userNote).toBeNull()
    })

    it('CEFORM-018: URL カード — url/title/colorLabel を含む更新ペイロード', () => {
      const form = makeForm({
        mode: 'edit',
        card: makeCardDetail({ cardType: 'URL', url: 'https://old.com' }),
      })
      form.url.value = 'https://new.com'
      form.title.value = '新しいタイトル'
      form.colorLabel.value = 'PURPLE'

      const payload = form.buildUpdatePayload()

      expect(payload.url).toBe('https://new.com')
      expect(payload.title).toBe('新しいタイトル')
      expect(payload.colorLabel).toBe('PURPLE')
    })

    it('CEFORM-019: REFERENCE カード — userNote のみ更新し referenceType/referenceId を含まない', () => {
      const form = makeForm({
        mode: 'edit',
        card: makeCardDetail({
          cardType: 'REFERENCE',
          referenceType: 'TIMELINE_POST',
          referenceId: 55,
        }),
      })
      form.userNote.value = '更新後メモ'

      const payload = form.buildUpdatePayload()

      expect(payload.userNote).toBe('更新後メモ')
      // REFERENCE の edit では referenceType / referenceId は送らない
      expect('referenceType' in payload).toBe(false)
      expect('referenceId' in payload).toBe(false)
    })

    it('CEFORM-020: createPayload には cardType が含まれ updatePayload には含まれない', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'MEMO'
      form.body.value = '本文'

      const createPayload = form.buildCreatePayload()
      const updatePayload = form.buildUpdatePayload()

      expect('cardType' in createPayload).toBe(true)
      expect(createPayload.cardType).toBe('MEMO')
      expect('cardType' in updatePayload).toBe(false)
    })
  })

  // ----------------------------------------------------------
  // extractIdFromUrl
  // ----------------------------------------------------------
  describe('extractIdFromUrl', () => {
    it('CEFORM-021: TIMELINE_POST パターン URL から ID が正しく抽出される', () => {
      const form = makeForm({ mode: 'create' })

      const id = form.extractIdFromUrl(
        'https://app.example.com/timeline/posts/123',
        'TIMELINE_POST',
      )

      expect(id).toBe(123)
    })

    it('CEFORM-022: BLOG_POST パターン URL から ID が正しく抽出される', () => {
      const form = makeForm({ mode: 'create' })

      const id = form.extractIdFromUrl('https://app.example.com/blog/posts/456', 'BLOG_POST')

      expect(id).toBe(456)
    })

    it('CEFORM-023: パターンにない referenceType でも末尾数字からフォールバック抽出される', () => {
      const form = makeForm({ mode: 'create' })

      // URL type には専用パターンがないため末尾数字で抽出
      const id = form.extractIdFromUrl(
        'https://app.example.com/something/789',
        'URL' as CorkboardReferenceType,
      )

      expect(id).toBe(789)
    })

    it('CEFORM-024: 不正 URL（数字なし）で null が返る', () => {
      const form = makeForm({ mode: 'create' })

      const id = form.extractIdFromUrl('https://app.example.com/no-id-here', 'TEAM')

      expect(id).toBeNull()
    })

    it('CEFORM-025: 空文字で null が返る', () => {
      const form = makeForm({ mode: 'create' })

      const id = form.extractIdFromUrl('', 'TIMELINE_POST')

      expect(id).toBeNull()
    })

    it('CEFORM-026: クエリパラメータ付き URL から末尾 ID が正しく抽出される', () => {
      const form = makeForm({ mode: 'create' })

      // クエリ・ハッシュを除去後に末尾数字を拾う
      const id = form.extractIdFromUrl(
        'https://app.example.com/timeline/posts/321?ref=home#comment',
        'TIMELINE_POST',
      )

      expect(id).toBe(321)
    })

    it('CEFORM-027: TEAM パターン URL から ID が正しく抽出される', () => {
      const form = makeForm({ mode: 'create' })

      const id = form.extractIdFromUrl('https://app.example.com/teams/77', 'TEAM')

      expect(id).toBe(77)
    })
  })

  // ----------------------------------------------------------
  // resetForm
  // ----------------------------------------------------------
  describe('resetForm', () => {
    it('CEFORM-028: create モードではフォームが初期値（MEMO / WHITE / position=0）にリセットされる', () => {
      const form = makeForm({ mode: 'create' })

      // 値を汚す
      form.cardType.value = 'URL'
      form.colorLabel.value = 'RED'
      form.positionX.value = 500
      form.title.value = '汚染タイトル'
      form.body.value = '汚染本文'

      form.resetForm()

      expect(form.cardType.value).toBe('MEMO')
      expect(form.colorLabel.value).toBe('WHITE')
      expect(form.positionX.value).toBe(0)
      expect(form.positionY.value).toBe(0)
      expect(form.title.value).toBe('')
      expect(form.body.value).toBe('')
      expect(form.url.value).toBe('')
      expect(form.userNote.value).toBe('')
      expect(form.errors.value).toEqual({})
    })

    it('CEFORM-029: edit モードでは card の値がフォームに反映される', () => {
      const testCard = makeCardDetail({
        cardType: 'URL',
        colorLabel: 'BLUE',
        positionX: 120,
        positionY: 240,
        title: '編集対象タイトル',
        url: 'https://edit.example.com',
        userNote: '編集メモ',
      })
      const form = makeForm({ mode: 'edit', card: testCard })

      // watch immediate で自動リセット済みだが明示的にも呼ぶ
      form.resetForm()

      expect(form.cardType.value).toBe('URL')
      expect(form.colorLabel.value).toBe('BLUE')
      expect(form.positionX.value).toBe(120)
      expect(form.positionY.value).toBe(240)
      expect(form.title.value).toBe('編集対象タイトル')
      expect(form.url.value).toBe('https://edit.example.com')
      expect(form.userNote.value).toBe('編集メモ')
    })

    it('CEFORM-030: edit モードで card が null なら create モードと同じ初期値になる', () => {
      const form = makeForm({ mode: 'edit', card: null })

      form.resetForm()

      expect(form.cardType.value).toBe('MEMO')
      expect(form.colorLabel.value).toBe('WHITE')
      expect(form.positionX.value).toBe(0)
      expect(form.title.value).toBe('')
    })
  })

  // ----------------------------------------------------------
  // applyReferenceUrlPaste
  // ----------------------------------------------------------
  describe('applyReferenceUrlPaste', () => {
    it('CEFORM-031: 有効な URL を貼り付けると referenceId が設定され success メッセージが出る', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceUrlPaste.value = 'https://app.example.com/timeline/posts/999'

      form.applyReferenceUrlPaste()

      expect(form.referenceId.value).toBe(999)
      expect(form.referenceUrlPasteMessage.value?.kind).toBe('success')
      expect(form.referenceUrlPasteMessage.value?.text).toBe(
        'corkboard.modal.referenceUrlExtractSuccess',
      )
    })

    it('CEFORM-032: 無効な URL を貼り付けると referenceId は変わらず error メッセージが出る', () => {
      const form = makeForm({ mode: 'create' })
      form.cardType.value = 'REFERENCE'
      form.referenceType.value = 'TIMELINE_POST'
      form.referenceId.value = null
      form.referenceUrlPaste.value = 'https://app.example.com/no-numbers-here'

      form.applyReferenceUrlPaste()

      expect(form.referenceId.value).toBeNull()
      expect(form.referenceUrlPasteMessage.value?.kind).toBe('error')
      expect(form.referenceUrlPasteMessage.value?.text).toBe(
        'corkboard.modal.referenceUrlExtractFailed',
      )
    })
  })
})
