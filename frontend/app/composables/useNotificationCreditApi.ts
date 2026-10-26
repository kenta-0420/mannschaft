import type {
  NotificationCreditBalance,
  NotificationCreditPackage,
  NotificationCreditPurchase,
  NotificationCreditCheckoutResponse,
} from '~/types/notification-credit'

/**
 * F09.13 通知プリペイドクレジット API コンポーザブル。
 */
export function useNotificationCreditApi() {
  const api = useApi()

  /**
   * 組織の通知クレジット残高を取得する（ADMIN以上）。
   */
  async function getBalance(orgId: string) {
    return api<{ data: NotificationCreditBalance }>(
      `/api/v1/organizations/${orgId}/notification-credits/balance`,
    )
  }

  /**
   * 組織の購入履歴一覧を取得する（ADMIN以上）。
   */
  async function listPurchases(orgId: string) {
    return api<{ data: NotificationCreditPurchase[] }>(
      `/api/v1/organizations/${orgId}/notification-credits/purchases`,
    )
  }

  /**
   * 販売中パッケージ一覧を取得する（認証済みユーザー）。
   */
  async function listPackages() {
    return api<{ data: NotificationCreditPackage[] }>('/api/v1/notification-credits/packages')
  }

  /**
   * 通知クレジット購入用 Checkout Session を作成する（ADMINのみ）。
   * レスポンスの checkoutUrl へリダイレクトして Stripe の決済ページを表示する。
   */
  async function createCheckout(orgId: string, packageId: number) {
    return api<{ data: NotificationCreditCheckoutResponse }>(
      `/api/v1/organizations/${orgId}/notification-credits/checkout`,
      {
        method: 'POST',
        body: { packageId },
      },
    )
  }

  return {
    getBalance,
    listPurchases,
    listPackages,
    createCheckout,
  }
}
