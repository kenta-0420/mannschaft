<script setup lang="ts">
import type { LoginHistoryResponse } from '~/types/user-settings'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const { getLoginHistory } = useUserSettingsApi()
const { formatDateTime, buildOffsetDateTimeStr } = useDatetime()

const loading = ref(true)
const history = ref<LoginHistoryResponse[]>([])
const nextCursor = ref<string | null>(null)
const hasNext = ref(false)
const loadingMore = ref(false)

const fromDate = ref<Date | null>(null)
const toDate = ref<Date | null>(null)

onMounted(async () => {
  await loadHistory()
})

// BE はオフセット付きの LocalDateTime を受理する（Issue #2508 / PR #2596）ため、
// ユーザーTZのオフセットを付けたまま送る。剥ぎ取ると BE 側で二重にTZ解釈され逆方向にずれる。
function toDateStart(date: Date | null): string | undefined {
  return buildOffsetDateTimeStr(date, '00:00') ?? undefined
}

function toDateEnd(date: Date | null): string | undefined {
  return buildOffsetDateTimeStr(date, '23:59') ?? undefined
}

async function loadHistory(cursor?: string) {
  if (cursor) {
    loadingMore.value = true
  } else {
    loading.value = true
    history.value = []
  }
  try {
    const res = await getLoginHistory(cursor, 5, toDateStart(fromDate.value), toDateEnd(toDate.value))
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

function onFilterChange() {
  nextCursor.value = null
  loadHistory()
}

function clearFilter() {
  fromDate.value = null
  toDate.value = null
  nextCursor.value = null
  loadHistory()
}

function eventLabel(eventType: string) {
  const labels: Record<string, string> = {
    LOGIN_SUCCESS: 'ログイン成功',
    LOGIN_FAILED: 'ログイン失敗',
    WEBAUTHN_LOGIN: 'WebAuthn ログイン',
    WEBAUTHN_LOGIN_FAILED: 'WebAuthn ログイン失敗',
    LOGOUT: 'ログアウト',
    LOGOUT_SESSION: 'セッションログアウト',
    LOGOUT_ALL_SESSIONS: '全セッションログアウト',
    TOKEN_REUSE_DETECTED: 'トークン再利用検知',
    DEVICE_FINGERPRINT_MISMATCH: 'デバイス変更検知',
    NEW_DEVICE_LOGIN: '新規デバイスログイン',
  }
  return labels[eventType] || eventType
}

function eventSeverity(eventType: string) {
  if (eventType === 'LOGIN_FAILED' || eventType === 'WEBAUTHN_LOGIN_FAILED') return 'danger'
  if (eventType === 'TOKEN_REUSE_DETECTED' || eventType === 'DEVICE_FINGERPRINT_MISMATCH') return 'warn'
  if (eventType.includes('LOGOUT')) return 'warn'
  return 'success'
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="ログイン履歴" />

    <SectionCard class="mb-4">
      <div class="flex flex-wrap items-end gap-3">
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">開始日</label>
          <DatePicker
            v-model="fromDate"
            date-format="yy/mm/dd"
            show-icon
            :max-date="toDate ?? undefined"
            class="w-40"
            show-button-bar
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">終了日</label>
          <DatePicker
            v-model="toDate"
            date-format="yy/mm/dd"
            show-icon
            :min-date="fromDate ?? undefined"
            class="w-40"
            show-button-bar
          />
        </div>
        <Button
          label="検索"
          icon="pi pi-search"
          size="small"
          @click="onFilterChange"
        />
        <Button
          v-if="fromDate || toDate"
          label="クリア"
          severity="secondary"
          text
          size="small"
          icon="pi pi-times"
          @click="clearFilter"
        />
      </div>
    </SectionCard>

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
            <span class="text-xs text-surface-400">{{ formatDateTime(item.createdAt) }}</span>
          </div>
        </SectionCard>
      </div>

      <div v-if="hasNext" class="flex justify-center py-4">
        <Button label="もっと読む" text :loading="loadingMore" @click="loadMore" />
      </div>
    </div>
  </div>
</template>
