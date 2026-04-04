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

const dmChannels = computed(() => channels.value.filter((ch) => ch.channelType === 'DIRECT'))
const roomChannels = computed(() => channels.value.filter((ch) => ch.channelType !== 'DIRECT'))

async function loadChannels() {
  loading.value = true
  try {
    const res = await getChannels({
      teamId: props.teamId,
      organizationId: props.organizationId,
    })
    channels.value = res.data
  } catch {
    showError('Zimmer一覧の取得に失敗しました')
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

async function refreshAndSelect(channelId: number) {
  await loadChannels()
  const ch = channels.value.find((c) => c.id === channelId)
  if (ch) selectChannel(ch)
}

defineExpose({ refresh: loadChannels, refreshAndSelect })
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex items-center justify-between border-b border-surface-200 px-3 py-2">
      <span class="text-sm font-semibold">チャット</span>
      <Button
        v-tooltip.right="'新しい会話'"
        icon="pi pi-plus"
        text
        rounded
        size="small"
        @click="emit('create')"
      />
    </div>

    <div class="flex-1 overflow-y-auto">
      <div v-if="loading" class="flex justify-center py-8">
        <ProgressSpinner style="width: 30px; height: 30px" />
      </div>

      <template v-else>
        <!-- Kabine(DM) セクション -->
        <div class="border-b border-surface-200">
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
            class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100"
            :class="selectedId === ch.id ? 'bg-primary/10' : ''"
            @click="selectChannel(ch)"
          >
            <i class="pi pi-user text-surface-400" />
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between">
                <span
                  class="truncate text-sm font-medium"
                  :class="ch.unreadCount > 0 ? 'font-bold' : ''"
                >
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
            class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100"
            :class="selectedId === ch.id ? 'bg-primary/10' : ''"
            @click="selectChannel(ch)"
          >
            <i :class="getIcon(ch)" class="text-surface-400" />
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between">
                <span
                  class="truncate text-sm font-medium"
                  :class="ch.unreadCount > 0 ? 'font-bold' : ''"
                >
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
      </template>
    </div>
  </div>
</template>
