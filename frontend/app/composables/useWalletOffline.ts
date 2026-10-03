/**
 * F18 個人ポイントカードウォレット — オフライン対応 composable。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §6.6 / §7.4
 *
 * <p>提示モードのページで使う想定。{@code startPresentation} API を
 * 「オンライン優先 / 失敗時は IndexedDB から復元」の戦略で呼び分ける。</p>
 *
 * <p>クリアは設定画面の「オフラインキャッシュを削除」ボタンと、
 * ログアウト処理（{@code stores/useAuthStore.ts}）から呼ばれる。</p>
 */

import type { PointCardGroupDetail } from '~/types/pointCard'
import { createOfflineStore } from '~/utils/walletOfflineStore'

/**
 * オフライン対応ウォレット composable。
 */
export function useWalletOffline() {
  const store = createOfflineStore()
  const api = useWalletApi()
  const authStore = useAuthStore()

  /**
   * 現在のユーザー ID を取得する。未ログインなら例外を投げる。
   * オフラインキャッシュはユーザーごとに分離する必要があるため必須。
   */
  function requireUserId(): number {
    const id = authStore.currentUser?.id
    if (typeof id !== 'number') {
      throw new Error('useWalletOffline: ログイン状態でのみ使用可能です')
    }
    return id
  }

  /**
   * 提示モード用にグループ詳細を取得する。
   *
   * <ol>
   *   <li>オンライン: {@code startPresentation} API を呼び監査ログを記録、結果を IndexedDB にキャッシュ。</li>
   *   <li>オフライン: ネットワーク失敗時は IndexedDB から復元。{@code cachedFromOffline=true} で返す。</li>
   *   <li>キャッシュも無ければオリジナルのエラーを再 throw する（呼び出し側でエラーハンドリング）。</li>
   * </ol>
   *
   * @returns グループ詳細と「オフラインキャッシュから返したか」フラグ。
   */
  async function getGroupForPresentation(groupId: string): Promise<{
    group: PointCardGroupDetail
    cachedFromOffline: boolean
  }> {
    const userId = requireUserId()
    try {
      const group = await api.startPresentation(groupId)
      // キャッシュ更新は fire-and-forget。IndexedDB の open/暗号化がハングしても
      // 提示モード本線（loading 完了）を塞がないよう await しない。
      // 失敗時は握りつぶさず .catch() でログを残す（根治治療の原則）。
      store.saveGroup(userId, group).catch((cacheErr) => {
        if (import.meta.dev) {
          console.warn('[useWalletOffline] saveGroup failed:', cacheErr)
        }
      })
      return { group, cachedFromOffline: false }
    } catch (onlineErr) {
      // ネットワーク失敗 or サーバーエラー時は IndexedDB から復元を試みる
      const cached = await store.loadGroup(userId, groupId)
      if (cached) {
        return { group: cached, cachedFromOffline: true }
      }
      throw onlineErr
    }
  }

  /**
   * 当該ユーザーのオフラインキャッシュをすべて削除する。
   * 設定画面の「オフラインキャッシュを削除」ボタンから呼ぶ。
   */
  async function clearCache(): Promise<void> {
    const userId = requireUserId()
    await store.clearAll(userId)
  }

  /**
   * 暗号鍵を強制再生成する（既存キャッシュは復号不可能になるため全削除される）。
   * ログイン直後など、新しい鍵で運用したいタイミングで呼ぶ。
   */
  async function refreshKey(): Promise<void> {
    const userId = requireUserId()
    await store.refreshKey(userId)
  }

  return {
    getGroupForPresentation,
    clearCache,
    refreshKey,
  }
}
