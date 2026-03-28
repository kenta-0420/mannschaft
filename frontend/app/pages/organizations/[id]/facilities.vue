<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getFacilities } = useFacilityApi()
const { showError } = useNotification()
const facilities = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { const res = await getFacilities(orgId, 'ORGANIZATION'); facilities.value = res.data } catch { showError('施設情報の取得に失敗しました') } finally { loading.value = false } }
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between"><h1 class="text-2xl font-bold">共用施設予約</h1><Button label="施設を追加" icon="pi pi-plus" /></div>
    <div v-if="loading" class="flex justify-center py-8"><ProgressSpinner style="width: 40px; height: 40px" /></div>
    <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <div v-for="f in facilities" :key="f.id" class="rounded-xl border border-surface-200 bg-surface-0 p-4">
        <h3 class="text-sm font-semibold">{{ f.name }}</h3>
        <p v-if="f.description" class="mt-1 text-xs text-surface-500">{{ f.description }}</p>
        <div class="mt-2 flex items-center gap-2 text-xs text-surface-400">
          <span><i class="pi pi-users" /> 定員{{ f.capacity }}名</span>
          <span v-if="f.hourlyRate"><i class="pi pi-yen" /> {{ f.hourlyRate }}/時間</span>
        </div>
      </div>
      <div v-if="facilities.length === 0" class="col-span-full py-12 text-center"><i class="pi pi-building mb-3 text-4xl text-surface-300" /><p class="text-surface-400">施設がありません</p></div>
    </div>
  </div>
</template>
