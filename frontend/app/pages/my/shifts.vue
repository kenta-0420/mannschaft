<script setup lang="ts">
import type { ShiftRequestResponse } from '~/types/shift'

definePageMeta({ middleware: 'auth' })

const { getMyShiftRequests } = useShiftApi()
const { showError } = useNotification()
const { t } = useI18n()

const shifts = ref<ShiftRequestResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyShiftRequests()
    shifts.value = res
  } catch {
    showError(t('shift.myShifts.fetchError'))
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
    case 'REJECTED':
      return 'bg-red-100 text-red-700'
    default:
      return 'bg-surface-100'
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('shift.myShifts.title')" />
      <NuxtLink to="/teams">
        <Button
          :label="$t('shift.changeRequest.submit')"
          icon="pi pi-plus"
          severity="secondary"
          outlined
          size="small"
        />
      </NuxtLink>
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="s in shifts"
        :key="s.id"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ s.slotDate }}</h3>
          <span :class="getStatusClass(s.preference)" class="rounded px-2 py-0.5 text-xs font-medium">{{
            s.preference
          }}</span>
        </div>
        <p v-if="s.note" class="mt-1 text-xs text-surface-400">{{ s.note }}</p>
        <!-- スケジュール ID がある場合は変更依頼リンクを表示 -->
        <NuxtLink
          v-if="s.scheduleId"
          :to="`/shifts/schedules/${s.scheduleId}`"
          class="mt-2 inline-flex items-center gap-1 text-xs text-primary-600 hover:underline"
        >
          <i class="pi pi-arrow-right text-xs" />
          {{ $t('shift.changeRequest.submit') }}
        </NuxtLink>
      </SectionCard>
      <DashboardEmptyState v-if="shifts.length === 0" icon="pi-clock" :message="$t('shift.myShifts.empty')" />
    </div>
  </div>
</template>
