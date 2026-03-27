export function useRelativeTime(dateStr: Ref<string> | string) {
  const resolved = computed(() => isRef(dateStr) ? dateStr.value : dateStr)

  const relativeTime = ref('')

  function update() {
    const now = Date.now()
    const target = new Date(resolved.value).getTime()
    const diffMs = now - target
    const diffSec = Math.floor(diffMs / 1000)
    const diffMin = Math.floor(diffSec / 60)
    const diffHour = Math.floor(diffMin / 60)
    const diffDay = Math.floor(diffHour / 24)

    if (diffSec < 60) {
      relativeTime.value = 'たった今'
    }
    else if (diffMin < 60) {
      relativeTime.value = `${diffMin}分前`
    }
    else if (diffHour < 24) {
      relativeTime.value = `${diffHour}時間前`
    }
    else if (diffDay < 7) {
      relativeTime.value = `${diffDay}日前`
    }
    else {
      relativeTime.value = new Date(resolved.value).toLocaleDateString('ja-JP')
    }
  }

  let intervalId: ReturnType<typeof setInterval> | null = null

  onMounted(() => {
    update()
    intervalId = setInterval(update, 60000) // 1分ごとに更新
  })

  onUnmounted(() => {
    if (intervalId) clearInterval(intervalId)
  })

  if (isRef(dateStr)) {
    watch(dateStr, update)
  }

  return relativeTime
}
