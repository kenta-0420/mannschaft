import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import DraggableCard from '~/components/corkboard/DraggableCard.vue'
import type { CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 DraggableCard.vue ユニットテスト。
 *
 * テストケース:
 *  DRAG-CARD-001: isDraggable — isPinned=true のとき false
 *  DRAG-CARD-002: isDraggable — canEdit=false のとき false
 *  DRAG-CARD-003: isDraggable — isPinned=false && canEdit=true のとき true
 *  DRAG-CARD-004: cardSize — SMALL のとき 150×100
 *  DRAG-CARD-005: cardSize — LARGE のとき 300×200
 *  DRAG-CARD-006: cardSize — SECTION_HEADER のとき 320×40
 *  DRAG-CARD-007: cardSize — MEDIUM（デフォルト）のとき 200×150
 *  DRAG-CARD-008: previewText — 100文字以下はそのまま返る
 *  DRAG-CARD-009: previewText — 100文字超は切り捨てられ「…」が付く
 *  DRAG-CARD-010: colorBarClass — RED のとき bg-red-400 クラスが適用される
 *  DRAG-CARD-011: colorBarClass — null のとき bg-surface-200（デフォルト）が適用される
 *  DRAG-CARD-012: iconClass — MEMO のとき pi-pencil アイコンが適用される
 *  DRAG-CARD-013: iconClass — SECTION_HEADER のとき pi-tag が適用される
 *  DRAG-CARD-014: セクション紐付けボタン — canEditSection=false のとき非表示
 *  DRAG-CARD-015: セクション紐付けボタン — canEditSection=true && availableSections 空のとき非表示
 *  DRAG-CARD-016: セクション紐付けボタン — canEditSection=true && availableSections 1件のとき表示
 *  DRAG-CARD-017: ピン止めロックアイコン — isPinned=true のとき表示
 *  DRAG-CARD-018: 編集権限なしロックアイコン — canEdit=false && isPinned=false のとき表示
 */

// ============================================================
// @vueuse/core モック — useDraggable を固定値で返す
// ============================================================

vi.mock('@vueuse/core', () => ({
  useDraggable: (_el: unknown, options: { initialValue?: { x: number; y: number } }) => {
    const { ref } = require('vue')
    const initX = options?.initialValue?.x ?? 0
    const initY = options?.initialValue?.y ?? 0
    return {
      x: ref(initX),
      y: ref(initY),
      isDragging: ref(false),
    }
  },
}))

// ============================================================
// 子コンポーネントモック（CardOgpPreview / CardSnapshot）
// ============================================================

vi.mock('~/components/corkboard/CardOgpPreview.vue', () => ({
  default: { template: '<div class="mock-ogp-preview" />' },
}))

vi.mock('~/components/corkboard/CardSnapshot.vue', () => ({
  default: { template: '<div class="mock-card-snapshot" />' },
}))

// ============================================================
// テストデータ ファクトリ
// ============================================================

function makeCard(over: Partial<CorkboardCardDetail> = {}): CorkboardCardDetail {
  return {
    id: 1,
    corkboardId: 10,
    sectionId: null,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: 'テストカード',
    body: null,
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: null,
    cardSize: 'MEDIUM',
    positionX: 100,
    positionY: 200,
    zIndex: null,
    userNote: null,
    noteColor: null,
    autoArchiveAt: null,
    isArchived: false,
    isPinned: false,
    pinnedAt: null,
    isRefDeleted: false,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...over,
  }
}

/** デフォルト props ファクトリ */
function makeProps(
  cardOver: Partial<CorkboardCardDetail> = {},
  propsOver: {
    canEdit?: boolean
    canPin?: boolean
    canEditSection?: boolean
    availableSections?: Array<{ id: number; name: string }>
    currentSectionId?: number | null
  } = {},
) {
  return {
    card: makeCard(cardOver),
    boardId: 10,
    canEdit: true,
    canPin: false,
    canEditSection: false,
    availableSections: [] as Array<{ id: number; name: string }>,
    ...propsOver,
  }
}

// ============================================================
// テスト本体
// ============================================================

describe('DraggableCard.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // ============================================================
  // isDraggable computed — data-draggable 属性で検証
  // ============================================================

  describe('isDraggable（data-draggable 属性で検証）', () => {
    it('DRAG-CARD-001: isPinned=true のとき isDraggable=false（data-draggable="false"）', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ isPinned: true }, { canEdit: true }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      expect(article.attributes('data-draggable')).toBe('false')
    })

    it('DRAG-CARD-002: canEdit=false のとき isDraggable=false（data-draggable="false"）', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ isPinned: false }, { canEdit: false }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      expect(article.attributes('data-draggable')).toBe('false')
    })

    it('DRAG-CARD-003: isPinned=false && canEdit=true のとき isDraggable=true（data-draggable="true"）', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ isPinned: false }, { canEdit: true }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      expect(article.attributes('data-draggable')).toBe('true')
    })
  })

  // ============================================================
  // cardSize computed — style の width/height で検証
  // ============================================================

  describe('cardSize computed（style width/height で検証）', () => {
    it('DRAG-CARD-004: cardSize=SMALL のとき width=150px, height=100px', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardSize: 'SMALL', cardType: 'MEMO' }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      const style = (article.element as HTMLElement).style
      expect(style.width).toBe('150px')
      expect(style.height).toBe('100px')
    })

    it('DRAG-CARD-005: cardSize=LARGE のとき width=300px, height=200px', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardSize: 'LARGE', cardType: 'MEMO' }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      const style = (article.element as HTMLElement).style
      expect(style.width).toBe('300px')
      expect(style.height).toBe('200px')
    })

    it('DRAG-CARD-006: cardType=SECTION_HEADER のとき width=320px, height=40px（サイズ優先）', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardType: 'SECTION_HEADER', cardSize: 'MEDIUM' }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      const style = (article.element as HTMLElement).style
      expect(style.width).toBe('320px')
      expect(style.height).toBe('40px')
    })

    it('DRAG-CARD-007: cardSize=MEDIUM（デフォルト）のとき width=200px, height=150px', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardSize: 'MEDIUM', cardType: 'MEMO' }),
      })
      const article = wrapper.find('[data-testid="corkboard-card-1"]')
      const style = (article.element as HTMLElement).style
      expect(style.width).toBe('200px')
      expect(style.height).toBe('150px')
    })
  })

  // ============================================================
  // previewText — body テキストのレンダリングで検証
  // ============================================================

  describe('previewText', () => {
    it('DRAG-CARD-008: 100文字以下の body はそのままレンダリングされる', async () => {
      const shortText = 'あいうえお'.repeat(10) // 50文字
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardType: 'MEMO', body: shortText, title: null }),
      })
      expect(wrapper.text()).toContain(shortText)
    })

    it('DRAG-CARD-009: 101文字の body は100文字で切り捨てられ「…」が付く', async () => {
      const longText = 'a'.repeat(101)
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardType: 'MEMO', body: longText, title: null }),
      })
      // 「aaaa...（100文字）…」が含まれること
      expect(wrapper.text()).toContain('a'.repeat(100) + '…')
      // 101文字目の 'a' が末尾に付いていないこと（切り捨てられていること）
      expect(wrapper.text()).not.toContain('a'.repeat(101))
    })
  })

  // ============================================================
  // colorBarClass — カラーバー要素のクラスで検証
  // ============================================================

  describe('colorBarClass', () => {
    it('DRAG-CARD-010: colorLabel=RED のとき bg-red-400 クラスが付く', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ colorLabel: 'RED' }),
      })
      // カラーバーは article の直下の最初の span
      const colorBar = wrapper.find('article > span')
      expect(colorBar.classes()).toContain('bg-red-400')
    })

    it('DRAG-CARD-011: colorLabel=null のとき bg-surface-200（デフォルト）が付く', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ colorLabel: null }),
      })
      const colorBar = wrapper.find('article > span')
      expect(colorBar.classes()).toContain('bg-surface-200')
    })
  })

  // ============================================================
  // iconClass — カードヘッダのアイコンクラスで検証
  // ============================================================

  describe('iconClass', () => {
    it('DRAG-CARD-012: cardType=MEMO のとき pi-pencil クラスが付く', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardType: 'MEMO' }),
      })
      // ヘッダアイコン（pi クラスを持つ最初の i 要素）
      const icon = wrapper.find('.pi.mt-0\\.5')
      expect(icon.classes()).toContain('pi-pencil')
    })

    it('DRAG-CARD-013: cardType=SECTION_HEADER のとき pi-tag クラスが付く', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ cardType: 'SECTION_HEADER' }),
      })
      const icon = wrapper.find('.pi.mt-0\\.5')
      expect(icon.classes()).toContain('pi-tag')
    })
  })

  // ============================================================
  // セクション紐付けボタン表示条件
  // ============================================================

  describe('セクション紐付けボタン', () => {
    it('DRAG-CARD-014: canEditSection=false のときセクションボタンは非表示', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({}, { canEditSection: false, availableSections: [{ id: 1, name: 'A' }] }),
      })
      const sectionBtn = wrapper.find('[data-testid="corkboard-card-section-button-1"]')
      expect(sectionBtn.exists()).toBe(false)
    })

    it('DRAG-CARD-015: canEditSection=true && availableSections 空のときセクションボタンは非表示', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({}, { canEditSection: true, availableSections: [] }),
      })
      const sectionBtn = wrapper.find('[data-testid="corkboard-card-section-button-1"]')
      expect(sectionBtn.exists()).toBe(false)
    })

    it('DRAG-CARD-016: canEditSection=true && availableSections 1件以上のときセクションボタン表示', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps(
          {},
          { canEditSection: true, availableSections: [{ id: 1, name: 'セクションA' }] },
        ),
      })
      const sectionBtn = wrapper.find('[data-testid="corkboard-card-section-button-1"]')
      expect(sectionBtn.exists()).toBe(true)
    })
  })

  // ============================================================
  // ロックアイコン表示
  // ============================================================

  describe('ロックアイコン表示', () => {
    it('DRAG-CARD-017: isPinned=true のときピン止めアイコン（bookmark-fill）が表示される', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ isPinned: true }, { canEdit: true }),
      })
      const lockIcon = wrapper.find('[data-testid="corkboard-card-lock-icon-1"]')
      expect(lockIcon.exists()).toBe(true)
      expect(lockIcon.classes()).toContain('pi-bookmark-fill')
    })

    it('DRAG-CARD-018: canEdit=false && isPinned=false のとき鍵アイコン（pi-lock）が表示される', async () => {
      const wrapper = await mountSuspended(DraggableCard, {
        props: makeProps({ isPinned: false }, { canEdit: false }),
      })
      const lockIcon = wrapper.find('[data-testid="corkboard-card-lock-icon-1"]')
      expect(lockIcon.exists()).toBe(true)
      expect(lockIcon.classes()).toContain('pi-lock')
    })
  })
})
