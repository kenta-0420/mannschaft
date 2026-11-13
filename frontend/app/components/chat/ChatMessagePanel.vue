<script setup lang="ts">
import { useDebounceFn, useEventBus } from '@vueuse/core'
import type { ChatChannelResponse, ChatMessageResponse } from '~/types/chat'
import {
  mapBeMessage,
  aggregateReactions,
  type BeMessageResponse,
  type BeReaction,
} from '~/composables/chat/chatMessageMapper'
import { useChatMembershipInvite } from '~/composables/chat/useChatMembershipInvite'
import { useChatReadSync } from '~/composables/chat/useChatReadSync'
import { useChatListSync } from '~/composables/chat/useChatListSync'
import { isMembershipGatedChannelType } from '~/utils/chatChannelType'
import type { ActiveThreadItem } from './message-panel/ChatActiveThreadsDrawer.vue'

const props = defineProps<{
  channel: ChatChannelResponse
  canPin?: boolean
  canDelete?: boolean
  teamId?: string
  organizationId?: string
}>()

const emit = defineEmits<{
  channelCreated: [channel: ChatChannelResponse]
}>()

const showInviteDialog = ref(false)
const showMembershipInviteDialog = ref(false)

const { joinInvite, declineInvite } = useChatMembershipInvite()
const { t } = useI18n()

const {
  getMessages,
  sendTyping,
  addReaction,
  removeReaction,
  togglePin,
  editMessage,
  deleteMessage,
  bookmarkMessage,
  removeBookmark,
  getActiveThreads,
  subscribeChannel,
  unsubscribeChannel,
  wsConnectionFailed,
  wsUnauthenticated,
  isChannelDenied,
} = useChatApi()
// 購読拒否（閲覧権限なし）と未認証（再ログインが必要）は別の状態として出し分ける。
const channelDenied = computed(() => isChannelDenied(props.channel.id))
const { showSuccess, showError } = useNotification()
const authStore = useAuthStore()
const { markChannelRead } = useChatReadSync()
// 編集・削除は BE が部屋のプレビュー・未読数を更新する。一覧へ伝えて取り直させる（CMP-261105-1507）
const { notifyChannelContentChanged } = useChatListSync()

/**
 * 他人の投稿も削除できるか（F04.2 §5）。ページが渡す管理者フラグ（スコープ ADMIN）に加え、
 * チャンネルの OWNER / ADMIN も他人の投稿を削除できる。本人の投稿は ChatMessageList が isMine で判定する。
 */
const canDeleteOthers = computed(() => {
  const role = props.channel.viewer?.role
  return Boolean(props.canDelete) || role === 'OWNER' || role === 'ADMIN'
})

useChatChannelEventListener(props.channel.id)

const messages = ref<ChatMessageResponse[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)

// ChatMessageList の scrollContainer 参照を取得するための ref
const messageListRef = ref<{ scrollContainer: HTMLElement | null } | null>(null)
const scrollContainer = computed(() => messageListRef.value?.scrollContainer ?? null)

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

function onActiveThreadSelected(threadId: number) {
  openThread(threadId)
  showActiveThreadsDrawer.value = false
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
  } catch {
    showError(t('chat.message.loadFailed'))
    return
  } finally {
    loading.value = false
  }
  await markCurrentChannelRead()
}

/**
 * 表示中チャンネルを既読にする（CMP-261105-1507）。完了を待ち、成功したら一覧・タブの未読バッジを消す。
 * 既読の対象はメンバー行を持つ種別（DM・グループ DM・チーム/組織チャンネル）だけ。本文を読めた時点で利用者は
 * メンバーである。村ロビー・イベント・大会チャットはメンバー行を持たないので対象外。
 * viewer の有無では判定しない（作成・招待の応答は viewer を持たないため、正規のメンバーでも既読にならなかった）。
 * 失敗は握りつぶさずに通知する。
 */
async function markCurrentChannelRead() {
  if (!isMembershipGatedChannelType(props.channel.identity?.channelType)) return
  try {
    await markChannelRead(props.channel.id)
  } catch {
    showError(t('chat.message.markReadFailed'))
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
    showError(t('chat.message.reactionFailed'))
  }
}

async function onPin(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  try {
    await togglePin(messageId, !msg.isPinned)
    msg.isPinned = !msg.isPinned
    showSuccess(msg.isPinned ? t('chat.message.pinned') : t('chat.message.unpinned'))
  } catch {
    showError(t('chat.message.pinFailed'))
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
    notifyChannelContentChanged(props.channel.id)
    showSuccess(t('chat.message.deleteSuccess'))
  } catch {
    showError(t('chat.message.deleteFailed'))
  }
}

// --- 本人の投稿の編集（CMP-261105-1507） ---
const editingMessageId = ref<number | null>(null)
const editingBody = ref('')
const editSaving = ref(false)
const showEditDialog = computed({
  get: () => editingMessageId.value !== null,
  set: (open: boolean) => {
    if (!open) editingMessageId.value = null
  },
})

function onEdit(messageId: number) {
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg) return
  editingBody.value = msg.body ?? ''
  editingMessageId.value = messageId
}

async function onEditSave(body: string) {
  const messageId = editingMessageId.value
  if (messageId === null) return
  editSaving.value = true
  try {
    const res = await editMessage(messageId, body)
    const idx = messages.value.findIndex((m) => m.id === messageId)
    if (idx !== -1) messages.value[idx] = res.data
    editingMessageId.value = null
    notifyChannelContentChanged(props.channel.id)
    showSuccess(t('chat.message.editSuccess'))
  } catch {
    showError(t('chat.message.editFailed'))
  } finally {
    editSaving.value = false
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
    showError(t('chat.message.bookmarkFailed'))
  }
}

function onSent() {
  loadMessages()
  // 一覧のプレビュー（最後の発言）・並びを最新にする（重複通知は一覧側の reloadSilently がまとめる）
  notifyChannelContentChanged(props.channel.id)
}

// ============================================================
// 承諾型招待カード（F04.12）— 承諾 / 辞退
// ============================================================

/** 承諾/辞退 API 実行中の招待トークン（招待カードのボタンローディング用・多重送信防止）。 */
const pendingInviteToken = ref<number | null>(null)

/**
 * 招待カードの状態をローカルで差し替える（設計書 A-2）。
 *
 * 既存 WS は MESSAGE_CREATED のみで MESSAGE_UPDATED を招待カードに使わないため、
 * ユーザーが操作した当該カード（messageId 既知）を直接 JOINED/REVOKED へ差し替える。
 * 発行者側 DM は次回フェッチ時に BE が inviteData.status を再導出して最終整合する。
 */
function updateInviteStatus(messageId: number, status: 'JOINED' | 'REVOKED') {
  const msg = messages.value.find((m) => m.id === messageId)
  if (msg && msg.inviteData) {
    msg.inviteData = { ...msg.inviteData, status }
  }
}

async function onInviteJoin(messageId: number, tokenId: number) {
  if (pendingInviteToken.value) return
  pendingInviteToken.value = tokenId
  try {
    await joinInvite(tokenId)
    updateInviteStatus(messageId, 'JOINED')
    showSuccess(t('chat.invite.joinedSuccess'))
  } catch {
    showError(t('chat.invite.error.joinFailed'))
  } finally {
    pendingInviteToken.value = null
  }
}

async function onInviteDecline(messageId: number, tokenId: number) {
  if (pendingInviteToken.value) return
  pendingInviteToken.value = tokenId
  try {
    await declineInvite(tokenId)
    updateInviteStatus(messageId, 'REVOKED')
    showSuccess(t('chat.invite.declinedSuccess'))
  } catch {
    showError(t('chat.invite.error.declineFailed'))
  } finally {
    pendingInviteToken.value = null
  }
}

// ============================================================
// タイピングインジケーター
// ============================================================

/** 入力中ユーザーのマップ（userId → displayName）*/
const typingUsers = ref<Map<number, string>>(new Map())

/** タイピング自動消去タイマー（userId → タイマーID） */
const typingTimers: Record<number, ReturnType<typeof setTimeout>> = {}

/** タイピング通知を STOMP でデバウンス送信する（2秒間隔）。ChatMessageInput の typing イベントで呼ばれる。 */
const debouncedSendTyping = useDebounceFn(() => {
  sendTyping(props.channel.id)
}, 2000)

// ============================================================
// WebSocket STOMP 購読 — メッセージリアルタイム受信
// ============================================================

/** WebSocket イベント受信ハンドラ（MESSAGE_CREATED / UPDATED / DELETED / REACTION_UPDATED） */
const wsEventBus = useEventBus<{ type: string; data: unknown }>('chat:ws:event')

const offWsEvent = wsEventBus.on((event) => {
  const currentUserId = authStore.user?.id
  if (event.type === 'MESSAGE_CREATED') {
    // WS の data は BE ネスト生形状のため、マッパーで FE フラット型へ変換する
    const newMsg = mapBeMessage(event.data as BeMessageResponse, currentUserId)
    // 重複チェック: REST API で送信した直後にバックエンドが WS でも同一メッセージを返すことがあるため
    if (!messages.value.some((m) => m.id === newMsg.id)) {
      messages.value.push(newMsg)
      nextTick(() => scrollToBottom())
    }
    // 他者の新着・自分の送信の WS 反射でも、一覧のプレビューを最新にする
    notifyChannelContentChanged(props.channel.id)
  } else if (event.type === 'MESSAGE_UPDATED') {
    const updated = mapBeMessage(event.data as BeMessageResponse, currentUserId)
    const idx = messages.value.findIndex((m) => m.id === updated.id)
    if (idx !== -1) {
      messages.value[idx] = updated
    }
    notifyChannelContentChanged(props.channel.id)
  } else if (event.type === 'MESSAGE_DELETED') {
    const deleted = event.data as { id: number }
    const idx = messages.value.findIndex((m) => m.id === deleted.id)
    if (idx !== -1) {
      messages.value[idx] = { ...messages.value[idx]!, isDeleted: true, body: null, sender: null }
    }
    notifyChannelContentChanged(props.channel.id)
  } else if (event.type === 'REACTION_UPDATED') {
    // BE は { messageId, reactions[] }（生）を送るため、ここで集計形へ変換する
    const reaction = event.data as { messageId: number; reactions: BeReaction[] }
    const idx = messages.value.findIndex((m) => m.id === reaction.messageId)
    if (idx !== -1) {
      const { reactionSummary, myReactions } = aggregateReactions(
        reaction.reactions ?? [],
        currentUserId,
      )
      messages.value[idx] = {
        ...messages.value[idx]!,
        reactionSummary,
        myReactions,
        reactionCount: (reaction.reactions ?? []).length,
      }
    }
  } else if (event.type === 'TYPING') {
    const typing = event.data as { userId: number; displayName: string }
    const currentUserId = authStore.user?.id
    if (typing.userId === currentUserId) return
    typingUsers.value.set(typing.userId, typing.displayName)
    if (typingTimers[typing.userId]) clearTimeout(typingTimers[typing.userId])
    typingTimers[typing.userId] = setTimeout(() => {
      typingUsers.value.delete(typing.userId)
    }, 3000)
  }
})

watch(
  () => props.channel.id,
  (newId, oldId) => {
    if (oldId !== undefined) {
      unsubscribeChannel(oldId)
    }
    closeThread()
    loadMessages()
    // アクティブスレッド数をリセット
    activeThreadCount.value = 0
    activeThreads.value = []
    subscribeChannel(newId)
    // チャンネル切り替え時にタイピングインジケーターをリセット
    typingUsers.value = new Map()
    for (const timerId of Object.values(typingTimers)) {
      clearTimeout(timerId)
    }
  },
)
onMounted(() => {
  loadMessages()
  subscribeChannel(props.channel.id)
})
onUnmounted(() => {
  unsubscribeChannel(props.channel.id)
  offWsEvent()
  for (const timerId of Object.values(typingTimers)) {
    clearTimeout(timerId)
  }
})
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 接続失敗バナー -->
    <div v-if="wsUnauthenticated" class="reconnect-warning" role="status">
      <span>{{ $t('chat.connection.unauthenticated') }}</span>
    </div>
    <div v-else-if="channelDenied" class="reconnect-warning" role="status">
      <span>{{ $t('chat.connection.denied') }}</span>
    </div>
    <div v-else-if="wsConnectionFailed" class="reconnect-warning">
      <span>{{ $t('chat.connection.failed') }}</span>
    </div>

    <!-- チャンネルヘッダー -->
    <ChatChannelHeader
      :channel="channel"
      @invite="showInviteDialog = true"
      @membership-invite="showMembershipInviteDialog = true"
    />

    <!-- アクティブスレッドバッジ -->
    <ChatActiveThreadsBar
      :count="channelActiveThreadCount"
      @open="openActiveThreadsDrawer"
    />

    <!-- メインコンテンツエリア（メッセージ + サイドスレッドパネル） -->
    <div class="flex flex-1 overflow-hidden">
      <!-- メッセージエリア -->
      <div
        class="flex flex-1 flex-col"
        :class="threadRootMessageId ? 'border-r border-surface-200 dark:border-surface-700' : ''"
      >
        <!-- メッセージ一覧 -->
        <ChatMessageList
          ref="messageListRef"
          :messages="messages"
          :loading="loading"
          :has-more="hasMore"
          :next-cursor="nextCursor"
          :can-pin="canPin"
          :can-delete="canDeleteOthers"
          :pending-invite-token="pendingInviteToken"
          @load-more="(cursor: string) => loadMessages(cursor)"
          @reaction="onReaction"
          @pin="onPin"
          @delete="onDelete"
          @edit="onEdit"
          @bookmark="onBookmark"
          @reply="openThread"
          @invite-join="onInviteJoin"
          @invite-decline="onInviteDecline"
        />

        <!-- タイピングインジケーター -->
        <ChatTypingIndicator :typing-users="typingUsers" />

        <!-- 入力 -->
        <ChatMessageInput
          :channel-id="channel.id"
          :disabled="channel.settings.isArchived"
          @sent="onSent"
          @typing="debouncedSendTyping"
        />
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
            :can-delete="canDeleteOthers"
            @close="closeThread"
          />
        </div>
      </Transition>
    </div>
  </div>

  <!-- アクティブスレッドドロワー -->
  <ChatActiveThreadsDrawer
    v-model:visible="showActiveThreadsDrawer"
    :count="channelActiveThreadCount"
    :threads="activeThreads"
    :loading="activeThreadsLoading"
    :has-more="activeThreadsHasMore"
    :next-cursor="activeThreadsNextCursor"
    @select-thread="onActiveThreadSelected"
    @load-more="(cursor: string) => loadActiveThreads(cursor)"
  />

  <ChatInviteToZimmerDialog
    v-model:visible="showInviteDialog"
    :channel-id="channel.id"
    :dm-partner-user-id="channel.dmPartner?.userId"
    :team-id="teamId"
    :organization-id="organizationId"
    @created="(ch) => emit('channelCreated', ch)"
  />

  <ChatMessageEditDialog
    v-model:visible="showEditDialog"
    :initial-body="editingBody"
    :saving="editSaving"
    @save="onEditSave"
  />

  <!-- チーム/組織への承諾型招待モーダル（F04.12）。発行後にメッセージを再取得してカードを反映する -->
  <ChatMembershipInviteDialog
    v-model:visible="showMembershipInviteDialog"
    :channel-id="channel.id"
    @invited="loadMessages()"
  />
</template>

<style scoped>
.reconnect-warning {
  background-color: #fef3c7;
  border-bottom: 1px solid #f59e0b;
  color: #92400e;
  font-size: 0.875rem;
  padding: 8px 16px;
  text-align: center;
}

:global(.dark) .reconnect-warning {
  background-color: #451a03;
  border-bottom-color: #92400e;
  color: #fef3c7;
}

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
