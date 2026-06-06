import type {
  MembershipSubscriptionListItem,
  MembershipSubscriptionResponse,
  SetupIntentResponse,
  PaymentMethodResponse,
  PaymentMethodConfirmRequest,
  SubscribeRequest,
} from '~/types/membershipSubscription'

/**
 * F08.9 P5 継続課金 API の型付きラッパー（設計書 02 §4 / 04 §2）。
 *
 * すべて払い手本人（ログインユーザー）視点。エンドポイントは BE PR #1366 main 済み。
 * - 一覧:   GET    /api/v1/me/membership-subscriptions
 * - 解約:   DELETE /api/v1/membership-subscriptions/{id}（期末解約予約）
 * - スキップ: POST   /api/v1/membership-subscriptions/{id}/skip（今月スキップ）
 * - 再開:   POST   /api/v1/membership-subscriptions/{id}/resume
 * - 加入:   POST   /api/v1/payment-items/{itemId}/subscribe（次 PR で Stripe.js confirm 後に利用）
 * - SetupIntent: POST /api/v1/me/payment-methods/setup-intent
 * - PM confirm:  POST /api/v1/me/payment-methods/confirm
 *
 * BE は全レスポンスを ApiResponse（{ data: ... }）でラップする。
 */
export function useMembershipSubscriptionApi() {
  const api = useApi()

  /** 自分（払い手）の継続課金一覧を取得する。 */
  async function listMySubscriptions() {
    return api<{ data: MembershipSubscriptionListItem[] }>('/api/v1/me/membership-subscriptions')
  }

  /** 継続課金を期末解約予約する（cancel_at_period_end=true）。 */
  async function cancelSubscription(id: string) {
    return api<{ data: MembershipSubscriptionResponse }>(
      `/api/v1/membership-subscriptions/${id}`,
      { method: 'DELETE' },
    )
  }

  /** 継続課金を今月スキップする（pause_collection）。 */
  async function skipSubscription(id: string) {
    return api<{ data: MembershipSubscriptionResponse }>(
      `/api/v1/membership-subscriptions/${id}/skip`,
      { method: 'POST' },
    )
  }

  /** 継続課金のスキップを解除して再開する。 */
  async function resumeSubscription(id: string) {
    return api<{ data: MembershipSubscriptionResponse }>(
      `/api/v1/membership-subscriptions/${id}/resume`,
      { method: 'POST' },
    )
  }

  /** 継続課金に加入する（次 PR で SetupIntent confirm 後に呼ぶ）。 */
  async function subscribe(itemId: number, body: SubscribeRequest) {
    return api<{ data: MembershipSubscriptionResponse }>(
      `/api/v1/payment-items/${itemId}/subscribe`,
      { method: 'POST', body },
    )
  }

  /** off_session 用 SetupIntent を作成する（次 PR の Stripe.js 統合で使用）。 */
  async function createSetupIntent() {
    return api<{ data: SetupIntentResponse }>('/api/v1/me/payment-methods/setup-intent', {
      method: 'POST',
    })
  }

  /** confirm 済み PaymentMethod を Customer へ attach＋既定設定する（次 PR で使用）。 */
  async function confirmPaymentMethod(body: PaymentMethodConfirmRequest) {
    return api<{ data: PaymentMethodResponse }>('/api/v1/me/payment-methods/confirm', {
      method: 'POST',
      body,
    })
  }

  return {
    listMySubscriptions,
    cancelSubscription,
    skipSubscription,
    resumeSubscription,
    subscribe,
    createSetupIntent,
    confirmPaymentMethod,
  }
}
