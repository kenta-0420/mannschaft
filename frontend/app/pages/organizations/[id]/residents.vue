<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getUnits } = useResidentApi()
const { showError } = useNotification()
const units = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { const res = await getUnits(orgId, 'ORGANIZATION'); units.value = res.data } catch { showError('住民台帳の取得に失敗しました') } finally { loading.value = false } }
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between"><h1 class="text-2xl font-bold">住民台帳</h1><Button label="住戸を追加" icon="pi pi-plus" /></div>
    <div v-if="loading" class="flex justify-center py-8"><ProgressSpinner style="width: 40px; height: 40px" /></div>
    <div v-else class="flex flex-col gap-2">
      <div v-for="u in units" :key="u.id" class="flex items-center gap-4 rounded-xl border border-surface-200 bg-surface-0 p-4">
        <div class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-sm font-bold text-primary">{{ u.unitNumber }}</div>
        <div class="flex-1"><p class="text-sm font-medium">{{ u.floor }}F - {{ u.unitNumber }}</p><p class="text-xs text-surface-400">{{ u.residentCount }}名居住</p></div>
        <span class="rounded px-2 py-0.5 text-xs font-medium" :class="u.status === 'OCCUPIED' ? 'bg-green-100 text-green-700' : 'bg-surface-100 text-surface-500'">{{ u.status === 'OCCUPIED' ? '入居中' : '空室' }}</span>
      </div>
      <div v-if="units.length === 0" class="py-12 text-center"><i class="pi pi-building mb-3 text-4xl text-surface-300" /><p class="text-surface-400">住戸情報がありません</p></div>
    </div>
  </div>
</template>
