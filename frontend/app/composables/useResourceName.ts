import type { ReservationResourceNameTypeCode } from '~/types/reservation'

/** プリセット（CUSTOM を除く）→ i18n キーの対応表（F03.4.5 §5.3・パラメータ化＋プリセット呼称キー方式）。 */
const PRESET_KEYS: Record<Exclude<ReservationResourceNameTypeCode, 'CUSTOM'>, string> = {
  DEFAULT: 'reservation.resource_name.DEFAULT',
  STAFF: 'reservation.resource_name.STAFF',
  SEAT: 'reservation.resource_name.SEAT',
  COURT: 'reservation.resource_name.COURT',
  BED: 'reservation.resource_name.BED',
  LANE: 'reservation.resource_name.LANE',
}

/**
 * 予約対象の呼称解決 composable（F03.4.5 §5.2 新設）。
 *
 * `GET /reservation-settings` の `resourceNameType`/`resourceNameCustom` から、画面に差し込むべき
 * 呼称文字列（`resourceName`）を解決する。
 * - `CUSTOM`: `resourceNameCustom` を全ロケール共通でそのまま生表示する（翻訳しない・v-html 禁止。
 *   BE でサニタイズ済みだが FE も常にテキストバインドで描画すること）
 * - `DEFAULT` / 未設定 / 取得失敗: 既存挙動と完全一致のフォールバック文言「予約対象」相当
 *   （`reservation.resource_name.DEFAULT`）を返す（後方互換・§5.1）
 *
 * 実装形は `useRoleAccess`（`backend/.claudecode.md` 系の写経元コンポーネント群と同一パターン）を
 * 踏襲し、呼び出し元コンポーネントごとに独立フェッチする（多重GETは許容・`feedback_copy_working_pattern_first`）。
 * 呼び出し元は `onMounted` で `load()` を呼ぶこと（自動フェッチしない）。
 */
export function useResourceName(teamId: Ref<string> | string) {
  const { t } = useI18n()
  const reservationApi = useReservationApi()

  const resourceNameType = ref<ReservationResourceNameTypeCode>('DEFAULT')
  const resourceNameCustom = ref<string | null>(null)
  const loading = ref(false)

  const resolvedTeamId = computed(() => (isRef(teamId) ? teamId.value : teamId))

  async function load(): Promise<void> {
    if (!resolvedTeamId.value) return
    loading.value = true
    try {
      const res = await reservationApi.getReservationSettings(resolvedTeamId.value)
      resourceNameType.value = res.data.resourceNameType ?? 'DEFAULT'
      resourceNameCustom.value = res.data.resourceNameCustom ?? null
    }
    catch {
      // 取得失敗は安全側（DEFAULT表示）にフォールバックする。呼称表示は補助的な文言であり、
      // ここで例外を伝播させて画面全体をブロックするのは過剰防御（症状を隠す対処療法ではなく、
      // 「呼称のフォールバック」は §5.1 で明文化された正規のデフォルト動作そのもの）。
      resourceNameType.value = 'DEFAULT'
      resourceNameCustom.value = null
    }
    finally {
      loading.value = false
    }
  }

  /** 画面に差し込むべき呼称文字列。CUSTOM は生表示・それ以外はプリセットi18nキー解決。 */
  const resourceName = computed<string>(() => {
    if (resourceNameType.value === 'CUSTOM') {
      const custom = resourceNameCustom.value?.trim()
      return custom ? custom : t('reservation.resource_name.DEFAULT')
    }
    const key = PRESET_KEYS[resourceNameType.value] ?? PRESET_KEYS.DEFAULT
    return t(key)
  })

  if (isRef(teamId)) {
    watch(teamId, (newId) => {
      if (newId) void load()
    })
  }

  return {
    resourceNameType,
    resourceNameCustom,
    resourceName,
    loading,
    load,
    refresh: load,
  }
}
