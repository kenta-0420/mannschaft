<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
  layout: 'organization',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const selectedChannel = ref<ChatChannelResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void; refreshAndSelect: (id: number) => void } | null>(null)

function onSelectChannel(ch: ChatChannelResponse) {
  selectedChannel.value = ch
}

function onCreated() {
  listRef.value?.refresh()
}

async function onChannelCreated(ch: ChatChannelResponse) {
  await listRef.value?.refreshAndSelect(ch.id)
  selectedChannel.value = ch
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="チャット" />
    </div>

    <div class="flex h-[calc(100vh-12rem)] overflow-hidden rounded-xl border-2 border-surface-400 dark:border-surface-500">
      <div class="w-64 shrink-0 border-r border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800">
        <ChatChannelList
          ref="listRef"
          :organization-id="orgSlug"
          @select="onSelectChannel"
          @create="showCreateDialog = true"
        />
      </div>

      <div class="flex-1 bg-surface-0 dark:bg-surface-900">
        <ChatMessagePanel
          v-if="selectedChannel"
          :channel="selectedChannel"
          :can-pin="isAdminOrDeputy"
          :can-delete="isAdmin"
          :organization-id="orgSlug"
          @channel-created="onChannelCreated"
        />
        <DashboardEmptyState v-else icon="pi pi-comments" message="チャンネルを選択してください" />
      </div>
    </div>

    <ChatCreateDialog
      v-model:visible="showCreateDialog"
      :organization-id="orgSlug"
      @created="onCreated"
    />
  </div>
</template>
