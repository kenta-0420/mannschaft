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
    <h1 class="mb-6 text-2xl font-bold">マイシフト</h1>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="s in shifts"
        :key="s.id"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
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
      </div>
      <div v-if="shifts.length === 0" class="py-12 text-center">
        <i class="pi pi-clock mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">シフトがありません</p>
      </div>
    </div>
  </div>
</template>
