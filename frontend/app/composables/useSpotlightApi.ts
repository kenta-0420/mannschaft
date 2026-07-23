import type { components } from '~/types/generated'

/**
 * F09.19 サービング/計測 spotlight 掲載面の API クライアント（中立命名・広告ブロッカー耐性）。
 *
 * <p>対応 BE（F09.19.2 / origin/main 実装済み）:</p>
 * <ul>
 *   <li>GET  {@code /api/v1/spotlight/content} … 掲載面の広告候補を取得</li>
 *   <li>POST {@code /api/v1/spotlight/{creativeId}/view} … インプレッション計上（HOUSE のみ）</li>
 *   <li>POST {@code /api/v1/spotlight/{creativeId}/visit} … クリック計上（HOUSE のみ）</li>
 * </ul>
 *
 * <p>受信設定・有料プランゲート・serve 証跡は全て BE が判定する。FE は
 * 「items が空 or エラー → 枠を描画しない」の単純規則だけを持つ（設計 §7.5 / §8）。</p>
 */

/** {@code /spotlight/content} の items[n]（生成型を直接使用）。 */
export type SpotlightItem = components['schemas']['SpotlightItem']
export type SpotlightHouseItem = components['schemas']['SpotlightHouseItem']
export type SpotlightAffiliateItem = components['schemas']['SpotlightAffiliateItem']
type SpotlightContentResponse = components['schemas']['SpotlightContentResponse']
type SpotlightViewRequest = components['schemas']['SpotlightViewRequest']
type SpotlightViewResponse = components['schemas']['SpotlightViewResponse']
type SpotlightVisitRequest = components['schemas']['SpotlightVisitRequest']

/**
 * 掲載面（AdPlacement）語彙。BE enum {@code com.mannschaft.app.advertising.AdPlacement} と一致。
 * 掲載面が実装済みなのは {@code DASHBOARD_TILE} / {@code IN_FEED} の 2 値（設計 §3）。
 */
export type AdPlacement =
  | 'DASHBOARD_TILE'
  | 'IN_FEED'
  | 'SIDEBAR_RIGHT'
  | 'BANNER_FOOTER'
  | 'BANNER_HEADER'

/** 掲載面のスコープ種別（BE {@code scopeType} クエリに渡す大文字表記）。 */
export type SpotlightScopeType = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

/** {@link useSpotlightApi.fetchContent} のスコープ／ターゲティング付帯情報。 */
export interface SpotlightFetchOptions {
  scopeType?: SpotlightScopeType
  /** TEAM / ORGANIZATION 時に必須（数値 ID）。BE は Long を要求する。 */
  scopeId?: number
  /** スコープのテンプレート slug（アフィリエイトターゲティング用。例 CLUB）。 */
  template?: string
  /** 都道府県コード（アフィリエイトターゲティング用）。 */
  prefecture?: string
  /** ロケール（既定 ja）。 */
  locale?: string
}

export function useSpotlightApi() {
  const api = useApi()

  /**
   * 掲載面の広告候補を取得する。
   *
   * <p>広告は非必須要素であり、失敗してもページ本体の描画を阻害してはならない（AC-4.9）。
   * そのため API エラー時は例外を握りつぶすのではなく、設計上の
   * グレースフルデグラデーション（items:[] = 枠非表示）として空配列を返す。</p>
   *
   * @param placement 掲載面
   * @param count 返却候補数（1〜2）
   * @param opts スコープ・ターゲティング付帯情報
   */
  async function fetchContent(
    placement: AdPlacement,
    count: number,
    opts: SpotlightFetchOptions = {},
  ): Promise<SpotlightItem[]> {
    const params: Record<string, string | number> = { placement, count }
    if (opts.scopeType) params.scopeType = opts.scopeType
    if (opts.scopeId != null) params.scopeId = opts.scopeId
    if (opts.template) params.template = opts.template
    if (opts.prefecture) params.prefecture = opts.prefecture
    if (opts.locale) params.locale = opts.locale

    try {
      const res = await api<{ data: SpotlightContentResponse }>('/api/v1/spotlight/content', {
        params,
      })
      return res.data.items ?? []
    } catch (e) {
      // 設計 §7.5 / AC-4.9: 広告取得の失敗はページ描画をブロックしない（枠を出さないだけ）。
      // ただし握りつぶさず「劣化を記録して継続」する（観測性の確保）。
      console.warn('[spotlight] fetchContent failed', e)
      return []
    }
  }

  /**
   * インプレッション計上（HOUSE のみ）。可視判定（50% × 1 秒）後に 1 回だけ呼ぶ。
   *
   * <p>計測はベストエフォート。serve 証跡切れ等で 4xx になっても UX を止めないため、
   * 失敗時は null を返す（設計 §6.3）。</p>
   */
  async function recordView(
    creativeId: number,
    body: SpotlightViewRequest,
  ): Promise<SpotlightViewResponse | null> {
    try {
      const res = await api<{ data: SpotlightViewResponse }>(
        `/api/v1/spotlight/${creativeId}/view`,
        { method: 'POST', body },
      )
      return res.data
    } catch {
      // eslint-disable-next-line no-restricted-syntax -- 広告ビュー計上はベストエフォート（設計 §6.3）。失敗は表示体験に影響させず null 返却
      return null
    }
  }

  /**
   * クリック計上（HOUSE のみ・fire-and-forget）。
   *
   * <p>呼び出し側はレスポンスを待たずに遷移してよい（計測欠損より UX を優先。設計 §6.4）。
   * 失敗しても遷移を妨げないよう例外は握りつぶす。</p>
   */
  function recordVisit(creativeId: number, body: SpotlightVisitRequest): void {
    void api(`/api/v1/spotlight/${creativeId}/visit`, { method: 'POST', body }).catch(() => {
      // fire-and-forget: 計測失敗は無視（遷移は既にネイティブに実行済み）
    })
  }

  return { fetchContent, recordView, recordVisit }
}
