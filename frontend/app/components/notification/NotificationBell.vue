<script setup lang="ts">
const { getUnreadCount } = useNotificationApi()
const router = useRouter()

const unreadTotal = ref(0)

async function fetchCount() {
  try {
    const res = await getUnreadCount()
    unreadTotal.value = res.data.total
  } catch {
    // silent fail
  }
}

function goToNotifications() {
  router.push('/notifications')
}

// 30秒ごとにポーリング
let timer: ReturnType<typeof setInterval>
onMounted(() => {
  fetchCount()
  timer = setInterval(fetchCount, 30000)
})
onUnmounted(() => clearInterval(timer))

defineExpose({ refresh: fetchCount })
</script>

<template>
  <div class="relative">
    <Button
      v-tooltip.bottom="'通知'"
      icon="pi pi-bell"
      text
      rounded
      severity="secondary"
      @click="goToNotifications"
    />
    <Badge
      v-if="unreadTotal > 0"
      :value="unreadTotal > 99 ? '99+' : unreadTotal"
      severity="danger"
      class="absolute -right-1 -top-1"
    />
  </div>
</template>
