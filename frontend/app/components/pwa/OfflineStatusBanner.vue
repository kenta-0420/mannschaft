<script setup lang="ts">
/**
 * F11.1 PWA: オフライン状態を示すバナー。
 *
 * ヘッダー直下に表示し、オフライン時は黄色バナー、
 * オンライン復帰時は緑色バナーを数秒表示して自動消滅する。
 */
const { t } = useI18n()
const online = useOnline()

const showBackOnline = ref(false)
let backOnlineTimer: ReturnType<typeof setTimeout> | null = null

watch(online, (isOnline, wasOnline) => {
  if (isOnline && wasOnline === false) {
    showBackOnline.value = true
    if (backOnlineTimer) clearTimeout(backOnlineTimer)
    backOnlineTimer = setTimeout(() => {
      showBackOnline.value = false
      backOnlineTimer = null
    }, 3000)
  }
})

onUnmounted(() => {
  if (backOnlineTimer) clearTimeout(backOnlineTimer)
})
</script>

<template>
  <!-- オフライン中 -->
  <Transition name="slide-down">
    <div
      v-if="!online"
      class="flex items-center justify-center gap-2 bg-yellow-100 px-4 py-2 text-sm font-medium text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
      role="alert"
    >
      <i class="pi pi-wifi text-yellow-600 dark:text-yellow-400" />
      {{ t('offline.status_banner') }}
    </div>
  </Transition>

  <!-- オンライン復帰 -->
  <Transition name="slide-down">
    <div
      v-if="showBackOnline && online"
      class="flex items-center justify-center gap-2 bg-green-100 px-4 py-2 text-sm font-medium text-green-800 dark:bg-green-900 dark:text-green-200"
      role="status"
    >
      <i class="pi pi-check-circle text-green-600 dark:text-green-400" />
      {{ t('offline.back_online') }}
    </div>
  </Transition>
</template>

<style scoped>
.slide-down-enter-active,
.slide-down-leave-active {
  transition: all 0.3s ease;
}
.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
