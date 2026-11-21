/**
 * 価格改定フォームの純関数（税率％↔basis points 変換・商品キー/税コードの選択肢構築・送信前検証）。
 *
 * BE の正本:
 * - 税率 basis points は 0〜10000（`BillingTaxCodeCreateRequest.rateBasisPoints`）。1bp = 0.01%。
 * - 商品キー: PLAN は plans に存在、ADDON は feature_catalog に存在かつ addonAvailable
 *   （`PriceRevisionCreateService.validateProductExists`）。
 * - 税コード: `[validFrom, validUntil)` の半開区間で enabled（`BillingTaxCodeService.resolveEffective`）。
 *
 * ここの選択肢は「画面で誤入力を防ぐ」ための絞り込みで、最終判定は常に BE が行う。
 */

const MAX_BASIS_POINTS = 10000
/** 浮動小数の丸め誤差（例: 1.15 * 100 = 114.99999999999999）を許容する閾値。 */
const EPSILON = 1e-6

/**
 * 税率の％入力を basis points（整数）へ変換する。
 *
 * 範囲外（0〜100%以外）・0.01% 未満の端数・非有限・未入力は `null`。端数は黙って丸めず拒否する
 * （税率を意図せず変えて課税額を狂わせないため）。
 */
export function percentToBasisPoints(percent: number | null | undefined): number | null {
  if (percent === null || percent === undefined || !Number.isFinite(percent)) return null
  const scaled = percent * 100
  const rounded = Math.round(scaled)
  if (Math.abs(scaled - rounded) > EPSILON) return null
  if (rounded < 0 || rounded > MAX_BASIS_POINTS) return null
  // -0 を 0 に正規化する。
  return rounded === 0 ? 0 : rounded
}

/** basis points を％表示用の数値へ変換する（1000 → 10、750 → 7.5）。 */
export function basisPointsToPercent(basisPoints: number): number {
  return basisPoints / 100
}

interface PlanLike {
  planKey?: string
  enabled?: boolean
}

interface FeatureLike {
  featureKey?: string
  enabled?: boolean
  addonAvailable?: boolean
}

/** 商品種別に応じた商品キーの選択肢（有効なものだけ。ADDON は単体契約可のものだけ）。 */
export function buildProductKeyOptions(
  productKind: 'PLAN' | 'ADDON',
  plans: readonly PlanLike[],
  features: readonly FeatureLike[],
): string[] {
  if (productKind === 'PLAN') {
    return plans.filter((p) => p.enabled === true && !!p.planKey).map((p) => p.planKey as string)
  }
  return features
    .filter((f) => f.enabled === true && f.addonAvailable === true && !!f.featureKey)
    .map((f) => f.featureKey as string)
}

interface TaxCodeLike {
  code: string
  rateBasisPoints: number
  validFrom: string
  validUntil?: string | null
  enabled: boolean
}

/**
 * 適用開始日時（epoch ミリ秒）に有効な税コードの選択肢。
 * 同じ code の版が複数あっても、その時点で有効なもの（validFrom が最新）1件に絞る。
 * 適用開始日時が未確定（null）の間は選べない（どの版が有効か決まらないため）。
 */
export function buildTaxCodeOptions<T extends TaxCodeLike>(taxCodes: readonly T[], atMillis: number | null): T[] {
  if (atMillis === null || !Number.isFinite(atMillis)) return []
  const latestByCode = new Map<string, T>()
  for (const taxCode of taxCodes) {
    if (!taxCode.enabled) continue
    const from = Date.parse(taxCode.validFrom)
    if (Number.isNaN(from) || from > atMillis) continue
    if (taxCode.validUntil) {
      const until = Date.parse(taxCode.validUntil)
      if (Number.isNaN(until) || atMillis >= until) continue
    }
    const current = latestByCode.get(taxCode.code)
    if (!current || Date.parse(current.validFrom) < from) latestByCode.set(taxCode.code, taxCode)
  }
  return [...latestByCode.values()]
}

/** 値が選択肢に完全一致で含まれるか（前後空白・大文字小文字の差は不一致）。 */
export function isSelectableOption(value: string | null | undefined, options: readonly string[]): boolean {
  return typeof value === 'string' && options.includes(value)
}
