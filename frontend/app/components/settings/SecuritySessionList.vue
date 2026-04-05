<script setup lang="ts">
import type { AuthSessionResponse } from '~/types/auth'

const props = defineProps<{
  sessions: AuthSessionResponse[]
}>()

const emit = defineEmits<{
  revoke: [id: number]
  revokeAll: []
}>()

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
</script>

<template>
  <SectionCard>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">アクティブセッション</h2>
      <Button
        v-if="props.sessions.length > 0"
        label="全てログアウト"
        icon="pi pi-sign-out"
        severity="danger"
        text
        size="small"
        @click="emit('revokeAll')"
      />
    </div>

    <div v-if="props.sessions.length === 0" class="py-4 text-center text-surface-400">
      セッション情報がありません
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="session in props.sessions"
        :key="session.id"
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">
            {{ session.userAgent || '不明なデバイス' }}
            <Tag v-if="session.isCurrent" value="現在" severity="success" class="ml-2" />
          </p>
          <p class="text-xs text-surface-500">
            IP: {{ session.ipAddress || '-' }} / {{ formatDate(session.createdAt) }}
          </p>
        </div>
        <Button
          v-if="!session.isCurrent"
          icon="pi pi-times"
          severity="danger"
          text
          rounded
          size="small"
          @click="emit('revoke', session.id)"
        />
      </div>
    </div>
  </SectionCard>
</template>
