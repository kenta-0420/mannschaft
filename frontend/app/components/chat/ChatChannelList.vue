<script setup lang="ts">
import { useDebounceFn } from '@vueuse/core'
import type { ChatChannelResponse } from '~/types/chat'
import { useChatReadSync } from '~/composables/chat/useChatReadSync'
import { useChatListSync } from '~/composables/chat/useChatListSync'

const props = defineProps<{
  teamId?: string
  organizationId?: string
  /**
   * 読み込み後に選択するチャンネル ID（?channel= で開いたとき。CMP-261030-0648）。
   * 一覧に無い（存在しない・見られない）なら何もしない。
   */
  initialChannelId?: number
}>()

const emit = defineEmits<{
  select: [channel: ChatChannelResponse]
  create: []
  /** チャンネル一覧のロード完了時に件数を通知する（モバイル単一ペインの一覧/空状態切替に使用） */
  loaded: [count: number]
}>()

const { getChannels } = useChatApi()
const { showError } = useNotification()
const { t } = useI18n()
const { onChannelRead } = useChatReadSync()
const { onChannelContentChanged } = useChatListSync()

const channels = ref<ChatChannelResponse[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)

/** DM / グループDM（Kabine・Zimmer）かどうか */
function isDmChannel(ch: ChatChannelResponse): boolean {
  return ch.identity.channelType === 'DM' || ch.identity.channelType === 'GROUP_DM'
}

const dmChannels = computed(() => channels.value.filter((ch) => isDmChannel(ch)))
const roomChannels = computed(() => channels.value.filter((ch) => !isDmChannel(ch)))

async function loadChannels(options: { silent?: boolean } = {}) {
  if (!options.silent) loading.value = true
  try {
    const res = await getChannels({
      teamId: props.teamId !== undefined ? Number(props.teamId) : undefined,
      organizationId: props.organizationId !== undefined ? Number(props.organizationId) : undefined,
    })
    channels.value = res.data
    selectInitialChannel()
    emit('loaded', channels.value.length)
  } catch {
    showError(t('chat.channelList.loadFailed'))
    emit('loaded', 0)
  } finally {
    loading.value = false
  }
}

function selectChannel(ch: ChatChannelResponse) {
  selectedId.value = ch.id
  emit('select', ch)
}

/** まだ何も選んでいなければ、initialChannelId のチャンネルを一覧から選ぶ */
function selectInitialChannel() {
  if (props.initialChannelId === undefined || selectedId.value !== null) return
  const initial = channels.value.find((c) => c.id === props.initialChannelId)
  if (initial) selectChannel(initial)
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

/**
 * 既読に成功したチャンネルの未読バッジを消す（CMP-261105-1507）。
 * 一覧は onMounted で一度しか取得しないため、既読の結果をここで反映しないとバッジが残り続ける。
 */
const offChannelRead = onChannelRead((channelId) => {
  const ch = channels.value.find((c) => c.id === channelId)
  if (ch?.viewer) ch.viewer.unreadCount = 0
})

/**
 * 開いている会話で投稿・編集・削除があったら一覧を取り直す（CMP-261105-1507）。
 * BE は編集・削除に追従して部屋のプレビューと未読数を更新するため、取り直せば消した本文が残らない。
 * スピナーは出さず、読み込み失敗は通常どおり通知する。
 */
// REST 応答と WS の同じ通知が続けて来るため、まとめて 1 回だけ取り直す
const reloadSilently = useDebounceFn(() => loadChannels({ silent: true }), 300)
const offContentChanged = onChannelContentChanged(() => reloadSilently())
onUnmounted(() => {
  offChannelRead()
  offContentChanged()
})

async function refreshAndSelect(channelId: number) {
  await loadChannels()
  const ch = channels.value.find((c) => c.id === channelId)
  if (ch) selectChannel(ch)
}

/**
 * 読み込み済みの一覧から channelId のチャンネルを選ぶ（取り直さない）。
 * 一覧に無ければ何もせず false を返す。呼び出し側が取り直しと選択の間で要求の新旧を確かめるために使う。
 */
function selectIfPresent(channelId: number): boolean {
  const ch = channels.value.find((c) => c.id === channelId)
  if (!ch) return false
  selectChannel(ch)
  return true
}

defineExpose({ refresh: loadChannels, refreshAndSelect, selectIfPresent })
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="flex items-center justify-between border-b border-surface-200 dark:border-surface-700 px-3 py-2">
      <span class="text-sm font-semibold">{{ $t('chat.channelList.title') }}</span>
      <Button
        v-tooltip.right="$t('chat.channelList.newConversation')"
        icon="pi pi-plus"
        :aria-label="$t('chat.channelList.addChannel')"
        data-testid="chat-channel-list-create"
        class="!min-h-11 !min-w-11 shrink-0"
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
            <h2 class="text-base font-semibold"><i class="pi pi-user mr-1 text-sm" />{{ $t('chat.channelList.dmSection') }}</h2>
          </div>
          <div
            v-if="dmChannels.length === 0"
            class="px-4 pb-3 text-center text-sm text-surface-400"
            data-testid="chat-channel-list-no-dm"
          >
            {{ $t('chat.channelList.noDm') }}
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
                <Badge
                  v-if="getUnread(ch) > 0"
                  :value="getUnread(ch)"
                  severity="danger"
                  data-testid="chat-channel-unread"
                />
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
              <i class="pi pi-hashtag mr-1 text-sm" />{{ $t('chat.channelList.roomSection') }}
            </h2>
          </div>
          <div
            v-if="roomChannels.length === 0"
            class="px-4 pb-3 text-center text-sm text-surface-400"
            data-testid="chat-channel-list-no-room"
          >
            {{ $t('chat.channelList.noRoom') }}
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
                <Badge
                  v-if="getUnread(ch) > 0"
                  :value="getUnread(ch)"
                  severity="danger"
                  data-testid="chat-channel-unread"
                />
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
