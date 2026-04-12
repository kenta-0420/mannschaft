<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { getFacilities } = useFacilityApi()
const { showError } = useNotification()
const facilities = ref<Record<string, unknown>[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getFacilities('team', teamId)
    facilities.value = res.data
  } catch {
    showError('施設情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="共用施設" />
      <Button label="施設を追加" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <SectionCard
        v-for="f in facilities"
        :key="f.id"
        :title="f.name"
      >
        <p v-if="f.location" class="text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ f.location }}
        </p>
        <p v-if="f.capacity" class="mt-1 text-xs text-surface-400">定員 {{ f.capacity }}名</p>
        <Badge v-if="f.requiresApproval" value="承認制" severity="info" class="mt-2" />
      </SectionCard>
      <DashboardEmptyState
        v-if="facilities.length === 0"
        icon="pi pi-building"
        message="施設が登録されていません"
        class="col-span-full"
      />
    </div>
  </div>
</template>
