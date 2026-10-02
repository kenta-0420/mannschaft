import type { TimelineMute, TimelineMutedType } from '~/types/timeline'

/**
 * タイムラインのミュート（非表示設定）を扱う composable。
 *
 * <p>ミュートは個人フィード（`GET /api/v1/timeline/my`）にのみ効く**表示設定**であり権限ではない。
 * 検索結果には効かず、ミュート中でも投稿詳細は開ける（仕様）。</p>
 *
 * <p>上限は 200 件で、超過すると BE が `TIMELINE_017`（`MAX_MUTES_EXCEEDED`）を返す。
 * このエラーは握りつぶさず `useErrorHandler` に渡してユーザーへ表示する
 * （文言は i18n `error.TIMELINE_017`）。</p>
 */
export function useTimelineMutes() {
  const { getMutes, addMute, removeMute } = useTimelineApi()
  const { handleApiError } = useErrorHandler()

  const mutes = ref<TimelineMute[]>([])
  const loading = ref(false)
  /** 一度でも一覧取得に成功したか（未取得と「0件」を区別する）。 */
  const loaded = ref(false)

  const muteCount = computed(() => mutes.value.length)

  /** 指定のスコープがミュート済みか。 */
  function isMuted(mutedType: TimelineMutedType, mutedId: number): boolean {
    return mutes.value.some((m) => m.mutedType === mutedType && m.mutedId === mutedId)
  }

  /**
   * ミュート一覧を取得する。
   * 失敗は握りつぶさずユーザーへ通知し、`false` を返す（呼び出し元が状態を偽らないため）。
   */
  async function loadMutes(): Promise<boolean> {
    loading.value = true
    try {
      const res = await getMutes()
      mutes.value = res.data ?? []
      loaded.value = true
      return true
    }
    catch (e: unknown) {
      handleApiError(e, 'timeline.mutes.load')
      return false
    }
    finally {
      loading.value = false
    }
  }

  /**
   * ミュートを追加する。
   * 成功時は一覧へ反映し `true`、失敗時（200件上限 `TIMELINE_017` を含む）は
   * エラーを表示したうえで `false` を返す。
   */
  async function mute(mutedType: TimelineMutedType, mutedId: number): Promise<boolean> {
    try {
      const res = await addMute({ mutedType, mutedId })
      if (res?.data) {
        mutes.value = [...mutes.value.filter((m) => !(m.mutedType === mutedType && m.mutedId === mutedId)), res.data]
      }
      return true
    }
    catch (e: unknown) {
      // TIMELINE_017（上限200件超過）も含め、BE の ErrorCode に応じた文言で必ずユーザーに見せる。
      handleApiError(e, 'timeline.mutes.add')
      return false
    }
  }

  /** ミュートを解除する。失敗時はエラーを表示して `false` を返す。 */
  async function unmute(mutedType: TimelineMutedType, mutedId: number): Promise<boolean> {
    try {
      await removeMute({ mutedType, mutedId })
      mutes.value = mutes.value.filter((m) => !(m.mutedType === mutedType && m.mutedId === mutedId))
      return true
    }
    catch (e: unknown) {
      handleApiError(e, 'timeline.mutes.remove')
      return false
    }
  }

  return { mutes, muteCount, loading, loaded, isMuted, loadMutes, mute, unmute }
}
