<script setup lang="ts">
/**
 * F11.1 Phase 5: コンフリクト解決モーダル。
 *
 * PrimeVue Dialog を使い、クライアントデータとサーバーデータを
 * 左右パネルで差分表示する。解決方法は CLIENT_WIN / SERVER_WIN / 破棄 の3つ。
 */
import type { SyncConflictDetail } from '~/types/sync'

const props = defineProps<{
  conflictId: number
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'resolved'): void
}>()

const { t } = useI18n()
const { getConflictDetail, resolveConflict, discardConflict } = useConflictResolver()
const toast = useToast()

const loading = ref(false)
const resolving = ref(false)
const detail = ref<SyncConflictDetail | null>(null)
const errorMessage = ref('')

const dialogVisible = computed({
  get: () => props.visible,
  set: (val: boolean) => emit('update:visible', val),
})

watch(
  () => props.visible,
  async (isVisible) => {
    if (isVisible && props.conflictId) {
      await loadDetail()
    }
  },
)

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getConflictDetail(props.conflictId)
  } catch {
    errorMessage.value = t('conflict.error_load')
  } finally {
    loading.value = false
  }
}

async function handleResolve(resolution: 'CLIENT_WIN' | 'SERVER_WIN') {
  if (!detail.value) return
  resolving.value = true
  try {
    await resolveConflict(detail.value.id, { resolution })
    toast.add({
      severity: 'success',
      summary: t('conflict.resolved'),
      life: 3000,
    })
    emit('resolved')
    dialogVisible.value = false
  } catch {
    toast.add({
      severity: 'error',
      summary: t('conflict.error_resolve'),
      life: 5000,
    })
  } finally {
    resolving.value = false
  }
}

async function handleDiscard() {
  if (!detail.value) return
  resolving.value = true
  try {
    await discardConflict(detail.value.id)
    toast.add({
      severity: 'info',
      summary: t('conflict.discarded'),
      life: 3000,
    })
    emit('resolved')
    dialogVisible.value = false
  } catch {
    toast.add({
      severity: 'error',
      summary: t('conflict.error_resolve'),
      life: 5000,
    })
  } finally {
    resolving.value = false
  }
}

/**
 * JSON オブジェクトの全キーを集約し、差分がある箇所を検出する。
 */
function getAllKeys(
  client: Record<string, unknown>,
  server: Record<string, unknown>,
): string[] {
  const keys = new Set([...Object.keys(client), ...Object.keys(server)])
  return Array.from(keys).sort()
}

function isDifferent(
  key: string,
  client: Record<string, unknown>,
  server: Record<string, unknown>,
): boolean {
  return JSON.stringify(client[key]) !== JSON.stringify(server[key])
}

function formatValue(value: unknown): string {
  if (value === undefined) return '(undefined)'
  if (value === null) return '(null)'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    :header="t('conflict.title')"
    modal
    :closable="!resolving"
    :style="{ width: '90vw', maxWidth: '900px' }"
    class="conflict-resolver-modal"
  >
    <!-- 読み込み中 -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <ProgressSpinner style="width: 40px; height: 40px" />
      <span class="ml-3 text-surface-500">{{ t('conflict.loading') }}</span>
    </div>

    <!-- エラー -->
    <div v-else-if="errorMessage" class="py-8 text-center text-red-500">
      <i class="pi pi-exclamation-triangle mb-2 text-2xl" />
      <p>{{ errorMessage }}</p>
      <Button
        :label="t('sync.retry')"
        icon="pi pi-refresh"
        text
        class="mt-3"
        @click="loadDetail"
      />
    </div>

    <!-- コンフリクト詳細 -->
    <template v-else-if="detail">
      <!-- メタ情報 -->
      <div class="mb-4 flex flex-wrap gap-4 text-sm text-surface-500">
        <span>
          <strong>{{ t('conflict.resource_type') }}:</strong>
          {{ detail.resourceType }}
        </span>
        <span>
          <strong>{{ t('conflict.resource_id') }}:</strong>
          {{ detail.resourceId }}
        </span>
        <span v-if="detail.clientVersion !== null">
          <strong>{{ t('conflict.client_version') }}:</strong>
          {{ detail.clientVersion }}
        </span>
        <span v-if="detail.serverVersion !== null">
          <strong>{{ t('conflict.server_version') }}:</strong>
          {{ detail.serverVersion }}
        </span>
      </div>

      <!-- 差分パネル -->
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <!-- クライアントデータ -->
        <div class="rounded-lg border border-blue-300 bg-blue-50 p-3 dark:border-blue-700 dark:bg-blue-900/20">
          <h3 class="mb-2 font-semibold text-blue-700 dark:text-blue-300">
            <i class="pi pi-user mr-1" />
            {{ t('conflict.client_data') }}
          </h3>
          <div class="space-y-1 text-sm">
            <div
              v-for="key in getAllKeys(detail.clientData, detail.serverData)"
              :key="'client-' + key"
              class="rounded px-2 py-1"
              :class="{
                'bg-green-100 dark:bg-green-900/30': isDifferent(key, detail.clientData, detail.serverData),
              }"
            >
              <span class="font-mono text-xs text-surface-500">{{ key }}:</span>
              <span class="ml-1 break-all font-mono text-xs">
                {{ formatValue(detail.clientData[key]) }}
              </span>
            </div>
          </div>
        </div>

        <!-- サーバーデータ -->
        <div class="rounded-lg border border-orange-300 bg-orange-50 p-3 dark:border-orange-700 dark:bg-orange-900/20">
          <h3 class="mb-2 font-semibold text-orange-700 dark:text-orange-300">
            <i class="pi pi-server mr-1" />
            {{ t('conflict.server_data') }}
          </h3>
          <div class="space-y-1 text-sm">
            <div
              v-for="key in getAllKeys(detail.clientData, detail.serverData)"
              :key="'server-' + key"
              class="rounded px-2 py-1"
              :class="{
                'bg-red-100 dark:bg-red-900/30': isDifferent(key, detail.clientData, detail.serverData),
              }"
            >
              <span class="font-mono text-xs text-surface-500">{{ key }}:</span>
              <span class="ml-1 break-all font-mono text-xs">
                {{ formatValue(detail.serverData[key]) }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- フッターボタン -->
    <template #footer>
      <div class="flex flex-wrap gap-2">
        <Button
          :label="t('conflict.use_mine')"
          icon="pi pi-user"
          severity="info"
          :loading="resolving"
          :disabled="!detail || loading"
          @click="handleResolve('CLIENT_WIN')"
        />
        <Button
          :label="t('conflict.use_server')"
          icon="pi pi-server"
          severity="warn"
          :loading="resolving"
          :disabled="!detail || loading"
          @click="handleResolve('SERVER_WIN')"
        />
        <Button
          :label="t('conflict.discard')"
          icon="pi pi-trash"
          severity="danger"
          text
          :loading="resolving"
          :disabled="!detail || loading"
          @click="handleDiscard"
        />
      </div>
    </template>
  </Dialog>
</template>
