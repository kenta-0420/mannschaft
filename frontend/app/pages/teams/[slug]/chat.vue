<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const selectedChannel = ref<ChatChannelResponse | null>(null)
const chatListRef = ref<{ refresh: () => void } | null>(null)
const showCreateDialog = ref(false)

function onChannelSelect(ch: ChatChannelResponse) {
  selectedChannel.value = ch
}

function onChannelCreated(ch: ChatChannelResponse) {
  selectedChannel.value = ch
  chatListRef.value?.refresh()
}

function onCreated() {
  chatListRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="チャット" />
    </div>

    <div class="flex h-[calc(100vh-12rem)] overflow-hidden rounded-xl border-2 border-surface-400 dark:border-surface-500">
      <!-- 左サイドバー（チャンネル一覧） -->
      <aside class="flex w-64 shrink-0 flex-col border-r border-surface-200 bg-surface-50 dark:border-surface-700 dark:bg-surface-800">
        <ChatChannelList
          ref="chatListRef"
          :team-id="teamSlug"
          @select="onChannelSelect"
          @create="showCreateDialog = true"
        />
      </aside>

      <!-- メインエリア -->
      <div class="flex-1 overflow-hidden bg-surface-0 dark:bg-surface-900">
        <ChatMessagePanel
          v-if="selectedChannel"
          :channel="selectedChannel"
          :can-pin="isAdminOrDeputy"
          :can-delete="isAdmin"
          :team-id="teamSlug"
          @channel-created="onChannelCreated"
        />
        <div v-else class="flex h-full flex-col items-center justify-center">
          <DashboardEmptyState icon="pi pi-comments" message="チャンネルを選択してください" />
        </div>
      </div>
    </div>

    <ChatCreateDialog
      v-model:visible="showCreateDialog"
      :team-id="teamSlug"
      @created="onCreated"
    />
  </div>
</template>
