<script setup lang="ts">
import type { LoginHistoryResponse } from '~/types/user-settings'

defineProps<{
  loginHistory: LoginHistoryResponse[]
  loginHistoryHasNext: boolean
  loadingMoreHistory: boolean
  loginHistoryNextCursor: string | null
}>()

defineEmits<{
  loadMore: [cursor: string | undefined]
}>()

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}

function eventLabel(eventType: string) {
  return (
    (
      {
        LOGIN_SUCCESS: 'ログイン成功',
        LOGIN_FAILURE: 'ログイン失敗',
        LOGOUT: 'ログアウト',
        TOKEN_REFRESH: 'トークン更新',
        PASSWORD_CHANGE: 'パスワード変更',
        MFA_VERIFY: '2FA認証',
      } as Record<string, string>
    )[eventType] || eventType
  )
}

function eventSeverity(eventType: string) {
  if (eventType === 'LOGIN_FAILURE') return 'danger'
  if (eventType === 'LOGOUT') return 'warn'
  return 'success'
}
</script>

<template>
  <SectionCard title="ログイン履歴">
    <div v-if="loginHistory.length === 0" class="py-8 text-center text-surface-400">
      <i class="pi pi-history mb-2 text-4xl" />
      <p>ログイン履歴がありません</p>
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="item in loginHistory"
        :key="item.id"
        class="rounded-lg border border-surface-100 p-3 dark:border-surface-600"
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
            <p class="mt-1 text-sm text-surface-500">
              <i class="pi pi-globe mr-1" />{{ item.ipAddress || '-' }}
            </p>
            <p class="mt-1 text-xs text-surface-400 line-clamp-1">
              {{ item.userAgent || '-' }}
            </p>
          </div>
          <span class="text-xs text-surface-400">{{ formatDate(item.createdAt) }}</span>
        </div>
      </div>
      <div v-if="loginHistoryHasNext" class="flex justify-center pt-2">
        <Button
          label="もっと読む"
          text
          :loading="loadingMoreHistory"
          @click="$emit('loadMore', loginHistoryNextCursor ?? undefined)"
        />
      </div>
    </div>
  </SectionCard>
</template>
