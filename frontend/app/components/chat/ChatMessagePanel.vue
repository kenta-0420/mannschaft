<script setup lang="ts">
import type { ChatChannelResponse, ChatMessageResponse } from '~/types/chat'

const props = defineProps<{
  channel: ChatChannelResponse
  canPin?: boolean
  canDelete?: boolean
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  channelCreated: [channel: ChatChannelResponse]
}>()

const showInviteDialog = ref(false)

const {
  getMessages,
  markAsRead,
  addReaction,
  removeReaction,
  togglePin,
  deleteMessage,
  bookmarkMessage,
  removeBookmark,
  getActiveThreads,
} = useChatApi()
const { showSuccess, showError } = useNotification()

useChatChannelEventListener(props.channel.id)

const messages = ref<ChatMessageResponse[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const scrollContainer = ref<HTMLElement | null>(null)

// --- スレッド展開パネル（新版: ChatThreadPanel コンポーネントへ委譲） ---
const threadRootMessageId = ref<number | null>(null)

function openThread(messageId: number) {
  threadRootMessageId.value = messageId
}

function closeThread() {
  threadRootMessageId.value = null
}

// --- アクティブスレッドドロワー ---
const showActiveThreadsDrawer = ref(false)
const activeThreadCount = ref(0)

/** チャンネルの active_thread_count を channel 拡張フィールドから取得（または API で別途取得） */
const channelActiveThreadCount = computed(() => {
  // ChatChannelResponse に active_thread_count が含まれる場合はそちらを優先
  const ch = props.channel as ChatChannelResponse & { activeThreadCount?: number }
  return ch.activeThreadCount ?? activeThreadCount.value
})

interface ActiveThreadItem {
  id: number
  body: string
  replyCount: number
  lastReplyAt: string | null
  lastReplyPreview: string | null
  createdAt: string
}

const activeThreads = ref<ActiveThreadItem[]>([])
const activeThreadsLoading = ref(false)
const activeThreadsNextCursor = ref<string | null>(null)
const activeThreadsHasMore = ref(false)

async function loadActiveThreads(cursor?: string) {
  activeThreadsLoading.value = true
  try {
    const res = await getActiveThreads(props.channel.id, cursor)
    if (!cursor) {
      activeThreads.value = res.data
    } else {
      activeThreads.value.push(...res.data)
    }
    activeThreadCount.value = res.data.length
    activeThreadsNextCursor.value = res.meta.nextCursor
    activeThreadsHasMore.value = res.meta.hasMore
  } catch {
    // アクティブスレッド取得失敗はサイレント（バッジ非表示のみ）
  } finally {
    activeThreadsLoading.value = false
  }
}

function openActiveThreadsDrawer() {
  showActiveThreadsDrawer.value = true
  loadActiveThreads()
}

async function loadMessages(cursor?: string) {
  loading.value = true
  try {
    const res = await getMessages(props.channel.id, cursor)
    if (!cursor) {
      messages.value = res.data.reverse()
      await nextTick()
      scrollToBottom()
    } else {
      const prevHeight = scrollContainer.value?.scrollHeight || 0
      messages.value.unshift(...res.data.reverse())
      await nextTick()
      if (scrollContainer.value) {
        scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight - prevHeight
      }
    }
    nextCursor.value = res.meta.nextCursor
    hasMore.value = res.meta.hasMore
    markAsRead(props.channel.id)
  } catch {
    showError('メッセージの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function scrollToBottom() {
  if (scrollContainer.value) {
    scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
  }
}

async function onReaction(messageId: number, emoji: string) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    if (msg.myReactions.includes(emoji)) {
      await removeReaction(messageId, emoji)
      msg.myReactions = msg.myReactions.filter((e) => e !== emoji)
      msg.reactionSummary[emoji] = (msg.reactionSummary[emoji] || 1) - 1
      if (msg.reactionSummary[emoji] <= 0) {
        msg.reactionSummary = Object.fromEntries(
          Object.entries(msg.reactionSummary).filter(([k]) => k !== emoji),
        )
      }
    } else {
      await addReaction(messageId, emoji)
      msg.myReactions.push(emoji)
      msg.reactionSummary[emoji] = (msg.reactionSummary[emoji] || 0) + 1
    }
  } catch {
    showError('リアクションに失敗しました')
  }
}

async function onPin(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    await togglePin(messageId, !msg.isPinned)
    msg.isPinned = !msg.isPinned
    showSuccess(msg.isPinned ? 'ピン留めしました' : 'ピン解除しました')
  } catch {
    showError('ピン操作に失敗しました')
  }
}

async function onDelete(messageId: number) {
  try {
    await deleteMessage(messageId)
    const msg = messages.value.find((m) => m.id === messageId)
    if (msg) {
      msg.isDeleted = true
      msg.body = null
      msg.sender = null
    }
    showSuccess('メッセージを削除しました')
  } catch {
    showError('削除に失敗しました')
  }
}

async function onBookmark(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    if (msg.isBookmarked) {
      await removeBookmark(messageId)
      msg.isBookmarked = false
    } else {
      await bookmarkMessage(messageId)
      msg.isBookmarked = true
    }
  } catch {
    showError('ブックマークに失敗しました')
  }
}

function onSent() {
  loadMessages()
}

watch(
  () => props.channel.id,
  () => {
    closeThread()
    loadMessages()
    // アクティブスレッド数をリセット
    activeThreadCount.value = 0
    activeThreads.value = []
  },
)
onMounted(() => loadMessages())
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- チャンネルヘッダー -->
    <div class="flex items-center gap-3 border-b border-surface-200 px-4 py-3">
      <i
        :class="
          channel.channelType === 'DIRECT'
            ? 'pi pi-user'
            : channel.isPrivate
              ? 'pi pi-lock'
              : 'pi pi-hashtag'
        "
        class="text-surface-400"
      />
      <div>
        <h3 class="text-sm font-semibold">
          {{
            channel.channelType === 'DIRECT' && channel.dmPartner
              ? channel.dmPartner.displayName
              : channel.name
          }}
        </h3>
        <p v-if="channel.description" class="text-xs text-surface-400">{{ channel.description }}</p>
      </div>
      <div class="ml-auto flex items-center gap-2">
        <Button
          v-if="channel.channelType === 'DIRECT'"
          v-tooltip.bottom="'Zimmerに招待'"
          icon="pi pi-user-plus"
          text
          rounded
          size="small"
          severity="secondary"
          @click="showInviteDialog = true"
        />
        <span class="flex items-center gap-1 text-xs text-surface-400">
          <i class="pi pi-users" />
          {{ channel.memberCount }}
        </span>
      </div>
    </div>

    <!-- アクティブスレッドバッジ -->
    <div
      v-if="channelActiveThreadCount > 0"
      class="flex cursor-pointer items-center gap-1.5 border-b border-surface-100 bg-primary/5 px-4 py-1.5 text-xs text-primary hover:bg-primary/10"
      @click="openActiveThreadsDrawer"
    >
      <i class="pi pi-comments text-xs" />
      <span>{{ $t('chat.thread.activeThreads', { count: channelActiveThreadCount }) }}</span>
      <i class="pi pi-chevron-right ml-auto text-xs" />
    </div>

    <!-- メインコンテンツエリア（メッセージ + サイドスレッドパネル） -->
    <div class="flex flex-1 overflow-hidden">
      <!-- メッセージエリア -->
      <div
        class="flex flex-1 flex-col"
        :class="threadRootMessageId ? 'border-r border-surface-200 dark:border-surface-700' : ''"
      >
        <!-- メッセージ一覧 -->
        <div ref="scrollContainer" class="flex-1 overflow-y-auto">
          <div v-if="hasMore" class="flex justify-center py-2">
            <Button
              label="過去のメッセージを読み込む"
              text
              size="small"
              :loading="loading"
              @click="loadMessages(nextCursor!)"
            />
          </div>
          <ChatMessageBubble
            v-for="msg in messages"
            :key="msg.id"
            :message="msg"
            :can-pin="canPin"
            :can-delete="canDelete"
            @reaction="onReaction"
            @pin="onPin"
            @delete="onDelete"
            @bookmark="onBookmark"
            @reply="openThread"
          />
          <DashboardEmptyState
            v-if="messages.length === 0 && !loading"
            icon="pi pi-comments"
            message="まだメッセージがありません"
          />
        </div>

        <!-- 入力 -->
        <ChatMessageInput :channel-id="channel.id" :disabled="channel.isArchived" @sent="onSent" />
      </div>

      <!-- スレッドパネル（デスクトップ: 右スライドイン） -->
      <Transition name="slide-right">
        <div
          v-if="threadRootMessageId"
          class="w-80 shrink-0 bg-surface-50 dark:bg-surface-900"
        >
          <ChatThreadPanel
            :root-message-id="threadRootMessageId"
            :channel-id="channel.id"
            @close="closeThread"
          />
        </div>
      </Transition>
    </div>
  </div>

  <!-- アクティブスレッドドロワー -->
  <Drawer
    v-model:visible="showActiveThreadsDrawer"
    :header="$t('chat.thread.activeThreads', { count: channelActiveThreadCount })"
    position="right"
    style="width: 360px"
  >
    <div v-if="activeThreadsLoading && activeThreads.length === 0" class="flex justify-center py-8">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>
    <div v-else-if="activeThreads.length === 0" class="py-8 text-center text-sm text-surface-400">
      進行中のスレッドはありません
    </div>
    <ul v-else class="divide-y divide-surface-100">
      <li
        v-for="thread in activeThreads"
        :key="thread.id"
        class="cursor-pointer px-4 py-3 hover:bg-surface-50"
        @click="() => { openThread(thread.id); showActiveThreadsDrawer = false }"
      >
        <p class="line-clamp-2 text-sm">{{ thread.body }}</p>
        <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
          <span>{{ $t('chat.thread.replyCount', { count: thread.replyCount }) }}</span>
          <span v-if="thread.lastReplyPreview" class="truncate">{{ thread.lastReplyPreview }}</span>
        </div>
      </li>
    </ul>
    <div v-if="activeThreadsHasMore" class="flex justify-center py-2">
      <Button
        :label="$t('label.loadMore')"
        text
        size="small"
        :loading="activeThreadsLoading"
        @click="loadActiveThreads(activeThreadsNextCursor!)"
      />
    </div>
  </Drawer>

  <ChatInviteToZimmerDialog
    v-model:visible="showInviteDialog"
    :channel-id="channel.id"
    :dm-partner-user-id="channel.dmPartner?.id"
    :team-id="teamId"
    :organization-id="organizationId"
    @created="(ch) => emit('channelCreated', ch)"
  />
</template>

<style scoped>
.slide-right-enter-active,
.slide-right-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}
.slide-right-enter-from,
.slide-right-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
