<script setup lang="ts">
import type { LoginHistoryResponse } from '~/types/user-settings'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { getLoginHistory } = useUserSettingsApi()

const loading = ref(true)
const history = ref<LoginHistoryResponse[]>([])
const nextCursor = ref<string | null>(null)
const hasNext = ref(false)
const loadingMore = ref(false)

onMounted(async () => {
  await loadHistory()
})

async function loadHistory(cursor?: string) {
  if (cursor) {
    loadingMore.value = true
  } else {
    loading.value = true
  }
  try {
    const res = await getLoginHistory(cursor, 20)
    if (cursor) {
      history.value.push(...res.data)
    } else {
      history.value = res.data
    }
    nextCursor.value = res.meta.nextCursor
    hasNext.value = res.meta.hasNext
  } catch {
    notification.error('ログイン履歴の取得に失敗しました')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function loadMore() {
  if (nextCursor.value && !loadingMore.value) {
    loadHistory(nextCursor.value)
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('ja-JP')
}

function eventLabel(eventType: string) {
  const labels: Record<string, string> = {
    LOGIN_SUCCESS: 'ログイン成功',
    LOGIN_FAILURE: 'ログイン失敗',
    LOGOUT: 'ログアウト',
    TOKEN_REFRESH: 'トークン更新',
    PASSWORD_CHANGE: 'パスワード変更',
    MFA_VERIFY: '2FA認証',
  }
  return labels[eventType] || eventType
}

function eventSeverity(eventType: string) {
  if (eventType === 'LOGIN_FAILURE') return 'danger'
  if (eventType === 'LOGOUT') return 'warn'
  return 'success'
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <BackButton to="/settings" />
    <PageHeader title="ログイン履歴" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in">
      <DashboardEmptyState v-if="history.length === 0" icon="pi-history" message="ログイン履歴がありません" />

      <div v-else class="space-y-3">
        <SectionCard
          v-for="item in history"
          :key="item.id"
        >
          <div class="flex items-start justify-between">
            <div>
              <div class="flex items-center gap-2">
                <Tag
                  :value="eventLabel(item.eventType)"
                  :severity="eventSeverity(item.eventType)"
                />
                <span v-if="item.method" class="text-xs text-surface-500">{{ item.method }}</span>
              </div>
              <p class="mt-2 text-sm text-surface-500">
                <i class="pi pi-globe mr-1" />{{ item.ipAddress || '-' }}
              </p>
              <p class="mt-1 text-xs text-surface-400 line-clamp-1">
                {{ item.userAgent || '-' }}
              </p>
            </div>
            <span class="text-xs text-surface-400">{{ formatDate(item.createdAt) }}</span>
          </div>
        </SectionCard>
      </div>

      <div v-if="hasNext" class="flex justify-center py-4">
        <Button label="もっと読む" text :loading="loadingMore" @click="loadMore" />
      </div>
    </div>
  </div>
</template>
