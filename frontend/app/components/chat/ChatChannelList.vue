<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

const props = defineProps<{
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  select: [channel: ChatChannelResponse]
  create: []
}>()

const { getChannels } = useChatApi()
const { showError } = useNotification()

const channels = ref<ChatChannelResponse[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)

async function loadChannels() {
  loading.value = true
  try {
    const res = await getChannels({
      teamId: props.teamId,
      organizationId: props.organizationId,
    })
    channels.value = res.data
  } catch {
    showError('チャンネル一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function selectChannel(ch: ChatChannelResponse) {
  selectedId.value = ch.id
  emit('select', ch)
}

function getDisplayName(ch: ChatChannelResponse): string {
  if (ch.channelType === 'DIRECT' && ch.dmPartner) {
    return ch.dmPartner.displayName
  }
  return ch.name || ''
}

function getIcon(ch: ChatChannelResponse): string {
  if (ch.channelType === 'DIRECT') return 'pi pi-user'
  if (ch.isPrivate) return 'pi pi-lock'
  return 'pi pi-hashtag'
}

onMounted(() => loadChannels())

defineExpose({ refresh: loadChannels })
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- ヘッダー -->
    <div class="flex items-center justify-between border-b border-surface-200 p-3">
      <h2 class="text-sm font-semibold">チャンネル</h2>
      <Button icon="pi pi-plus" text rounded size="small" @click="emit('create')" />
    </div>

    <!-- チャンネル一覧 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 30px; height: 30px" />
      </div>

      <div v-else-if="channels.length === 0" class="p-4 text-center text-sm text-surface-400">
        チャンネルがありません
      </div>

      <button
        v-for="ch in channels"
        :key="ch.id"
        class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100"
        :class="selectedId === ch.id ? 'bg-primary/10' : ''"
        @click="selectChannel(ch)"
      >
        <i :class="getIcon(ch)" class="text-surface-400" />
        <div class="min-w-0 flex-1">
          <div class="flex items-center justify-between">
            <span class="truncate text-sm font-medium" :class="ch.unreadCount > 0 ? 'font-bold' : ''">
              {{ getDisplayName(ch) }}
            </span>
            <Badge v-if="ch.unreadCount > 0" :value="ch.unreadCount" severity="danger" />
          </div>
          <p v-if="ch.lastMessagePreview" class="truncate text-xs text-surface-400">
            {{ ch.lastMessagePreview }}
          </p>
        </div>
      </button>
    </div>
  </div>
</template>
