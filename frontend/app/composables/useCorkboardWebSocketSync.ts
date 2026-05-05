/**
 * F09.8 Phase F リファクタリング: WebSocket リアルタイム同期 composable。
 *
 * `[id].vue` から以下のロジックを抽出した:
 *  - isSharedBoard computed（TEAM / ORGANIZATION のみ WebSocket 購読する判定）
 *  - handleCorkboardEvent() 関数（eventType 別の board.value 局所更新。10種類）
 *  - watch([board, isSharedBoard]) → connect / disconnect のトリガー
 *  - onUnmounted() → disconnect クリーンアップ
 *  - E2E テスト用フック（window.__corkboardE2eEmit）
 *
 * 設計方針:
 *  - useCorkboardEventListener の connect / disconnect を内部で管理し、
 *    呼び出し元は WebSocket 購読の詳細を意識しない設計にする。
 *  - board / isSharedBoard の変化を watch して自動で再購読する。
 *  - onUnmounted でリソースを確実に解放する。
 *  - E2E 専用フック（__corkboardE2eEmit）は本番環境では window.__E2E__ が
 *    未定義なため公開されない。
 */
import { computed, watch, onMounted, onUnmounted } from 'vue'
import type { Ref } from 'vue'
import type { CorkboardDetail, CorkboardEventPayload } from '~/types/corkboard'

export function useCorkboardWebSocketSync(
  board: Ref<CorkboardDetail | null>,
) {

  // ----- 共有ボード判定 -----

  /**
   * 共有ボード（TEAM / ORGANIZATION）か否か。
   * 個人ボード（PERSONAL）は WebSocket 配信対象外なので購読をスキップする。
   */
  const isSharedBoard = computed<boolean>(() => {
    const sc = board.value?.scopeType
    return sc === 'TEAM' || sc === 'ORGANIZATION'
  })

  // ----- WebSocket 購読状態 -----

  /** 現在アクティブな購読 listener（ボード切替時に旧 listener を必ず disconnect する） */
  let corkboardListener: ReturnType<typeof useCorkboardEventListener> | null = null
  /** 現在購読中のボード ID（再購読判定に使う） */
  let subscribedBoardId: number | null = null

  // ----- イベントハンドラ -----

  /**
   * 受信イベント時のハンドラ（件B: eventType 別の局所更新）。
   *
   * 件B 改修 (2026-05-03):
   *  - BE が `card` / `section` の完成 DTO を同梱配信するようになったため、
   *    eventType ごとに push / map / filter で局所更新する。これにより API 呼び出しを
   *    1 回節約でき、UX も向上する（カード追加が「サッと現れる」）。
   *  - 旧 BE / DTO 不在のペイロード（`event.card == null` 等）は `void load()` で
   *    フルリロードへフォールバックし、後方互換を保つ。
   *  - 自身のアクションで既に楽観的更新済みのカードは、受信した完成 DTO で再描画される
   *    （map で id 一致するものを置換するため二重表示にはならない）。
   *  - BOARD_DELETED は当面 `void _reloadFn()` に倒し、再取得 404 で errorMessage に倒れる挙動に任せる。
   */
  function handleCorkboardEvent(event: CorkboardEventPayload): void {
    if (!board.value) return

    switch (event.eventType) {
      case 'CARD_CREATED': {
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
          _reloadFn()
        }
        break
      }

      case 'CARD_UPDATED':
      case 'CARD_MOVED':
      case 'CARD_ARCHIVED': {
        if (event.card) {
          const card = event.card
          board.value = {
            ...board.value,
            cards: board.value.cards.map((c) => (c.id === event.cardId ? card : c)),
          }
        } else {
          _reloadFn()
        }
        break
      }

      case 'CARD_DELETED': {
        if (event.cardId !== null) {
          board.value = {
            ...board.value,
            cards: board.value.cards.filter((c) => c.id !== event.cardId),
          }
        } else {
          _reloadFn()
        }
        break
      }

      case 'CARD_SECTION_CHANGED': {
        if (event.card) {
          const card = event.card
          board.value = {
            ...board.value,
            cards: board.value.cards.map((c) => (c.id === event.cardId ? card : c)),
          }
        } else {
          _reloadFn()
        }
        break
      }

      case 'SECTION_CREATED': {
        if (event.section) {
          const exists = board.value.groups.some((g) => g.id === event.sectionId)
          board.value = {
            ...board.value,
            groups: exists
              ? board.value.groups.map((g) => (g.id === event.sectionId ? event.section! : g))
              : [...board.value.groups, event.section],
          }
        } else {
          _reloadFn()
        }
        break
      }

      case 'SECTION_UPDATED': {
        if (event.section) {
          const section = event.section
          board.value = {
            ...board.value,
            groups: board.value.groups.map((g) => (g.id === event.sectionId ? section : g)),
          }
        } else {
          _reloadFn()
        }
        break
      }

      case 'SECTION_DELETED': {
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
          _reloadFn()
        }
        break
      }

      case 'BOARD_DELETED': {
        // 当面はリロードに倒す（再取得で 404 → errorMessage 表示）。
        // 将来は一覧へリダイレクト + toast に置き換える。
        _reloadFn()
        break
      }

      default: {
        // 未知の eventType（将来 BE 拡張時の前方互換）→ 安全側にフルリロード
        _reloadFn()
        break
      }
    }
  }

  // ----- フルリロード関数（外部注入） -----

  /**
   * フルリロード関数。
   * `handleCorkboardEvent` 内でフォールバック時に呼び出される。
   * `setReloadFn()` で外部から注入する（循環参照を回避するため）。
   */
  let _reloadFn: () => void = () => {
    // デフォルトは no-op（setReloadFn が呼ばれる前の安全策）
  }

  /**
   * フルリロード関数を注入する。
   * `[id].vue` のセットアップ処理で `useCorkboardDetail().load` を渡す。
   *
   * @example
   * ```ts
   * const { setReloadFn, handleCorkboardEvent } = useCorkboardWebSocketSync(board, boardId, t)
   * const { load } = useCorkboardDetail(t)
   * setReloadFn(() => { void load() })
   * ```
   */
  function setReloadFn(fn: () => void): void {
    _reloadFn = fn
  }

  // ----- 購読管理 -----

  /**
   * ボード詳細の読み込み完了を検知して STOMP 購読を開始する。
   * ボード ID が変わった場合は旧購読を解除してから新規購読する。
   * `immediate: true` により、初回マウント時（board がすでにロード済みの場合）にも即座に発火する。
   */
  watch(
    [board, isSharedBoard] as const,
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
    { immediate: true },
  )

  onUnmounted(() => {
    if (corkboardListener) {
      corkboardListener.disconnect()
      corkboardListener = null
      subscribedBoardId = null
    }
    // E2E テストフック解除
    if (typeof window !== 'undefined') {
      const w = window as unknown as { __corkboardE2eEmit?: unknown }
      if (w.__corkboardE2eEmit) {
        delete w.__corkboardE2eEmit
      }
    }
  })

  // ----- E2E テスト専用フック -----

  /**
   * F09.8 件B 追補 E2E 専用フック。
   *
   * Playwright が `addInitScript` で `window.__E2E__ = true` を注入したときのみ
   * `window.__corkboardE2eEmit(payload)` を公開し、テストから WebSocket 受信イベントを
   * シミュレートできるようにする。本番環境では `__E2E__` が未定義のため公開されず、
   * バンドルにも追加 API は残らない（ただの no-op）。
   */
  onMounted(() => {
    if (typeof window === 'undefined') return
    const w = window as unknown as {
      __E2E__?: boolean
      __corkboardE2eEmit?: (payload: CorkboardEventPayload) => void
    }
    if (w.__E2E__) {
      w.__corkboardE2eEmit = (payload: CorkboardEventPayload) => {
        handleCorkboardEvent(payload)
      }
    }
  })

  return {
    isSharedBoard,
    handleCorkboardEvent,
    setReloadFn,
  }
}
