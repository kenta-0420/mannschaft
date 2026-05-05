/**
 * F09.8 Phase E: セクション管理 composable。
 *
 * `[id].vue` から以下のロジックを抽出:
 *  - セクション折りたたみ状態管理（collapsedSections + localStorage 同期）
 *  - セクション CRUD モーダル制御（sectionEditorMode / sectionEditorTarget / sectionEditorVisible）
 *  - カードとセクションの紐付け操作（addCardToSection / removeCardFromSection）
 *  - セクション選択ポップオーバー制御（popoverTargetCard）
 *
 * 設計メモ:
 *  - useI18n() は呼び出し元（`[id].vue`）から t 関数を受け取る。
 *    composable 内部で直接 useI18n() を呼ばないことで、Vitest テスト環境での
 *    「setup function 外呼び出し」制約を回避し、ユニットテストを容易にする。
 *  - toast / captureQuiet / confirmAction は内部で各 composable を呼び出す
 *    （これらは vue-i18n のような制約がないため）。
 */
import { useToast } from 'primevue/usetoast'
import type { CorkboardDetail, CorkboardCardDetail, CorkboardGroupDetail } from '~/types/corkboard'

export function useCorkboardSectionManagement(
  board: Ref<CorkboardDetail | null>,
  boardId: Ref<number>,
  /** useI18n().t を呼び出し元から注入（Vitest 環境での setup 制約を回避するため） */
  t: (key: string) => string,
) {
  const toast = useToast()
  const { captureQuiet } = useErrorReport()
  const { confirmAction } = useConfirmDialog()
  const {
    deleteGroup: apiDeleteGroup,
    addCardToGroup: apiAddCardToGroup,
    removeCardFromGroup: apiRemoveCardFromGroup,
  } = useCorkboardApi()

  // ----- セクション折りたたみ状態 -----

  const collapsedSections = ref<Record<number, boolean>>({})

  const storageKey = computed(() => `corkboard:collapse:${boardId.value}`)

  function loadCollapsedState() {
    if (typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(storageKey.value)
      if (!raw) return
      const parsed: unknown = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') {
        const obj: Record<number, boolean> = {}
        for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
          const id = Number(k)
          if (Number.isFinite(id) && typeof v === 'boolean') {
            obj[id] = v
          }
        }
        collapsedSections.value = obj
      }
    } catch {
      // localStorage が壊れている等は無視
    }
  }

  function persistCollapsedState() {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(storageKey.value, JSON.stringify(collapsedSections.value))
    } catch {
      // QuotaExceeded 等は無視
    }
  }

  function toggleSection(sectionId: number) {
    collapsedSections.value = {
      ...collapsedSections.value,
      [sectionId]: !collapsedSections.value[sectionId],
    }
    persistCollapsedState()
  }

  /**
   * localStorage に値があればそれを優先、無ければ DTO の isCollapsed を初期値とする。
   */
  function isSectionCollapsed(section: CorkboardGroupDetail): boolean {
    const local = collapsedSections.value[section.id]
    if (typeof local === 'boolean') return local
    return section.isCollapsed
  }

  // ----- セクション CRUD モーダル制御 -----

  const sectionEditorMode = ref<'create' | 'edit' | null>(null)
  const sectionEditorTarget = ref<CorkboardGroupDetail | null>(null)
  const sectionEditorVisible = computed({
    get: () => sectionEditorMode.value !== null,
    set: (v: boolean) => {
      if (!v) {
        sectionEditorMode.value = null
        sectionEditorTarget.value = null
      }
    },
  })

  function openCreateSection() {
    sectionEditorTarget.value = null
    sectionEditorMode.value = 'create'
  }

  function openEditSection(section: CorkboardGroupDetail) {
    sectionEditorTarget.value = section
    sectionEditorMode.value = 'edit'
  }

  /** セクション削除（確認ダイアログ → API → ローカル状態更新）。 */
  function confirmDeleteSection(section: CorkboardGroupDetail) {
    confirmAction({
      header: t('corkboard.confirm.deleteSectionTitle'),
      message: t('corkboard.confirm.deleteSectionMessage'),
      onAccept: () => doDeleteSection(section),
    })
  }

  async function doDeleteSection(section: CorkboardGroupDetail) {
    try {
      await apiDeleteGroup(boardId.value, section.id)
      if (board.value) {
        // section_id FK は ON DELETE SET NULL なので、
        // ローカル状態でも該当カードの sectionId を null にしておく（残骸防止）。
        board.value = {
          ...board.value,
          groups: board.value.groups.filter((g) => g.id !== section.id),
          cards: board.value.cards.map((c) =>
            c.sectionId === section.id ? { ...c, sectionId: null } : c,
          ),
        }
      }
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.sectionDeleteSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardSectionManagement: セクション削除失敗' })
      toast.add({
        severity: 'error',
        summary: t('corkboard.toast.sectionDeleteError'),
        life: 3500,
      })
    }
  }

  // ----- カード ↔ セクション紐付け -----

  /**
   * カードの主セクション ID を返す。
   *
   * F09.8 積み残し件1 (V9.097) で `corkboard_cards.section_id` がバックエンド DTO に追加されたため、
   * カード DTO の `sectionId` を直接参照する。
   */
  function getCardSectionId(card: CorkboardCardDetail): number | null {
    return card.sectionId ?? null
  }

  /** ボード内の特定カードの sectionId をローカル状態で楽観的に更新する。 */
  function patchCardSectionLocally(cardId: number, sectionId: number | null) {
    if (!board.value) return
    board.value = {
      ...board.value,
      cards: board.value.cards.map((c) => (c.id === cardId ? { ...c, sectionId } : c)),
    }
  }

  /** カードをセクションに追加 / 移動する。 */
  async function addCardToSection(card: CorkboardCardDetail, sectionId: number) {
    try {
      // 既に別セクションに属していた場合は先に外す
      const current = getCardSectionId(card)
      if (current != null && current !== sectionId) {
        try {
          await apiRemoveCardFromGroup(boardId.value, current, card.id)
        } catch (e) {
          // 旧所属の解除失敗はログのみ（追加自体は試みる）
          captureQuiet(e, {
            context: 'useCorkboardSectionManagement: 旧セクションからの解除失敗',
          })
        }
      }
      await apiAddCardToGroup(boardId.value, sectionId, card.id)
      patchCardSectionLocally(card.id, sectionId)
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.cardAddToSectionSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, {
        context: 'useCorkboardSectionManagement: カードをセクションに追加失敗',
      })
      toast.add({
        severity: 'error',
        summary: t('corkboard.toast.cardSectionChangeError'),
        life: 3500,
      })
    }
  }

  /** カードを現在のセクションから外す。 */
  async function removeCardFromSection(card: CorkboardCardDetail) {
    const sectionId = getCardSectionId(card)
    if (sectionId == null) return
    try {
      await apiRemoveCardFromGroup(boardId.value, sectionId, card.id)
      patchCardSectionLocally(card.id, null)
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.cardRemoveFromSectionSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, {
        context: 'useCorkboardSectionManagement: カードをセクションから外す失敗',
      })
      toast.add({
        severity: 'error',
        summary: t('corkboard.toast.cardSectionChangeError'),
        life: 3500,
      })
    }
  }

  // ----- セクション選択ポップオーバー制御 -----

  /**
   * セクション選択ポップオーバーで現在開いているカード。
   *
   * 1 つの `<Popover>` インスタンスを使い回し、ボタン押下時に
   * `popoverTargetCard` を切り替えてから `popover.show($event)` を呼ぶ運用。
   */
  const popoverTargetCard = ref<CorkboardCardDetail | null>(null)

  return {
    // 折りたたみ管理
    collapsedSections,
    loadCollapsedState,
    toggleSection,
    isSectionCollapsed,
    // セクション CRUD
    sectionEditorMode,
    sectionEditorTarget,
    sectionEditorVisible,
    openCreateSection,
    openEditSection,
    confirmDeleteSection,
    // カード紐付け
    popoverTargetCard,
    getCardSectionId,
    addCardToSection,
    removeCardFromSection,
  }
}
