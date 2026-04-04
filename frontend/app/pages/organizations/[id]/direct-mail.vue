<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getMails } = useDirectMailApi()
const { error: showError } = useNotification()
const mails = ref<Array<Record<string, unknown>>>([])
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
      <h1 class="text-2xl font-bold">ダイレクトメール</h1>
      <Button label="メール作成" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-2">
      <div
        v-for="m in mails"
        :key="m.id"
        class="flex items-center gap-4 rounded-xl border border-surface-200 bg-surface-0 p-4"
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
      <div v-if="mails.length === 0" class="py-12 text-center">
        <i class="pi pi-envelope mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">メールがありません</p>
      </div>
    </div>
  </div>
</template>
