<script setup lang="ts">
import type { AuditLog } from '~/types/audit-log'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const auditLogApi = useAuditLogApi()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const { t } = useI18n()

const PAGE_SIZE = 30

const logs = ref<AuditLog[]>([])
const loading = ref(true)
const loadingMore = ref(false)
const nextCursor = ref<string | null>(null)
const hasNext = ref(false)

/**
 * 監査ログを読み込む。
 *
 * BE（`/api/v1/teams/{teamId}/audit-logs`）は `CursorPagedResponse` を返すため総件数を持たない。
 * かつては総件数前提のページャーを置き、`meta.totalElements`（BE が送らない幽霊フィールド）を
 * 読んで常に 0 になっていたため、2ページ目に到達できなかった（CMP-260912-1823）。
 *
 * @param cursor 続きを読む場合の起点カーソル。未指定なら先頭から読み直す
 */
async function loadLogs(cursor?: string) {
  if (cursor) loadingMore.value = true
  else loading.value = true
  try {
    const res = await auditLogApi.listByTeam(teamSlug.value, { cursor, limit: PAGE_SIZE })
    logs.value = cursor ? [...logs.value, ...res.data] : res.data
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
  }
  catch {
    notification.error(t('auditLog.loadFailed'))
  }
  finally {
    loading.value = false
    loadingMore.value = false
  }
}

/** 次のカーソルぶんを追記読み込みする。 */
function loadMore() {
  if (nextCursor.value) loadLogs(nextCursor.value)
}

onMounted(() => loadLogs())
</script>

<template>
  <div>
    <PageHeader :title="t('auditLog.title')" class="mb-6" />
    <DataTable :value="logs" :loading="loading" data-key="id" striped-rows>
      <template #empty>
        <div class="py-8 text-center text-surface-500">
          {{ t('auditLog.empty') }}
        </div>
      </template>
      <Column :header="t('auditLog.column.datetime')" style="width: 160px">
        <template #body="{ data }">
          {{ formatDateTime(data.createdAt) }}
        </template>
      </Column>
      <Column field="userName" :header="t('auditLog.column.user')" />
      <Column :header="t('auditLog.column.category')">
        <template #body="{ data }">
          <Badge :value="data.eventCategory" severity="secondary" />
        </template>
      </Column>
      <Column field="eventType" :header="t('auditLog.column.event')" />
    </DataTable>
    <div v-if="hasNext" class="flex justify-center py-4">
      <Button :label="t('label.loadMore')" text :loading="loadingMore" @click="loadMore" />
    </div>
  </div>
</template>
