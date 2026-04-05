<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { getMyReservations } = useReservationApi()
const { showError } = useNotification()

const reservations = ref<Record<string, unknown>[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyReservations()
    reservations.value = res.data
  } catch {
    showError('予約情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(s: string) {
  switch (s) {
    case 'CONFIRMED':
      return 'bg-green-100 text-green-700'
    case 'PENDING':
      return 'bg-yellow-100 text-yellow-700'
    case 'CANCELLED':
      return 'bg-red-100 text-red-700'
    case 'COMPLETED':
      return 'bg-blue-100 text-blue-700'
    default:
      return 'bg-surface-100'
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <h1 class="mb-6 text-2xl font-bold">マイ予約</h1>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="r in reservations"
        :key="r.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ r.slotTitle ?? r.serviceName }}</h3>
          <span :class="getStatusClass(r.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{
            r.status
          }}</span>
        </div>
        <p class="mt-1 text-xs text-surface-400">{{ r.startAt }} - {{ r.endAt }}</p>
        <p v-if="r.teamName" class="mt-1 text-xs text-surface-500">{{ r.teamName }}</p>
      </div>
      <div v-if="reservations.length === 0" class="py-12 text-center">
        <i class="pi pi-calendar mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">予約がありません</p>
      </div>
    </div>
  </div>
</template>
