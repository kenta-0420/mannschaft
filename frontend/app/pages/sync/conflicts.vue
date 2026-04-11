<script setup lang="ts">
/**
 * F11.1 Phase 5: コンフリクト一覧ページ。
 *
 * 自分の未解決コンフリクトを一覧表示し、
 * 各行の「解決」ボタンで ConflictResolverModal を開く。
 */
import type { SyncConflictListItem } from '~/types/sync'
import type { PageMeta as ApiPageMeta } from '~/types/api'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const { getMyConflicts } = useConflictResolver()
const syncStore = useSyncStore()

const loading = ref(false)
const conflicts = ref<SyncConflictListItem[]>([])
const pageMeta = ref<ApiPageMeta>({
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
})
const errorMessage = ref('')

// モーダル制御
const selectedConflictId = ref<number | null>(null)
const showModal = ref(false)

async function loadConflicts(page = 0) {
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await getMyConflicts(page)
    conflicts.value = res.data
    pageMeta.value = res.meta
  } catch {
    errorMessage.value = t('conflict.error_load')
  } finally {
    loading.value = false
  }
}

function openResolver(conflictId: number) {
  selectedConflictId.value = conflictId
  showModal.value = true
}

async function onResolved() {
  // 解決後にリストを再取得
  await loadConflicts(pageMeta.value.page)
  // ストアのコンフリクト情報もクリア（全件再取得するため）
  if (conflicts.value.length === 0) {
    syncStore.clearConflicts()
  }
}

function onPageChange(event: { page: number }) {
  loadConflicts(event.page)
}

onMounted(() => {
  loadConflicts()
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <h1 class="mb-6 text-2xl font-bold">
      <i class="pi pi-exclamation-triangle mr-2 text-orange-500" />
      {{ t('conflict.page_title') }}
    </h1>

    <!-- 読み込み中 -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <!-- エラー -->
    <div v-else-if="errorMessage" class="py-8 text-center text-red-500">
      <i class="pi pi-exclamation-triangle mb-2 text-3xl" />
      <p>{{ errorMessage }}</p>
      <Button
        :label="t('sync.retry')"
        icon="pi pi-refresh"
        text
        class="mt-3"
        @click="loadConflicts()"
      />
    </div>

    <!-- 空状態 -->
    <div
      v-else-if="conflicts.length === 0"
      class="py-16 text-center text-surface-400"
    >
      <i class="pi pi-check-circle mb-3 text-5xl text-green-400" />
      <p class="text-lg">{{ t('conflict.empty') }}</p>
    </div>

    <!-- コンフリクト一覧 -->
    <template v-else>
      <div class="space-y-3">
        <div
          v-for="conflict in conflicts"
          :key="conflict.id"
          class="flex items-center justify-between rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="flex-1">
            <div class="flex items-center gap-3">
              <Tag severity="warn" :value="conflict.resourceType" />
              <span class="text-sm text-surface-500">
                ID: {{ conflict.resourceId }}
              </span>
            </div>
            <div class="mt-1 text-xs text-surface-400">
              {{ t('conflict.created_at') }}: {{ conflict.createdAt }}
            </div>
          </div>
          <Button
            :label="t('conflict.resolve_button')"
            icon="pi pi-wrench"
            size="small"
            severity="warn"
            @click="openResolver(conflict.id)"
          />
        </div>
      </div>

      <!-- ページネーション -->
      <div v-if="pageMeta.totalPages > 1" class="mt-6 flex justify-center">
        <Paginator
          :rows="pageMeta.size"
          :total-records="pageMeta.totalElements"
          :first="pageMeta.page * pageMeta.size"
          @page="onPageChange"
        />
      </div>
    </template>

    <!-- コンフリクト解決モーダル -->
    <ClientOnly>
      <PwaConflictResolverModal
        v-if="selectedConflictId !== null"
        :conflict-id="selectedConflictId"
        :visible="showModal"
        @update:visible="showModal = $event"
        @resolved="onResolved"
      />
    </ClientOnly>
  </div>
</template>
