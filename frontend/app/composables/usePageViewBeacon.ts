/**
 * ページビュービーコン送信 composable（F10.8）。
 *
 * SPA の1遷移に対して1回ビーコンを送出する。
 * 同一URL への連続再送は行わない（§5.5: 二重カウント防止）。
 *
 * 設計書: docs/features/F10.8_team_org_access_analytics.md §3.1, §5.5
 */

/** ビーコン POST のリクエストボディ型 */
export interface PageViewBeaconBody {
  /** スコープ種別 */
  scope: 'TEAM' | 'ORGANIZATION'
  /** スコープ数値 ID（slug ではなく内部 ID） */
  scopeId: number
  /** コンテンツ種別 */
  contentType: 'ARTICLE' | 'ACTIVITY' | 'PAGE' | 'TEAM'
  /** コンテンツ ID（PAGE 等 ID 無し時は 0 固定） */
  contentId: number
  /** 閲覧 URL（相対パス） */
  url: string
  /** ページタイトル */
  title: string
}

/**
 * ページビュービーコンを送信する composable。
 *
 * 使用例（analytics ページで自身のページビューを計測する場合）:
 * ```ts
 * const { sendBeacon } = usePageViewBeacon()
 * onMounted(() => {
 *   sendBeacon({
 *     scope: 'TEAM',
 *     scopeId: numericTeamId,
 *     contentType: 'PAGE',
 *     contentId: 0,
 *     url: route.path,
 *     title: 'アクセス解析',
 *   })
 * })
 * ```
 */
export function usePageViewBeacon() {
  const api = useApi()

  /** 最後に送信した URL（同一 URL の連続再送を防ぐ） */
  let lastSentUrl: string | null = null

  /**
   * ビーコンを送信する。
   *
   * 送信失敗は UX を止めないが、コンソールに記録する（対処療法の握りつぶし禁止）。
   */
  function sendBeacon(body: PageViewBeaconBody): void {
    const currentUrl = body.url

    // 同一 URL への連続再送は行わない（§5.5 の二重カウント防止）
    if (lastSentUrl === currentUrl) {
      return
    }
    lastSentUrl = currentUrl

    // ビーコン送信は非同期・fire-and-forget。失敗は UX を止めない
    api('/api/v1/page-views', {
      method: 'POST',
      body,
    }).catch((error: unknown) => {
      // 計測なので UX は止めないが、ログは残す（対処療法禁止・障害対応原則）
      console.warn('[usePageViewBeacon] ビーコン送信に失敗しました:', error)
    })
  }

  /**
   * router.afterEach で自動的にビーコンを送るフックを登録する。
   *
   * setup context 内で呼ぶ必要がある（onUnmounted でのクリーンアップのため）。
   *
   * @param getBody - 遷移後のルートから送信ボディを組み立てる関数。
   *                  null を返した場合は送信しない（スキップ）。
   */
  function setupRouterBeacon(getBody: () => PageViewBeaconBody | null): void {
    const router = useRouter()
    // afterEach フックは setup context 内で登録すれば onUnmounted で自動クリーンアップされる
    router.afterEach((_to, _from, failure) => {
      // ナビゲーション失敗時はビーコン送信しない
      if (failure) return

      const body = getBody()
      if (body) {
        sendBeacon(body)
      }
    })
  }

  return {
    sendBeacon,
    setupRouterBeacon,
  }
}
