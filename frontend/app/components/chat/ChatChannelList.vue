<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

const props = defineProps<{
  teamId?: string
  organizationId?: string
}>()

const emit = defineEmits<{
  select: [channel: ChatChannelResponse]
  create: []
  /** チャンネル一覧のロード完了時に件数を通知する（モバイル単一ペインの一覧/空状態切替に使用） */
  loaded: [count: number]
}>()

const { getChannels } = useChatApi()
const { showError } = useNotification()

const channels = ref<ChatChannelResponse[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)

/** DM / グループDM（Kabine・Zimmer）かどうか */
function isDmChannel(ch: ChatChannelResponse): boolean {
  return ch.identity.channelType === 'DM' || ch.identity.channelType === 'GROUP_DM'
}

const dmChannels = computed(() => channels.value.filter((ch) => isDmChannel(ch)))
const roomChannels = computed(() => channels.value.filter((ch) => !isDmChannel(ch)))

async function loadChannels() {
  loading.value = true
  try {
    const res = await getChannels({
      teamId: props.teamId !== undefined ? Number(props.teamId) : undefined,
      organizationId: props.organizationId !== undefined ? Number(props.organizationId) : undefined,
    })
    channels.value = res.data
    emit('loaded', channels.value.length)
  } catch {
    showError('Zimmer一覧の取得に失敗しました')
    emit('loaded', 0)
  } finally {
    loading.value = false
  }
}

function selectChannel(ch: ChatChannelResponse) {
  selectedId.value = ch.id
  emit('select', ch)
}

function getDisplayName(ch: ChatChannelResponse): string {
  if (ch.dmPartner) {
    return ch.dmPartner.displayName
  }
  return ch.meta.name || ''
}

function getIcon(ch: ChatChannelResponse): string {
  if (ch.dmPartner) return 'pi pi-user'
  if (isDmChannel(ch)) return 'pi pi-users'
  if (ch.settings.isPrivate) return 'pi pi-lock'
  return 'pi pi-hashtag'
}

/** 未読件数（閲覧者状態が無い場合は 0） */
function getUnread(ch: ChatChannelResponse): number {
  return ch.viewer?.unreadCount ?? 0
}

/** 最新メッセージプレビュー */
function getPreview(ch: ChatChannelResponse): string | null {
  return ch.lastMessage?.lastMessagePreview ?? null
}

onMounted(() => loadChannels())

async function refreshAndSelect(channelId: number) {
  await loadChannels()
  const ch = channels.value.find((c) => c.id === channelId)
  if (ch) selectChannel(ch)
}

defineExpose({ refresh: loadChannels, refreshAndSelect })
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex items-center justify-between border-b border-surface-200 dark:border-surface-700 px-3 py-2">
      <span class="text-sm font-semibold">チャット</span>
      <Button
        v-tooltip.right="'新しい会話'"
        icon="pi pi-plus"
        aria-label="チャンネル追加"
        text
        rounded
        size="small"
        @click="emit('create')"
      />
    </div>

    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="flex justify-center py-8">
        <LoadingBounce />
      </div>

      <template v-else>
        <!-- Kabine(DM) セクション -->
        <div class="border-b border-surface-200 dark:border-surface-700">
          <div class="flex items-center justify-between p-3">
            <h2 class="text-base font-semibold"><i class="pi pi-user mr-1 text-sm" />Kabine(DM)</h2>
          </div>
          <div
            v-if="dmChannels.length === 0"
            class="px-4 pb-3 text-center text-sm text-surface-400"
          >
            DMはありません
          </div>
          <button
            v-for="ch in dmChannels"
            :key="ch.id"
            class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
            :class="selectedId === ch.id ? 'bg-primary/10' : ''"
            :data-testid="`chat-channel-${ch.id}`"
            @click="selectChannel(ch)"
          >
            <i class="pi pi-user text-surface-400" />
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between">
                <span
                  class="truncate text-sm font-medium"
                  :class="getUnread(ch) > 0 ? 'font-bold' : ''"
                >
                  {{ getDisplayName(ch) }}
                </span>
                <Badge v-if="getUnread(ch) > 0" :value="getUnread(ch)" severity="danger" />
              </div>
              <p v-if="getPreview(ch)" class="truncate text-xs text-surface-400">
                {{ getPreview(ch) }}
              </p>
            </div>
          </button>
        </div>

        <!-- Zimmer(部屋) セクション -->
        <div>
          <div class="p-3">
            <h2 class="text-base font-semibold">
              <i class="pi pi-hashtag mr-1 text-sm" />Zimmer(部屋)
            </h2>
          </div>
          <div
            v-if="roomChannels.length === 0"
            class="px-4 pb-3 text-center text-sm text-surface-400"
          >
            Zimmer(部屋)がありません
          </div>
          <button
            v-for="ch in roomChannels"
            :key="ch.id"
            class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
            :class="selectedId === ch.id ? 'bg-primary/10' : ''"
            :data-testid="`chat-channel-${ch.id}`"
            @click="selectChannel(ch)"
          >
            <i :class="getIcon(ch)" class="text-surface-400" />
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between">
                <span
                  class="truncate text-sm font-medium"
                  :class="getUnread(ch) > 0 ? 'font-bold' : ''"
                >
                  {{ getDisplayName(ch) }}
                </span>
                <Badge v-if="getUnread(ch) > 0" :value="getUnread(ch)" severity="danger" />
              </div>
              <p v-if="getPreview(ch)" class="truncate text-xs text-surface-400">
                {{ getPreview(ch) }}
              </p>
            </div>
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
