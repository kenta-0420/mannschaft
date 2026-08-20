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
 * ここに必ず束縛が必要（番人 (ii) が検出する）。逆に専用の top-level route を持たない機能は
 * 台帳側の `gate_key` を null のままにしておく（route 層の対象外であることを台帳上で明示する）。
 *
 * **公開ページ（`definePageMeta({ auth: false })`）は束縛しない。**
 * 配信停止（`/ads/unsubscribe`）や招待受諾（`/care-links/invitations/[token]`）は
 * 未ログインの第三者が踏む導線であり、機能の隔離とは目的が異なるため対象外とした。
 */
export const GATE_ROUTE_MAP: Record<string, string[]> = {
  SHIFT: ['/shift'],
  MATCHING: ['/matching', '/jobs'],
  BILLING_PAYMENT: ['/billing', '/payments', '/contracts'],
  MARKET: ['/market'],
  PROPERTY_REPAIRPLAN: ['/property-disclosure', '/property-history'],
  FAMILY_CARE: ['/families'],
  RECRUITMENT: ['/recruitment-listings'],
  WEBHOOK_SYNC: ['/sync'],
}

/** 末尾スラッシュを落として比較用に正規化する（`/shift/` と `/shift` を同一視する）。 */
function normalizePath(path: string): string {
  if (path.length > 1 && path.endsWith('/')) {
    return path.replace(/\/+$/, '') || '/'
  }
  return path
}

/**
 * パスに対応する gate_key を返す（対象外なら null）。
 *
 * 境界判定は `p === prefix || p.startsWith(prefix + '/')`。
 * 素の `startsWith(prefix)` は隣接名を巻き込む（`/todo` が `/todo-memo` を巻き込む）ため使わない。
 * 複数のプレフィクスに一致した場合は**最長一致**を採る。
 */
export function matchGateKey(path: string): string | null {
  const p = normalizePath(path)
  let matchedKey: string | null = null
  let matchedLength = -1

  for (const [gateKey, prefixes] of Object.entries(GATE_ROUTE_MAP)) {
    for (const prefix of prefixes) {
      if ((p === prefix || p.startsWith(`${prefix}/`)) && prefix.length > matchedLength) {
        matchedKey = gateKey
        matchedLength = prefix.length
      }
    }
  }

  return matchedKey
}

/**
 * ガード対象パスを SSR 対象外（クライアント描画のみ）にする Nitro の routeRules を組み立てる。
 *
 * SSR 実行時はフラグを取得できない（公開フラグ API は localStorage のトークンに依存し、
 * ①の plugin は `.client` 限定）。ここで `ssr: false` にしておくことで、
 * **フラグが未確定な状態で未公開ページの HTML がサーバーから出力されること自体を防ぐ**。
 * middleware 側の `ssr-defer` はこの routeRules と対になっている（片方だけでは穴が空く）。
 */
export function buildGateRouteRules(): Record<string, { ssr: false }> {
  const rules: Record<string, { ssr: false }> = {}
  for (const prefixes of Object.values(GATE_ROUTE_MAP)) {
    for (const prefix of prefixes) {
      rules[prefix] = { ssr: false }
      rules[`${prefix}/**`] = { ssr: false }
    }
  }
  return rules
}

/** ガード判定の結果。 */
export type GateDecision =
  | { action: 'pass' }
  | { action: 'ssr-defer', gateKey: string }
  | { action: 'ensure', gateKey: string }
  | { action: 'deny', gateKey: string }

/**
 * ガード判定の純関数（三値判定: enabled / disabled / unknown）。
 *
 * - 対象外パス → `pass`（フラグストアに一切触れない = happy-path 非干渉）
 * - SSR → `ssr-defer`。SSR では判定材料が無いので「通す」も「弾く」もしない。
 *   未公開コンテンツの出力自体は {@link buildGateRouteRules} の `ssr: false` が防ぐ。
 * - 公開フラグ未取得（unknown） → `ensure`。**素通りさせない**（fail-open 禁止）。
 * - 取得済み → `enabled` の真偽で `pass` / `deny`。
 */
export function decideGate(input: {
  path: string
  isServer: boolean
  publicLoaded: boolean
  enabled: (gateKey: string) => boolean
}): GateDecision {
  const gateKey = matchGateKey(input.path)
  if (gateKey === null) return { action: 'pass' }
  if (input.isServer) return { action: 'ssr-defer', gateKey }
  if (!input.publicLoaded) return { action: 'ensure', gateKey }
  return input.enabled(gateKey) ? { action: 'pass' } : { action: 'deny', gateKey }
}
