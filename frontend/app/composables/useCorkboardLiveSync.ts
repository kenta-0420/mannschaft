import type { Ref } from 'vue'
import type { CorkboardDetail, CorkboardEventPayload } from '~/types/corkboard'
import { useCorkboardEventListener } from '~/composables/useCorkboardEventListener'

/**
 * F09.8 Phase F: コルクボード詳細ページの WebSocket リアルタイム同期。
 *
 * `pages/corkboard/[id].vue` から切り出し（フロント技術的負債一掃 Phase 2）。
 *
 * - 共有ボード（TEAM / ORGANIZATION）のみ購読する。PERSONAL は対象外。
 * - 受信イベントは `eventType` 別に push / map / filter で局所更新する。
 *   完成 DTO が同梱されないペイロードは `reload()` でフルリロードへフォールバックする。
 * - ボード切替時は旧 listener を必ず disconnect し、新 listener を再生成する。
 * - 自動的に onUnmounted で listener を解放する。
 *
 * @param board   ボード詳細の ref。eventType 別に局所更新される。
 * @param reload  ボード詳細を再取得するコールバック（フォールバック用）。
 */
export function useCorkboardLiveSync(
  board: Ref<CorkboardDetail | null>,
  reload: () => Promise<void> | void,
) {
  /** 共有ボード（TEAM / ORGANIZATION）か否か。PERSONAL は WebSocket 配信対象外。 */
  const isSharedBoard = computed<boolean>(() => {
    const sc = board.value?.scopeType
    return sc === 'TEAM' || sc === 'ORGANIZATION'
  })

  /** 現在アクティブな購読 listener（ボード切替時に旧 listener を必ず disconnect する） */
  let corkboardListener: ReturnType<typeof useCorkboardEventListener> | null = null
  /** 現在購読中のボード ID（再購読判定に使う） */
  let subscribedBoardId: number | null = null

  /**
   * 受信イベント時のハンドラ（件B: eventType 別の局所更新）。
   *
   * 件B 改修 (2026-05-03):
   *  - これまではイベント受信のたびに `reload()` でボード詳細をフルリロードしていた。
   *  - BE が `card` / `section` の完成 DTO を同梱配信するようになったため、
   *    eventType ごとに push / map / filter で局所更新する。これにより API 呼び出しを
   *    1 回節約でき、UX も向上する（カード追加が「サッと現れる」）。
   *  - 旧 BE / DTO 不在のペイロード（`event.card == null` 等）は `reload()` で
   *    フルリロードへフォールバックし、後方互換を保つ。
   *  - 自身のアクションで既に楽観的更新済みのカードは、受信した完成 DTO で再描画される
   *    （map で id 一致するものを置換するため二重表示にはならない）。
   *  - BOARD_DELETED は当面 `reload()` に倒し、再取得 404 で errorMessage に倒れる挙動に任せる。
   */
  function handleCorkboardEvent(event: CorkboardEventPayload) {
    if (!board.value) return

    switch (event.eventType) {
      case 'CARD_CREATED':
        if (event.card) {
          // 既に同 id のカードがあれば置換、無ければ末尾に追加（多重 push 防止）
          const exists = board.value.cards.some((c) => c.id === event.cardId)
          board.value = {
            ...board.value,
            cards: exists
              ? board.value.cards.map((c) => (c.id === event.cardId ? event.card! : c))
              : [...board.value.cards, event.card],
          }
        } else {
          void reload()
        }
        break

      case 'CARD_UPDATED':
      case 'CARD_MOVED':
      case 'CARD_ARCHIVED':
        if (event.card) {
          const card = event.card
          board.value = {
            ...board.value,
            cards: board.value.cards.map((c) => (c.id === event.cardId ? card : c)),
          }
        } else {
          void reload()
        }
        break

      case 'CARD_DELETED':
        if (event.cardId !== null) {
          board.value = {
            ...board.value,
            cards: board.value.cards.filter((c) => c.id !== event.cardId),
          }
        } else {
          void reload()
        }
        break

      case 'CARD_SECTION_CHANGED':
        if (event.card) {
          const card = event.card
          board.value = {
            ...board.value,
            cards: board.value.cards.map((c) => (c.id === event.cardId ? card : c)),
          }
        } else {
          void reload()
        }
        break

      case 'SECTION_CREATED':
        if (event.section) {
          const exists = board.value.groups.some((g) => g.id === event.sectionId)
          board.value = {
            ...board.value,
            groups: exists
              ? board.value.groups.map((g) =>
                  g.id === event.sectionId ? event.section! : g,
                )
              : [...board.value.groups, event.section],
          }
        } else {
          void reload()
        }
        break

      case 'SECTION_UPDATED':
        if (event.section) {
          const section = event.section
          board.value = {
            ...board.value,
            groups: board.value.groups.map((g) => (g.id === event.sectionId ? section : g)),
          }
        } else {
          void reload()
        }
        break

      case 'SECTION_DELETED':
        if (event.sectionId !== null) {
          const removedSectionId = event.sectionId
          // 件B: section 削除時、紐付くカードの sectionId を null に戻す
          // （V9.097 DDL の ON DELETE SET NULL とフロント表示を整合させる）。
          board.value = {
            ...board.value,
            groups: board.value.groups.filter((g) => g.id !== removedSectionId),
            cards: board.value.cards.map((c) =>
              c.sectionId === removedSectionId ? { ...c, sectionId: null } : c,
            ),
          }
        } else {
          void reload()
        }
        break

      case 'BOARD_DELETED':
        // 当面はリロードに倒す（再取得で 404 → errorMessage 表示）。
        // 将来は一覧へリダイレクト + toast に置き換える。
        void reload()
        break

      default:
        // 未知の eventType（将来 BE 拡張時の前方互換）→ 安全側にフルリロード
        void reload()
        break
    }
  }

  /**
   * ボード詳細の読み込み完了を検知して STOMP 購読を開始する。
   * ボード ID が変わった場合は旧購読を解除してから新規購読する。
   */
  watch(
    [board, isSharedBoard],
    ([newBoard]) => {
      const newBoardId = newBoard?.id ?? null

      // 既に同じボードを購読中なら何もしない
      if (corkboardListener && subscribedBoardId === newBoardId) {
        return
      }

      // ボードが変わった or 共有ボードでなくなった → 旧購読を解除
      if (corkboardListener) {
        corkboardListener.disconnect()
        corkboardListener = null
        subscribedBoardId = null
      }

      // 共有ボードかつボードが読み込み済みのときのみ購読開始
      if (newBoardId !== null && isSharedBoard.value) {
        corkboardListener = useCorkboardEventListener({
          boardId: newBoardId,
          onEvent: handleCorkboardEvent,
        })
        corkboardListener.connect()
        subscribedBoardId = newBoardId
      }
    },
  )

  onUnmounted(() => {
    if (corkboardListener) {
      corkboardListener.disconnect()
      corkboardListener = null
      subscribedBoardId = null
    }
  })

  return {
    isSharedBoard,
  }
}
