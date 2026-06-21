<script setup lang="ts">
import type { ReservationResponse } from '~/types/reservation'
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { listMyReservations } = useReservationApi()
const { showError } = useNotification()

const reservations = ref<ReservationResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await listMyReservations()
    reservations.value = res.data
  } catch {
    showError(t('reservation.message.my_load_failed'))
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

function statusLabel(s?: string): string {
  if (!s) return ''
  const known = ['PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW']
  return known.includes(s) ? t(`reservation.status.${s}`) : s
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('reservation.page.my_title')" back-to="/my" />
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="r in reservations"
        :key="r.id"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ r.slot?.lineName }}</h3>
          <span :class="getStatusClass(r.status?.status ?? '')" class="rounded px-2 py-0.5 text-xs font-medium">{{
            statusLabel(r.status?.status)
          }}</span>
        </div>
        <p class="mt-1 text-xs text-surface-400">{{ r.slot?.slotDate }} {{ r.slot?.startTime }} - {{ r.slot?.endTime }}</p>
      </SectionCard>
      <DashboardEmptyState v-if="reservations.length === 0" icon="pi-calendar" :message="t('reservation.empty.no_my_reservations')" />
    </div>
  </div>
</template>
