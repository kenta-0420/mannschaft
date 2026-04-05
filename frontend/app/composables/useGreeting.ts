export function useGreeting() {
  const { t } = useI18n()

  const hour = new Date().getHours()
  const key =
    hour >= 5 && hour < 9
      ? 'greeting.earlyMorning'
      : hour >= 9 && hour < 12
        ? 'greeting.morning'
        : hour >= 12 && hour < 17
          ? 'greeting.afternoon'
          : hour >= 17 && hour < 21
            ? 'greeting.evening'
            : 'greeting.night'

  // computed にすることで i18n の lazy load 完了後に自動で再評価される
  return computed(() => t(key))
}
