/**
 * F09.8.1 / F09.8 件3': ピン止め管理 composable。
 *
 * `[id].vue` から以下のロジックを抽出:
 *  - ピン止め・アンピン操作（togglePin / doTogglePin）
 *  - ピン上限エラーの専用 toast 表示（isPinLimitError）
 *  - 付箋メモ popover 制御（pinPopoverVisible / pinPopoverTargetCard / onPinNoteConfirm）
 *
 * 設計メモ:
 *  - useI18n().t は呼び出し元から注入する（Vitest 環境での setup 制約を回避するため）。
 */
import { useToast } from 'primevue/usetoast'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

interface PinErrorPayload {
  data?: { code?: string }
  response?: { status?: number; _data?: { code?: string } }
  status?: number
  statusCode?: number
}

function isPinLimitError(e: unknown): boolean {
  if (!e || typeof e !== 'object') return false
  const err = e as PinErrorPayload
  const status = err.status ?? err.statusCode ?? err.response?.status
  const code = err.data?.code ?? err.response?._data?.code
  if (status === 409) return true
  if (code === 'CORKBOARD_013') return true
  return false
}

export function useCorkboardPinManagement(
  board: Ref<CorkboardDetail | null>,
  boardId: Ref<number>,
  /** useI18n().t を呼び出し元から注入（Vitest 環境での setup 制約を回避するため） */
  t: (key: string) => string,
) {
  const toast = useToast()
  const { captureQuiet } = useErrorReport()
  const { togglePinCard: apiTogglePinCard } = useCorkboardApi()

  // ----- 付箋メモ popover 制御 -----

  const pinPopoverVisible = ref(false)
  const pinPopoverTargetCard = ref<CorkboardCardDetail | null>(null)

  /**
   * ピン止めボタン押下ハンドラ。
   *
   * - 未ピンカードの押下 → PinNoteEditorPopover を開く（付箋メモ・色を入力させる）
   * - ピン済カードの押下 → 即時アンピン（付箋メモは触らない）
   */
  function togglePin(card: CorkboardCardDetail) {
    if (card.isPinned) {
      // アンピン（即時）
      void doTogglePin(card, false, null, null)
      return
    }
    // ピン止め: Popover を開いて付箋メモ・色を入力させる
    pinPopoverTargetCard.value = card
    pinPopoverVisible.value = true
  }

  /** PinNoteEditorPopover の「ピン止めする」確定時コールバック。 */
  function onPinNoteConfirm(userNote: string, noteColor: string) {
    const card = pinPopoverTargetCard.value
    if (!card) return
    void doTogglePin(card, true, userNote, noteColor)
  }

  /**
   * ピン止め状態の切り替えを実行する。
   *
   * - 楽観的更新なし（PATCH 完了後にローカル state を更新）。
   * - 上限到達（409 / CORKBOARD_013）の場合は専用 warn toast を表示。
   * - アンピン成功後は pinPopoverTargetCard を null にリセットしない（既にアンピンなので不要）。
   * - ピン成功後は pinPopoverTargetCard を null にリセットする。
   */
  async function doTogglePin(
    card: CorkboardCardDetail,
    next: boolean,
    userNote: string | null,
    noteColor: string | null,
  ) {
    try {
      const res = await apiTogglePinCard(
        boardId.value,
        card.id,
        next,
        // アンピン時はサーバ側で無視されるが、明示的に undefined を送って差分最小化
        next ? userNote : undefined,
        next ? noteColor : undefined,
      )
      if (board.value) {
        board.value = {
          ...board.value,
          cards: board.value.cards.map((c) =>
            c.id === card.id
              ? {
                  ...c,
                  isPinned: res.data.isPinned,
                  pinnedAt: res.data.pinnedAt,
                  // pin 時に書き込んだ値があればローカル state へも反映
                  userNote: next && userNote !== null ? userNote : c.userNote,
                  noteColor: next && noteColor !== null ? noteColor : c.noteColor,
                }
              : c,
          ),
        }
      }
      toast.add({
        severity: 'success',
        summary: next ? t('corkboard.toast.pinSuccess') : t('corkboard.toast.unpinSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardPinManagement: ピン止め切替失敗' })
      if (next && isPinLimitError(e)) {
        toast.add({
          severity: 'warn',
          summary: t('corkboard.pinLimitReached'),
          life: 4000,
        })
        return
      }
      toast.add({
        severity: 'error',
        summary: next ? t('corkboard.toast.pinError') : t('corkboard.toast.unpinError'),
        life: 3500,
      })
    } finally {
      if (next) {
        pinPopoverTargetCard.value = null
      }
    }
  }

  return {
    pinPopoverVisible,
    pinPopoverTargetCard,
    togglePin,
    onPinNoteConfirm,
    doTogglePin,
    isPinLimitError,
  }
}
