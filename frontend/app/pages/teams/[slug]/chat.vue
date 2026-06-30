<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const selectedChannel = useState<ChatChannelResponse | null>(`team-chat-channel-${teamSlug}`, () => null)

function onChannelCreated(ch: ChatChannelResponse) {
  selectedChannel.value = ch
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader title="チャット" />
    </div>

    <div class="h-[calc(100vh-12rem)] overflow-hidden rounded-xl border-2 border-surface-400 dark:border-surface-500 bg-surface-0 dark:bg-surface-900">
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
</template>
