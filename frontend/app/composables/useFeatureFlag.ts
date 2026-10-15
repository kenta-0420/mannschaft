/**
 * フィーチャーフラグ composable
 *
 * システム管理者: admin API から取得したフラグ値を返す
 * 一般ユーザー:  フラグが取得できないため、常に false を返す
 *
 * @example
 * const { enabled: betaEnabled } = useFeatureFlag('BETA_FEATURE')
 * // <div v-if="betaEnabled">...</div>
 */
export function useFeatureFlag(flagKey: string) {
  const store = useFeatureFlagStore()

  const enabled = computed(() => store.isEnabled(flagKey))

  return { enabled }
}
