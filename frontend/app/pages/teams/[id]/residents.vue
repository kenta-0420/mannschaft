<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { getUnits } = useResidentApi()
const { showError } = useNotification()
const units = ref<Record<string, unknown>[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getUnits('team', teamId)
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
      <h1 class="text-2xl font-bold">住民台帳</h1>
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
          class="flex h-10 w-10 items-center justify-center rounded-lg bg-surface-100 text-sm font-bold"
        >
          {{ u.unitNumber }}
        </div>
        <div class="flex-1">
          <p class="text-sm font-medium">{{ u.unitNumber }}号室</p>
          <p class="text-xs text-surface-400">
            {{ u.isVacant ? '空室' : `${u.residents.length}名居住中` }}
          </p>
        </div>
        <Badge
          :value="u.isVacant ? '空室' : '入居中'"
          :severity="u.isVacant ? 'warning' : 'success'"
        />
      </div>
      <div v-if="units.length === 0" class="py-12 text-center">
        <i class="pi pi-building mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">住戸が登録されていません</p>
      </div>
    </div>
  </div>
</template>
