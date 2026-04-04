<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

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
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h1 class="text-2xl font-bold">チャット</h1>
    </div>

    <div class="flex h-[calc(100vh-12rem)] overflow-hidden rounded-xl border border-surface-200">
      <!-- サイドバー -->
      <div class="w-64 shrink-0 border-r border-surface-200 bg-surface-50">
        <ChatChannelList
          ref="listRef"
          :team-id="teamId"
          @select="onSelectChannel"
          @create="showCreateDialog = true"
        />
      </div>

      <!-- メッセージパネル -->
      <div class="flex-1 bg-surface-0">
        <ChatMessagePanel
          v-if="selectedChannel"
          :channel="selectedChannel"
          :can-pin="isAdminOrDeputy"
          :can-delete="isAdmin"
          :team-id="teamId"
          @channel-created="onChannelCreated"
        />
        <div v-else class="flex h-full flex-col items-center justify-center text-surface-400">
          <i class="pi pi-comments mb-3 text-4xl" />
          <p>チャンネルを選択してください</p>
        </div>
      </div>
    </div>

    <ChatCreateDialog v-model:visible="showCreateDialog" :team-id="teamId" @created="onCreated" />
  </div>
</template>
