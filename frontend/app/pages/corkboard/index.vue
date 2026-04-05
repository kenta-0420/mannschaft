<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const { getMyBoards } = useCorkboardApi()
const { showError } = useNotification()
const boards = ref<Record<string, unknown>[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getMyBoards()
    boards.value = res.data
  } catch {
    showError('コルクボードの取得に失敗しました')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">コルクボード</h1>
      <Button label="ボードを作成" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="b in boards"
        :key="b.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4"
        :style="b.backgroundColor ? `border-color: ${b.backgroundColor}40` : ''"
      >
        <h3 class="text-sm font-semibold">{{ b.title }}</h3>
        <p v-if="b.description" class="mt-1 text-xs text-surface-400">{{ b.description }}</p>
        <p class="mt-2 text-xs text-surface-400">{{ b.cardCount }}枚のカード</p>
      </div>
      <div v-if="boards.length === 0" class="col-span-full py-12 text-center">
        <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">コルクボードがありません</p>
      </div>
    </div>
  </div>
</template>
