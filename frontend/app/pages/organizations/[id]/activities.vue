<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const orgId = Number(route.params.id)

const { getActivities } = useActivityApi()
const { showError } = useNotification()

const activities = ref<Record<string, unknown>[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getActivities({ scope_type: 'ORGANIZATION', scope_id: orgId })
    activities.value = res.data
  } catch {
    showError('活動記録の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
        <h1 class="text-2xl font-bold">活動記録</h1>
      </div>
      <Button label="記録を追加" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="act in activities"
        :key="act.id"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ act.title }}</h3>
          <span class="text-xs text-surface-400">{{ act.activityDate }}</span>
        </div>
        <p v-if="act.location" class="mt-1 text-xs text-surface-400">
          <i class="pi pi-map-marker" /> {{ act.location }}
        </p>
        <p v-if="act.description" class="mt-1 text-sm text-surface-600">{{ act.description }}</p>
        <div class="mt-2 text-xs text-surface-400">参加者 {{ act.participantCount }}名</div>
      </div>
      <div v-if="activities.length === 0" class="py-12 text-center">
        <i class="pi pi-history mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">活動記録がありません</p>
      </div>
    </div>
  </div>
</template>
