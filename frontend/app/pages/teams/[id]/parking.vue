<script setup lang="ts">
import type { ParkingSpaceResponse } from '~/types/parking'
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { getSpaces } = useParkingApi()
const { showError } = useNotification()
const spaces = ref<ParkingSpaceResponse[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getSpaces('team', teamId)
    spaces.value = res.data as ParkingSpaceResponse[]
  } catch {
    showError('駐車場情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
function getStatusClass(s: string) {
  switch (s) {
    case 'AVAILABLE':
      return 'bg-green-100 text-green-700'
    case 'ASSIGNED':
      return 'bg-blue-100 text-blue-700'
    default:
      return 'bg-surface-100 text-surface-500'
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="駐車場管理" />
      <Button label="区画を追加" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">
      <SectionCard
        v-for="s in spaces"
        :key="s.id"
        class="text-center"
      >
        <p class="text-lg font-bold">{{ s.spaceNumber }}</p>
        <span
          :class="getStatusClass(s.status)"
          class="mt-1 inline-block rounded px-2 py-0.5 text-xs font-medium"
          >{{ s.status }}</span
        >
        <p v-if="s.assignedTo" class="mt-2 text-xs text-surface-400">
          {{ s.assignedTo.displayName }}<br >{{ s.assignedTo.vehiclePlate }}
        </p>
      </SectionCard>
    </div>
  </div>
</template>
