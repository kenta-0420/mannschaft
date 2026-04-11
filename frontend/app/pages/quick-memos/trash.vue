<script setup lang="ts">
import type { QuickMemoResponse } from '~/types/quickMemo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()

const memos = ref<QuickMemoResponse[]>([])
const loading = ref(false)
const page = ref(1)
const totalPages = ref(1)

onMounted(loadTrash)

async function loadTrash() {
  loading.value = true
  try {
    const res = await memoApi.listTrash({ page: page.value, size: 20 })
    memos.value = res.data
    totalPages.value = res.meta.totalPages
  } catch {
    notification.error(t('quick_memo.load_error'))
  } finally {
    loading.value = false
  }
}

async function handleUndelete(id: number) {
  try {
    await memoApi.undeleteMemo(id)
    notification.success(t('quick_memo.trash.restored'))
    loadTrash()
  } catch {
    notification.error(t('quick_memo.trash.restore_error'))
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <div class="mb-4 flex items-center gap-3">
      <Button
        icon="pi pi-arrow-left"
        rounded
        text
        @click="$router.push('/quick-memos')"
      />
      <h1 class="text-xl font-bold">{{ t('quick_memo.trash.title') }}</h1>
    </div>

    <!-- 自動削除の告知 -->
    <Message severity="info" class="mb-4" :closable="false">
      {{ t('quick_memo.trash.auto_delete_notice') }}
    </Message>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-3">
      <Skeleton v-for="i in 5" :key="i" height="80px" border-radius="12px" />
    </div>

    <!-- 空状態 -->
    <div v-else-if="memos.length === 0" class="py-16 text-center text-surface-400">
      <i class="pi pi-trash mb-3 text-4xl" />
      <p>{{ t('quick_memo.trash.no_memos') }}</p>
    </div>

    <!-- 一覧 -->
    <div v-else class="space-y-3">
      <QuickMemoCard
        v-for="memo in memos"
        :key="memo.id"
        :memo="memo"
        @undelete="handleUndelete"
        @click="() => {}"
      />
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1" class="mt-6 flex justify-center">
      <Paginator
        v-model:first="page"
        :rows="1"
        :total-records="totalPages"
        @page="(e: { page: number }) => { page = e.page + 1; loadTrash() }"
      />
    </div>
  </div>
</template>
