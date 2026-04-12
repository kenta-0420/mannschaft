<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const { getRecords } = useServiceRecordApi()
const { showError } = useNotification()

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getRecords(teamId)
    records.value = res.data
  } catch {
    showError('サービス履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="サービス履歴" />
      <Button label="記録を追加" icon="pi pi-plus" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="flex flex-col gap-2">
      <SectionCard
        v-for="rec in records"
        :key="rec.id"
        class="flex items-center gap-4"
      >
        <Avatar :label="rec.targetUser?.displayName?.charAt(0) || '?'" shape="circle" />
        <div class="min-w-0 flex-1">
          <h3 class="text-sm font-semibold">{{ rec.title }}</h3>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <span>{{ rec.targetUser?.displayName }}</span>
            <span>{{ rec.serviceDate }}</span>
            <span
              class="rounded px-1.5 py-0.5"
              :class="
                rec.status === 'CONFIRMED'
                  ? 'bg-green-100 text-green-700'
                  : 'bg-surface-100 text-surface-600'
              "
              >{{ rec.status === 'CONFIRMED' ? '確定' : '下書き' }}</span
            >
          </div>
        </div>
      </SectionCard>
      <DashboardEmptyState
        v-if="records.length === 0"
        icon="pi pi-list"
        message="サービス履歴がありません"
      />
    </div>
  </div>
</template>
