<script setup lang="ts">
import type { ServiceRecordResponse } from '~/types/service'

definePageMeta({ middleware: 'auth' })

const { getMyHistory: getMyRecords } = useServiceRecordApi()
const { showError } = useNotification()

const records = ref<ServiceRecordResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyRecords()
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
  <div class="mx-auto max-w-3xl">
    <PageHeader title="マイサービス履歴" />
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <SectionCard
        v-for="r in records"
        :key="r.id"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ r.title }}</h3>
          <span class="text-xs text-surface-400">{{ r.serviceDate }}</span>
        </div>
        <p v-if="r.recordedBy" class="mt-1 text-xs text-surface-500">担当: {{ r.recordedBy.displayName }}</p>
        <p v-if="r.body" class="mt-1 text-sm text-surface-600">{{ r.body }}</p>
        <p v-if="r.templateName" class="mt-1 text-xs text-surface-400">{{ r.templateName }}</p>
      </SectionCard>
      <DashboardEmptyState v-if="records.length === 0" icon="pi-list" message="サービス履歴がありません" />
    </div>
  </div>
</template>
