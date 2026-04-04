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
      <h1 class="text-2xl font-bold">共用施設</h1>
      <Button label="施設を追加" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="f in facilities"
        :key="f.id"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
      >
        <h3 class="text-sm font-semibold">{{ f.name }}</h3>
        <p v-if="f.location" class="text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ f.location }}
        </p>
        <p v-if="f.capacity" class="mt-1 text-xs text-surface-400">定員 {{ f.capacity }}名</p>
        <Badge v-if="f.requiresApproval" value="承認制" severity="info" class="mt-2" />
      </div>
      <div v-if="facilities.length === 0" class="col-span-full py-12 text-center">
        <i class="pi pi-building mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">施設が登録されていません</p>
      </div>
    </div>
  </div>
</template>
