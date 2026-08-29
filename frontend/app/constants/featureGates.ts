/**
 * 未公開機能の route 層隔離（Gate 基盤工事②）の URL パスプレフィクス対応表と純関数群。
 *
 * ## gate_key の発行元
 * `gate_key` の**唯一の発行元は棚卸し台帳** `docs/inventory/feature-inventory.yaml` の
 * `release.gate_key`（SCREAMING_SNAKE）である。本ファイルはそれに **route を束縛する**だけで、
 * 新しい gate_key をここで生み出してはならない。
 *
 * ## なぜ FE 側の定数なのか（YAML を読まない理由）
 * FE に YAML パーサ依存を足したりビルド時コード生成を挟むと、FE のビルド経路が台帳の書式に
 * 結合してしまう。よって束縛表は素の TypeScript 定数として持ち、台帳との二重管理の破綻は
 * 番人テスト `FeatureGateRouteMapGuardTest`（BE 側・ソース走査型）が CI で検出する。
 *
 * ## なぜ global middleware ＋ 対応表なのか（definePageMeta 自己申告にしない理由）
 * named middleware ＋ `definePageMeta` の自己申告方式は、宣言忘れがそのまま fail-open
 * （= 未公開機能の漏洩）になる。global middleware ＋ 対応表なら、漏れは「余計に弾く」側へ倒れる。
 */

/** 拒否時の戻り先。ガード対象外である必要がある（無限リダイレクト防止）。 */
export const GATE_FALLBACK_PATH = '/dashboard'

/**
 * gate_key → ガード対象の URL パスプレフィクス。
 *
 * 台帳で隔離対象（`release.beta` が β限定 / 内部限定 / 停止）かつ `gate_key` が発行済みの機能は、
 * ここに必ず束縛が必要（番人 `FeatureGateRouteMapGuardTest` の (ii) が検出する）。
 * 実ページを1枚も持たない機能だけ台帳側の `gate_key` を null のままにする
 * （現時点では weather-health のみ）。
 *
 * **入口だけでなく所属ページを網羅すること。** `/shift` を1本書いても `/my/shift` や
 * `/teams/{slug}/shifts` は覆われない。番人 `FeatureGatePageCoverageGuardTest` が
 * `frontend/app/pages/` を走査し、台帳の `release.route_keywords` に一致するのに
 * 覆われていないページを red にする。
 *
 * **束縛しないページは台帳の `route_coverage_exclusions` に理由付きで宣言する。**
 * 配信停止（`/ads/unsubscribe`）や招待受諾（`/care-links/invitations/[token]`）は
 * `definePageMeta({ auth: false })` の未ログイン導線であり、塞ぐと機能自体が成立しないため除外した。
 */
export const GATE_ROUTE_MAP: Record<string, string[]> = {
  FEATURE_SHIFT_ENABLED: [
    '/shift',
    '/my/shift',
    '/my/shifts',
    '/my/shift-availability',
    '/my/shift-request',
    '/admin/shift-budget',
    '/teams/*/shifts',
    '/teams/*/settings/shift',
  ],
  FEATURE_MATCHING_ENABLED: [
    '/matching',
    '/jobs',
    '/me/jobs',
    '/me/match-analytics',
    '/teams/*/jobs',
    '/teams/*/matching',
    '/teams/*/match-analytics',
    '/teams/*/members/*/match-analytics',
  ],
  FEATURE_BILLING_PAYMENT_ENABLED: [
    '/billing',
    '/payments',
    '/contracts',
    '/wallet',
    '/settings/billing',
    '/admin/org-billing',
    '/system-admin/billing',
    '/me/payments',
    '/organizations/*/payments',
    '/organizations/*/settings/billing',
    '/teams/*/payments',
    '/teams/*/billing',
    '/teams/*/settings/billing',
  ],
  FEATURE_PROMOTION_ENABLED: [
    '/me/ad-deliveries',
    '/settings/ad-preferences',
    '/admin/ad-credit-limit-requests',
    '/admin/ad-rate-cards',
    '/admin/advertiser-accounts',
    '/admin/campaigns',
    '/admin/promotions',
    '/system-admin/advertising',
    '/organizations/*/advertiser',
    '/organizations/*/signage',
    '/teams/*/advertiser',
    '/teams/*/signage',
  ],
  FEATURE_MARKET_ENABLED: [
    '/market',
    '/organizations/*/market',
    '/teams/*/market',
  ],
  FEATURE_WORKFLOW_FORMS_ENABLED: [
    '/organizations/*/forms',
    '/organizations/*/workflows',
    '/teams/*/forms',
    '/teams/*/workflows',
  ],
  FEATURE_FACILITY_ENABLED: [
    '/admin/equipment',
    '/organizations/*/equipment',
    '/organizations/*/facilities',
    '/organizations/*/parking',
    '/teams/*/equipment',
    '/teams/*/facilities',
    '/teams/*/parking',
  ],
  FEATURE_PROPERTY_REPAIRPLAN_ENABLED: [
    '/property-disclosure',
    '/property-history',
    '/teams/*/repair-plan',
  ],
  FEATURE_FAMILY_CARE_ENABLED: [
    '/families',
    '/me/care-links',
    '/me/guardianship',
    '/teams/*/school-attendance',
  ],
  FEATURE_SKILL_RESUME_ENABLED: [
    '/my/resume',
    '/teams/*/skills',
  ],
  FEATURE_RECRUITMENT_ENABLED: [
    '/recruitment-listings',
    '/me/recruitment-listings',
    '/me/recruitment-feed',
    '/me/recruitment-cancellation-fees',
    '/me/recruitment-payments',
    '/organizations/*/recruitment-listings',
    '/teams/*/recruitment-listings',
    '/villages/*/admin/recruit-categories',
    '/villages/*/match-recruits',
  ],
  FEATURE_SUCCESSION_PROXY_ENABLED: [
    '/admin/proxy-desk',
    '/my/proxy-requests',
    '/organizations/*/succession',
  ],
  FEATURE_GDPR_DISCLOSURE_ENABLED: [
    '/system-admin/gdpr',
  ],
  FEATURE_MODERATION_INCIDENT_ENABLED: [
    '/admin/moderation',
    '/system-admin/incident-banners',
    '/organizations/*/incidents',
    '/teams/*/incidents',
  ],
  FEATURE_WEBHOOK_SYNC_ENABLED: [
    '/sync',
    '/settings/calendar-sync',
    '/organizations/*/webhooks',
    '/teams/*/webhooks',
  ],
  FEATURE_TRANSLATION_SEARCH_ENABLED: [
    '/organizations/*/translations',
    '/organizations/*/analytics',
    '/teams/*/analytics',
    '/system-admin/analytics',
  ],
  FEATURE_GAMIFICATION_ENABLED: [
    '/organizations/*/gamification',
    '/organizations/*/supporters',
    '/teams/*/gamification',
    '/teams/*/supporters',
  ],
}

/** 末尾スラッシュを落として比較用に正規化する（`/shift/` と `/shift` を同一視する）。 */
function normalizePath(path: string): string {
  if (path.length > 1 && path.endsWith('/')) {
    return path.replace(/\/+$/, '') || '/'
  }
  return path
}

/** パスをセグメント配列にする（先頭の空要素を落とす）。 */
function segments(path: string): string[] {
  return normalizePath(path).split('/').filter((s) => s.length > 0)
}

/**
 * プレフィクスがパスを覆うか判定する（セグメント単位）。
 *
 * プレフィクスの `*` は**1セグメントちょうど**にマッチする（動的セグメント用）。
 * 例: `/teams/{slug}/shifts` は `/teams/my-team/shifts` と `/teams/my-team/shifts/1/board` を覆い、
 *     `/teams/my-team/settings` は覆わない。
 *
 * 素の `startsWith` は隣接名を巻き込む（`/shift` が `/shift-budget` を巻き込む）ため使わない。
 * セグメント単位で比較することで境界は自動的に守られる。
 */
export function prefixCovers(prefix: string, path: string): boolean {
  const pre = segments(prefix)
  const target = segments(path)
  if (target.length < pre.length) return false
  for (let i = 0; i < pre.length; i++) {
    const p = pre[i]!
    if (p === '*') continue
    if (p !== target[i]) return false
  }
  return true
}

/**
 * パスに対応する gate_key を返す（対象外なら null）。
 *
 * 複数のプレフィクスに一致した場合は**最長一致**（セグメント数が多い方）を採る。
 */
export function matchGateKey(path: string): string | null {
  let matchedKey: string | null = null
  let matchedDepth = -1

  for (const [gateKey, prefixes] of Object.entries(GATE_ROUTE_MAP)) {
    for (const prefix of prefixes) {
      const depth = segments(prefix).length
      if (depth > matchedDepth && prefixCovers(prefix, path)) {
        matchedKey = gateKey
        matchedDepth = depth
      }
    }
  }

  return matchedKey
}

/**
 * ガード対象パスのうち<b>静的プレフィクスだけ</b>を SSR 対象外（クライアント描画のみ）にする
 * Nitro の routeRules を組み立てる。
 *
 * SSR 実行時はフラグを取得できない（公開フラグ API は localStorage のトークンに依存し、
 * ①の plugin は `.client` 限定）。`ssr: false` にしたパスについては、
 * フラグが未確定な状態で未公開ページの HTML がサーバーから出力されることを防げる。
 *
 * <h2>守備範囲は全域ではない（過大に読まないこと）</h2>
 * <b>`*`（動的セグメント）を含むプレフィクスは routeRules に出さないため、SSR 抑止が掛からない。</b>
 * 実測の内訳は次のとおりで、<b>およそ半分が抑止の対象外</b>である。
 * <ul>
 *   <li>静的プレフィクス（`ssr: false` を出す） … 47 件</li>
 *   <li>動的プレフィクス（<b>出さない = SSR 抑止なし</b>） … 44 件（`/teams/{slug}/…`・
 *       `/organizations/{slug}/…` 系がまるごと該当）</li>
 * </ul>
 * この 44 経路は <b>routeRules による SSR 抑止も middleware 判定も掛からない</b>
 * （middleware は SSR では `ssr-defer` で一切判定しないため）。
 * 隔離は<b>ハイドレーション後のクライアント側判定だけに依存する</b>。
 *
 * 帰結: <b>フラグを閉じた状態では、未公開機能の「画面の外枠」（見出し・ラベル・空の表）が
 * SSR 出力に露出しうる。</b>該当ページは `middleware: 'auth'` を持ち、データ取得も
 * `onMounted` のクライアント限定なので載るのはデータではなく枠に留まる。
 * seed 済みフラグが全て有効な現時点では実際に漏れるものは無く、
 * <b>この穴が効いてくるのは β公開時にフラグを閉じた後</b>である。
 * 追跡: docs/inventory/feature-inventory.yaml の各対象行の blockers と Issue #2888。
 *
 * <h2>なぜ `/teams/**` まで広げないのか（判断であって、防げているという主張ではない）</h2>
 * Nitro の routeRules は Vue Router の動的セグメント（`[slug]`）を解さないため、
 * `/teams/{slug}/shifts` を表現するには `/teams/**` まで広げるしかない。しかしそれは
 * <b>チーム配下の全ページの SSR を止める</b>ことを意味し、
 * `slug-redirect.global.ts` が SSR 実行時にのみ返せる「本物の HTTP 301」（旧 slug → 新 slug）を
 * 丸ごと壊す。SEO・ブックマークの継承という出荷済みの契約を壊す代償の方が、
 * 枠の露出より大きいと判断した。
 *
 * この非対称性は {@code tests/unit/constants/featureGates.spec.ts} が件数比ごと固定している
 * （静的/動的の内訳が変わったらテストが落ちて気付けるようにするため）。
 */
export function buildGateRouteRules(): Record<string, { ssr: false }> {
  const rules: Record<string, { ssr: false }> = {}
  for (const prefixes of Object.values(GATE_ROUTE_MAP)) {
    for (const prefix of prefixes) {
      if (prefix.includes('*')) continue
      rules[prefix] = { ssr: false }
      rules[`${prefix}/**`] = { ssr: false }
    }
  }
  return rules
}

/** ガード判定の結果。 */
export type GateDecision =
  | { action: 'pass' }
  | { action: 'ssr-defer' }
  | { action: 'ensure' }
  | { action: 'deny', gateKey: string }

/**
 * ガード判定の純関数（三値判定: enabled / disabled / unknown）。
 *
 * - 対象外パス → `pass`（フラグストアに一切触れない = happy-path 非干渉）
 * - SSR → `ssr-defer`。SSR では判定材料が無いので「通す」も「弾く」もしない。
 *   SSR 出力の抑止は {@link buildGateRouteRules} の `ssr: false` が担うが、
 *   <b>それが掛かるのは静的プレフィクスのみ</b>で、動的セグメントを含む経路は抑止対象外である
 *   （詳細と帰結は {@link buildGateRouteRules} の doc を参照）。
 * - **未認証 → `pass`**。公開フラグ API は認証必須なので、ここで取得を試みると 401 になり
 *   「取得失敗 = 503 フルページ」に化ける。未ログインでメール内リンク（例 `/contracts/123`）を
 *   開いた利用者が /login へ誘導されず 503 に叩き落とされる事故になるため、判定せず
 *   後段の named `auth` ミドルウェアへ委ねる（ログイン後に同じパスへ戻り、そこで正しく判定される）。
 *   なお、この委譲が穴にならないことは「ガード対象プレフィクス配下に auth:false のページを
 *   置かせない」番人（FeatureGatePageCoverageGuardTest）が担保する。
 * - 公開フラグ未取得（unknown） → `ensure`。**素通りさせない**（fail-open 禁止）。
 * - 取得済み → `enabled` の真偽で `pass` / `deny`。
 */
export function decideGate(input: {
  path: string
  isServer: boolean
  isAuthenticated: boolean
  publicLoaded: boolean
  enabled: (gateKey: string) => boolean
}): GateDecision {
  const gateKey = matchGateKey(input.path)
  if (gateKey === null) return { action: 'pass' }
  if (input.isServer) return { action: 'ssr-defer' }
  // 未認証は判定せず後段の auth ミドルウェアに委ねる（詳細は上の doc コメント参照）。
  if (!input.isAuthenticated) return { action: 'pass' }
  if (!input.publicLoaded) return { action: 'ensure' }
  return input.enabled(gateKey) ? { action: 'pass' } : { action: 'deny', gateKey }
}
