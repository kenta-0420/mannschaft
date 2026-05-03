<script setup lang="ts">
import type { DirectMailResponse } from '~/types/line'
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getMails } = useDirectMailApi()
const { error: showError } = useNotification()
const mails = ref<DirectMailResponse[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getMails('organization', orgId)
    mails.value = res.data
  } catch {
    showError('メール一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
function getStatusClass(s: string) {
  switch (s) {
    case 'SENT':
      return 'bg-green-100 text-green-700'
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600'
    case 'SCHEDULED':
      return 'bg-blue-100 text-blue-700'
    case 'SENDING':
      return 'bg-yellow-100 text-yellow-700'
    default:
      return 'bg-surface-100'
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="ダイレクトメール" />
      <Button label="メール作成" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="m in mails"
        :key="m.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div class="flex-1">
          <h3 class="text-sm font-semibold">{{ m.title }}</h3>
          <p class="text-xs text-surface-400">{{ m.subject }}</p>
        </div>
        <span
          :class="getStatusClass(String(m.status))"
          class="rounded px-2 py-0.5 text-xs font-medium"
          >{{ m.status }}</span
        >
        <div class="text-right text-xs text-surface-400">
          <p>{{ m.sentCount }}/{{ m.recipientCount }}送信</p>
          <p>開封{{ m.openCount }}</p>
        </div>
      </div>
      <DashboardEmptyState v-if="mails.length === 0" icon="pi pi-envelope" message="メールがありません" />
    </div>
  </div>
</template>
