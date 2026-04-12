<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getUnits } = useResidentApi()
const { showError } = useNotification()
const units = ref<Record<string, unknown>[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getUnits('organization', orgId)
    units.value = res.data
  } catch {
    showError('住民台帳の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="住民台帳" />
      <Button label="住戸を追加" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="u in units"
        :key="u.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div
          class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-sm font-bold text-primary"
        >
          {{ u.unitNumber }}
        </div>
        <div class="flex-1">
          <p class="text-sm font-medium">{{ u.floor }}F - {{ u.unitNumber }}</p>
          <p class="text-xs text-surface-400">{{ u.residentCount }}名居住</p>
        </div>
        <span
          class="rounded px-2 py-0.5 text-xs font-medium"
          :class="
            u.status === 'OCCUPIED'
              ? 'bg-green-100 text-green-700'
              : 'bg-surface-100 text-surface-500'
          "
          >{{ u.status === 'OCCUPIED' ? '入居中' : '空室' }}</span
        >
      </div>
      <DashboardEmptyState v-if="units.length === 0" icon="pi pi-building" message="住戸情報がありません" />
    </div>
  </div>
</template>
