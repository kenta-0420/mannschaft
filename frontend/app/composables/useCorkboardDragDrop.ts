import type { Ref } from 'vue'
import { computed } from 'vue'
import { useToast } from 'primevue/usetoast'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 Phase D リファクタリング: D&D 位置更新 composable。
 *
 * `[id].vue` から以下のロジックを抽出した:
 *  - カードサイズプリセット (cardSizePixels)
 *  - D&D 完了時の位置更新 (onPositionChange)
 *    - 楽観的更新: 先にローカル state を新座標で書き換え、API を呼ぶ
 *    - 失敗時: 旧座標へロールバック + toast
 *    - 成功時: 既に UI 反映済みのためサイレント（toast なし）
 *
 * `DraggableCard.vue` から `@update:position` イベントを受け取り、
 * `apiBatchUpdateCardPositions` で単一カードの位置を 1 件配列として送信する。
 */
export function useCorkboardDragDrop(
  board: Ref<CorkboardDetail | null>,
  boardId: Ref<number>,
  tFn?: (key: string) => string,
) {
  const { batchUpdateCardPositions: apiBatchUpdateCardPositions } = useCorkboardApi()
  const { captureQuiet } = useErrorReport()
  const toast = useToast()
  const { t } = tFn ? { t: tFn } : useI18n()

  // ----- カードサイズプリセット -----

  /**
   * カードのサイズプリセット（ピクセル）を返す関数。
   *
   * - SECTION_HEADER: 固定 320x40
   * - SMALL: 150x100
   * - LARGE: 300x200
   * - MEDIUM (デフォルト): 200x150
   *
   * `boardContentSize` 算出やボード描画領域計算に使用する。
   */
  function cardSizePixels(card: CorkboardCardDetail): { width: number; height: number } {
    const size = (card.cardSize ?? 'MEDIUM').toUpperCase()
    if (card.cardType === 'SECTION_HEADER') {
      return { width: 320, height: 40 }
    }
    if (size === 'SMALL') return { width: 150, height: 100 }
    if (size === 'LARGE') return { width: 300, height: 200 }
    return { width: 200, height: 150 }
  }

  /**
   * ボードの全カード・全セクションを内包する最小描画領域サイズ（最低 1200x800）。
   *
   * DraggableCard が位置更新を通知するたびに board が更新されるため、
   * computed で自動追従する。
   */
  const boardContentSize = computed<{ width: number; height: number }>(() => {
    let maxX = 1200
    let maxY = 800
    for (const c of (board.value?.cards ?? [])) {
      const { width, height } = cardSizePixels(c)
      maxX = Math.max(maxX, c.positionX + width + 40)
      maxY = Math.max(maxY, c.positionY + height + 40)
    }
    for (const s of (board.value?.groups ?? [])) {
      maxX = Math.max(maxX, s.positionX + s.width + 40)
      maxY = Math.max(maxY, s.positionY + s.height + 40)
    }
    return { width: maxX, height: maxY }
  })

  // ----- D&D 位置更新 -----

  /**
   * D&D 完了時の位置更新ハンドラ。
   *
   * `DraggableCard.vue` の `@update:position` イベントから (cardId, x, y) を受け取り、
   * 楽観的更新 → API 呼び出し → 失敗時ロールバック の一連の処理を行う。
   */
  async function onPositionChange(cardId: number, x: number, y: number) {
    if (!board.value) return
    const target = board.value.cards.find((c) => c.id === cardId)
    if (!target) return

    const prevX = target.positionX
    const prevY = target.positionY
    const zIndex = target.zIndex ?? 1

    // 楽観的更新: 先に UI を新座標へ反映
    board.value = {
      ...board.value,
      cards: board.value.cards.map((c) =>
        c.id === cardId ? { ...c, positionX: x, positionY: y } : c,
      ),
    }

    try {
      await apiBatchUpdateCardPositions(boardId.value, [
        { cardId, positionX: x, positionY: y, zIndex },
      ])
      // 成功時はサイレント（楽観的更新で既に UI 反映済み）
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardDragDrop: カード位置更新失敗' })
      // ロールバック: 旧座標に戻す
      if (board.value) {
        board.value = {
          ...board.value,
          cards: board.value.cards.map((c) =>
            c.id === cardId ? { ...c, positionX: prevX, positionY: prevY } : c,
          ),
        }
      }
      toast.add({
        severity: 'error',
        summary: t('corkboard.dnd.updateError'),
        life: 3500,
      })
    }
  }

  return {
    cardSizePixels,
    boardContentSize,
    onPositionChange,
  }
}
