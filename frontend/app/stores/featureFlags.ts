import { defineStore } from 'pinia'

export const useFeatureFlagStore = defineStore('featureFlags', () => {
  const flags = ref<Record<string, boolean>>({})
  const loaded = ref(false)
  /**
   * 公開フラグ読取API（loadPublicFlags）で取得済みかどうか（Gate 基盤工事②で追加）。
   *
   * loaded は admin API 経由の loadFlags でも true になるため、システム管理者が
   * 管理コンソールを開いた後は「公開フラグ未取得なのに取得済みに見える」状態が起こる。
   * route ガード middleware は「未取得なら素通りさせず遅延取得する」判定に使うため、
   * 公開フラグの取得完了だけを表す独立したフラグが必要になる。
   */
  const publicLoaded = ref(false)

  /**
   * 進行中の loadPublicFlags の Promise（in-flight 重複排除用）。
   *
   * route ガード middleware は未取得のガード対象パスで遅延取得するため、リンク連打や
   * prefetch で同じ API が並走しうる。同一の Promise を返して1本に束ねる。
   * ref ではなくモジュール内のローカル変数で持つ（描画に関与しない実装詳細のため）。
   */
  let inFlight: Promise<void> | null = null

  /**
   * システム管理者向け: admin API から全フラグ（description等含む）を取得する。
   * 管理コンソール用途。isSystemAdmin でなければ何もしない。
   */
  async function loadFlags() {
    const authStore = useAuthStore()
    if (!authStore.isSystemAdmin) return

    try {
      const { getFeatureFlags } = useSystemAdminApi()
      const res = await getFeatureFlags()
      flags.value = Object.fromEntries(res.data.map((f) => [f.flagKey, f.isEnabled]))
      loaded.value = true
    } catch {
      // サイレント失敗 — 一般ユーザーやネットワークエラー時はデフォルト(false)を維持
    }
  }

  /**
   * 一般ユーザー向け: 公開フラグ読取API（GET /api/v1/feature-flags）から取得する
   * （Gate基盤工事①）。認証済みユーザーであれば誰でも呼べる。
   *
   * エラーを握りつぶさず呼び出し元へ伝播する（根治治療の原則）。呼び出し元の
   * plugins/feature-flags.client.ts で console.error に出力しつつアプリ起動は妨げない。
   */
  async function loadPublicFlags(): Promise<void> {
    // 進行中の取得があれば相乗りする（同時多重呼び出しを1本に束ねる）。
    if (inFlight) return inFlight

    inFlight = (async () => {
      const { getPublicFlags } = useFeatureFlagsApi()
      const publicFlags = await getPublicFlags()
      flags.value = {
        ...flags.value,
        ...Object.fromEntries(publicFlags.map((f) => [f.flagKey, f.enabled])),
      }
      loaded.value = true
      publicLoaded.value = true
    })()

    try {
      await inFlight
    } finally {
      // 成否によらず解放する。失敗を握り潰して次回の再試行を封じない。
      inFlight = null
    }
  }

  function isEnabled(flagKey: string): boolean {
    return flags.value[flagKey] ?? false
  }

  return { flags, loaded, publicLoaded, loadFlags, loadPublicFlags, isEnabled }
})
