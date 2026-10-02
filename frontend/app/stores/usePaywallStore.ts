import { defineStore } from 'pinia'

/**
 * F20.1 ペイウォールモーダル（U-2）のグローバル状態。
 *
 * `useApi()` の共通レスポンスエラーハンドラ（onResponseError）が 402 `ENTITLEMENT_003` を
 * 検知した際にここへ open() し、`app.vue` にグローバルマウントされた `<PaywallModal />` が
 * この状態を読んで表示する（設計書 04 §2「既存の 402 処理があれば共通化」に対応）。
 *
 * BE（#2442・EntitlementGuard/GlobalExceptionHandler）は `error.details` に
 * `{ featureKey, addonAvailable, addonPriceJpy, plansContaining, scopeKind, scopeId }` を
 * 追補済み（`FeatureNotEntitledErrorResponse` / `EntitlementNotEntitledDetails`）。
 * FE はこれを受けてワンクリックの機能別購入導線（アドオン個別追加・対象プラン提示）を出す。
 * details が無い応答（旧 BE・後方互換）でも従来どおり message のみで汎用モーダルを表示する。
 */
export interface PaywallDetails {
  /** 権利が不足している機能キー。 */
  featureKey?: string
  /** アドオン契約で購入可能か。 */
  addonAvailable?: boolean
  /** アドオン月額（円）。未定/アドオン不可の場合は null（明示 null）。 */
  addonPriceJpy?: number | null
  /** この機能を含む購入可能プラン（enabled かつ非 FREE）のキー一覧。0 件の場合は空配列。 */
  plansContaining?: string[]
  /** スコープ種別。 */
  scopeKind?: 'USER' | 'TEAM' | 'ORG'
  /** スコープ ID。 */
  scopeId?: number
}

export interface PaywallState {
  visible: boolean
  /** BE から返されたエラーメッセージ（`ENTITLEMENT_003` の message）。 */
  message: string | null
  /** BE から返された購入導線情報（`ENTITLEMENT_003` の details。無い場合は null）。 */
  details: PaywallDetails | null
}

export const usePaywallStore = defineStore('paywall', {
  state: (): PaywallState => ({
    visible: false,
    message: null,
    details: null,
  }),
  actions: {
    open(payload?: { message?: string | null; details?: PaywallDetails | null }) {
      this.message = payload?.message ?? null
      this.details = payload?.details ?? null
      this.visible = true
    },
    close() {
      this.visible = false
      this.message = null
      this.details = null
    },
  },
})
