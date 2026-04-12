<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { getMyShiftRequests } = useShiftApi()
const { showError } = useNotification()

const shifts = ref<Record<string, unknown>[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyShiftRequests()
    shifts.value = res.data
  } catch {
    showError('シフト情報の取得に失敗しました')
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
    <PageHeader title="マイシフト" />
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="s in shifts"
        :key="s.id"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ s.date }}</h3>
          <span :class="getStatusClass(s.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{
            s.status
          }}</span>
        </div>
        <p class="mt-1 text-xs text-surface-400">{{ s.startTime }} - {{ s.endTime }}</p>
        <p v-if="s.positionName" class="mt-1 text-xs text-surface-500">{{ s.positionName }}</p>
        <p v-if="s.teamName" class="mt-1 text-xs text-surface-400">{{ s.teamName }}</p>
      </SectionCard>
      <DashboardEmptyState v-if="shifts.length === 0" icon="pi-clock" message="シフトがありません" />
    </div>
  </div>
</template>
