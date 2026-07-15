import { defineStore } from 'pinia'

/**
 * F20.1 ペイウォールモーダル（U-2）のグローバル状態。
 *
 * `useApi()` の共通レスポンスエラーハンドラ（onResponseError）が 402 `ENTITLEMENT_003` を
 * 検知した際にここへ open() し、`app.vue` にグローバルマウントされた `<PaywallModal />` が
 * この状態を読んで表示する（設計書 04 §2「既存の 402 処理があれば共通化」に対応）。
 *
 * 【実装上の注記（BE 実装との既知の乖離）】
 * 設計書 02 §1.2 は 402 のエラー詳細に `details: { featureKey, addonAvailable, addonPriceJpy,
 * plansContaining }` を含める想定だが、実装済みの BE（EntitlementGuard/GlobalExceptionHandler）は
 * ErrorDetail に code/message/fieldErrors のみを持ち、当該 details を一切返さない。
 * そのため FE 側はこの汎用情報（コード・メッセージのみ）から汎用ペイウォールモーダルを表示し、
 * 機能別の購入導線（アドオン個別追加・対象プラン名の提示）は行わず、プラン一覧ページへの
 * 導線のみを提示する「段階的縮退（degraded）」実装とする。
 */
export interface PaywallState {
  visible: boolean
  /** BE から返されたエラーメッセージ（`ENTITLEMENT_003` の message）。 */
  message: string | null
}

export const usePaywallStore = defineStore('paywall', {
  state: (): PaywallState => ({
    visible: false,
    message: null,
  }),
  actions: {
    open(message?: string | null) {
      this.message = message ?? null
      this.visible = true
    },
    close() {
      this.visible = false
    },
  },
})
