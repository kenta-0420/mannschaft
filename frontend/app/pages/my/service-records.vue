<script setup lang="ts">
import type { ServiceRecordResponse } from '~/types/service'

definePageMeta({ middleware: 'auth' })

const { getMyHistory: getMyRecords } = useServiceRecordApi()
const notification = useNotification()

const records = ref<ServiceRecordResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyRecords()
    records.value = res.data
  } catch {
    notification.error('サービス履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <h1 class="mb-6 text-2xl font-bold">マイサービス履歴</h1>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="r in records"
        :key="r.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ r.title }}</h3>
          <span class="text-xs text-surface-400">{{ r.serviceDate }}</span>
        </div>
        <p v-if="r.recordedBy" class="mt-1 text-xs text-surface-500">担当: {{ r.recordedBy.displayName }}</p>
        <p v-if="r.body" class="mt-1 text-sm text-surface-600">{{ r.body }}</p>
      </div>
      <div v-if="records.length === 0" class="py-12 text-center">
        <i class="pi pi-list mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">サービス履歴がありません</p>
      </div>
    </div>
  </div>
</template>
