<script setup lang="ts">
import type { ChatMessageResponse } from '~/types/chat'
import type { ChatContextMenuItem } from '~/components/chat/ChatContextMenu.vue'

/** チャット用プリセット絵文字（タイムラインとは独立して定義） */
const CHAT_PRESET_EMOJIS = ['👍', '👏', '🙏', '😊', '❤️', '🔥', '🙇'] as const

const props = defineProps<{
  message: ChatMessageResponse
  canPin?: boolean
  canDelete?: boolean
  /** 投稿者本人かどうか（コンテキストメニュー出し分け用） */
  isMine?: boolean
  /** 承諾/辞退 API 実行中の招待トークン（招待カードのボタンローディング表示用・F04.12） */
  pendingInviteToken?: string | null
}>()

const emit = defineEmits<{
  reply: [messageId: number]
  reaction: [messageId: number, emoji: string]
  pin: [messageId: number]
  delete: [messageId: number]
  bookmark: [messageId: number]
  /** 招待カードの承諾（F04.12）。messageId でローカル差し替え対象を特定する */
  inviteJoin: [messageId: number, token: string]
  /** 招待カードの辞退（F04.12） */
  inviteDecline: [messageId: number, token: string]
}>()

/** この招待カードが承諾/辞退 API 実行中か（F04.12）。 */
const isInvitePending = computed(
  () => props.pendingInviteToken != null && props.pendingInviteToken === props.message.inviteData?.token,
)

const { t } = useI18n()
const { relativeTime } = useRelativeTime()
const showActions = ref(false)
const showEmojiPicker = ref(false)

// --- コンテキストメニュー ---
const showContextMenu = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)

/** タッチ長押し検出用タイマー */
let longPressTimer: ReturnType<typeof setTimeout> | null = null

function onTouchStart(event: TouchEvent) {
  longPressTimer = setTimeout(() => {
    const touch = event.touches[0]
    if (!touch) return
    openContextMenu(touch.clientX, touch.clientY)
  }, 500)
}

function onTouchEnd() {
  if (longPressTimer !== null) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

function onContextMenu(event: MouseEvent) {
  event.preventDefault()
  openContextMenu(event.clientX, event.clientY)
}

function openContextMenu(x: number, y: number) {
  contextMenuX.value = x
  contextMenuY.value = y
  showContextMenu.value = true
}

/** コンテキストメニュー項目（権限に応じて出し分け） */
const contextMenuItems = computed<ChatContextMenuItem[]>(() => {
  const items: ChatContextMenuItem[] = []

  if (!props.message.parentId) {
    items.push({ key: 'reply', label: t('chat.contextMenu.replyInThread'), icon: 'pi pi-reply' })
  }
  items.push({ key: 'react', label: t('chat.contextMenu.react'), icon: 'pi pi-face-smile' })

  if (props.canPin) {
    items.push({
      key: props.message.isPinned ? 'unpin' : 'pin',
      label: props.message.isPinned ? t('chat.contextMenu.unpin') : t('chat.contextMenu.pin'),
      icon: 'pi pi-thumbtack',
    })
  }

  // 24h 以内かつ本人のみ編集可
  const isEditable =
    props.isMine && Date.now() - new Date(props.message.createdAt).getTime() < 24 * 60 * 60 * 1000
  if (isEditable) {
    items.push({ key: 'edit', label: t('chat.contextMenu.edit'), icon: 'pi pi-pencil' })
  }

  items.push({ key: 'copy', label: t('chat.contextMenu.copy'), icon: 'pi pi-copy' })
  items.push({ key: 'forward', label: t('chat.contextMenu.forward'), icon: 'pi pi-share-alt' })
  items.push({
    key: props.message.isBookmarked ? 'removeBookmark' : 'bookmark',
    label: props.message.isBookmarked
      ? t('chat.contextMenu.removeBookmark')
      : t('chat.contextMenu.bookmark'),
    icon: props.message.isBookmarked ? 'pi pi-bookmark-fill' : 'pi pi-bookmark',
  })

  if (props.isMine || props.canDelete) {
    items.push({
      key: 'delete',
      label: t('chat.contextMenu.delete'),
      icon: 'pi pi-trash',
      danger: true,
    })
  }

  return items
})

function onContextMenuSelect(key: string) {
  switch (key) {
    case 'reply':
      emit('reply', props.message.id)
      break
    case 'react':
      showEmojiPicker.value = true
      break
    case 'pin':
    case 'unpin':
      emit('pin', props.message.id)
      break
    case 'copy':
      if (props.message.body) {
        // クリップボード書き込み失敗（権限拒否・非対応ブラウザ）は非クリティカルのため握りつぶす。
        // eslint-disable-next-line no-restricted-syntax -- clipboard 書込は非対応/非セキュア文脈/ユーザー拒否が想定内。best-effort でありデータ影響なし
        navigator.clipboard.writeText(props.message.body).catch(() => {})
      }
      break
    case 'bookmark':
    case 'removeBookmark':
      emit('bookmark', props.message.id)
      break
    case 'delete':
      emit('delete', props.message.id)
      break
    default:
      break
  }
}

function toggleReaction(emoji: string) {
  emit('reaction', props.message.id, emoji)
  showEmojiPicker.value = false
}

/** depth に基づくインデント（最大 200px = depth 10） */
const indentStyle = computed(() => {
  const depth = props.message.depth ?? 0
  if (depth <= 0) return {}
  const px = Math.min(depth * 20, 200)
  return { paddingLeft: `${px + 16}px` }
})
</script>

<template>
  <div
    class="group relative py-1.5 pr-4 transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
    data-testid="chat-message"
    :style="indentStyle"
    @mouseenter="showActions = true"
    @mouseleave="showActions = false; showEmojiPicker = false"
    @touchstart.passive="onTouchStart"
    @touchend.passive="onTouchEnd"
    @touchcancel.passive="onTouchEnd"
    @contextmenu="onContextMenu"
  >
    <!-- システムメッセージ -->
    <div v-if="message.isSystem" class="py-1 text-center text-xs text-surface-400">
      {{ message.body }}
    </div>

    <!-- 削除済み -->
    <div v-else-if="message.isDeleted" class="py-1 text-sm italic text-surface-400">
      このメッセージは削除されました
    </div>

    <!-- 招待カード（F04.12） -->
    <div v-else-if="message.messageType === 'INVITE_CARD' && message.inviteData" class="flex gap-3">
      <Avatar
        :label="message.sender?.displayName?.charAt(0) || '?'"
        shape="circle"
        size="normal"
        class="mt-0.5 shrink-0"
      />
      <div class="min-w-0 flex-1">
        <div class="flex items-baseline gap-2">
          <span class="text-sm font-semibold">{{ message.sender?.displayName || '不明' }}</span>
          <span class="text-xs text-surface-400">{{ relativeTime(message.createdAt) }}</span>
        </div>
        <ChatInviteCard
          :invite="message.inviteData"
          :submitting="isInvitePending"
          @join="(token: string) => emit('inviteJoin', message.id, token)"
          @decline="(token: string) => emit('inviteDecline', message.id, token)"
        />
      </div>
    </div>

    <!-- 通常メッセージ -->
    <template v-else>
      <!-- 掲示板移行バナー（depth >= 10） -->
      <ChatBoardMigrationBanner
        v-if="message.suggestBoardMigration"
        :message-id="message.id"
      />

      <div class="flex gap-3">
        <Avatar
          :label="message.sender?.displayName?.charAt(0) || '?'"
          shape="circle"
          size="normal"
          class="mt-0.5 shrink-0"
        />
        <div class="min-w-0 flex-1">
          <div class="flex items-baseline gap-2">
            <span class="text-sm font-semibold">{{ message.sender?.displayName || '不明' }}</span>
            <span class="text-xs text-surface-400">{{ relativeTime(message.createdAt) }}</span>
            <span v-if="message.isEdited" class="text-xs text-surface-300">(編集済み)</span>
            <i v-if="message.isPinned" class="pi pi-thumbtack text-xs text-amber-500" />
          </div>

          <!-- 転送元 -->
          <div v-if="message.forwardedFrom" class="mb-1 rounded border-l-2 border-surface-300 dark:border-surface-600 bg-surface-50 dark:bg-surface-800 px-2 py-1 text-xs text-surface-500 dark:text-surface-400">
            <span class="font-medium">{{ message.forwardedFrom.sender?.displayName }}</span>
            <span v-if="message.forwardedFrom.channelName"> in #{{ message.forwardedFrom.channelName }}</span>
            <p class="mt-0.5">{{ message.forwardedFrom.body }}</p>
          </div>

          <!-- 本文 -->
          <p class="whitespace-pre-wrap text-sm leading-relaxed">{{ message.body }}</p>

          <!-- 添付ファイル -->
          <div v-if="message.attachments.length > 0" class="mt-1 flex flex-wrap gap-2">
            <a
              v-for="att in message.attachments"
              :key="att.id"
              :href="att.url"
              target="_blank"
              rel="noopener"
              class="inline-flex items-center gap-1 rounded border border-surface-300 dark:border-surface-600 px-2 py-1 text-xs text-primary hover:bg-surface-50 dark:hover:bg-surface-800"
            >
              <i class="pi pi-file" />
              {{ att.fileName }}
            </a>
          </div>

          <!-- リアクション -->
          <div v-if="Object.keys(message.reactionSummary).length > 0" class="mt-1 flex flex-wrap gap-1">
            <button
              v-for="(count, emoji) in message.reactionSummary"
              :key="emoji"
              class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs"
              :class="message.myReactions.includes(String(emoji))
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-surface-200 dark:border-surface-700'"
              @click="toggleReaction(String(emoji))"
            >
              {{ emoji }} {{ count }}
            </button>
          </div>

          <!-- スレッド返信 -->
          <button
            v-if="message.replyCount > 0 && !message.parentId"
            class="mt-1 text-xs font-medium text-primary hover:underline"
            data-testid="thread-reply-btn"
            @click="emit('reply', message.id)"
          >
            {{ $t('chat.thread.replyCount', { count: message.replyCount }) }}
          </button>
        </div>
      </div>

      <!-- ホバーアクション（PC: 右端クイックアクション） -->
      <div
        v-if="showActions"
        class="absolute -top-3 right-4 flex items-center gap-0.5 rounded-md border border-surface-300 dark:border-surface-600 bg-surface-0 dark:bg-surface-900 shadow-sm"
      >
        <button
          class="p-1.5 text-surface-400 dark:text-surface-500 hover:text-surface-600 dark:hover:text-surface-300"
          :title="$t('chat.contextMenu.react')"
          @click="showEmojiPicker = !showEmojiPicker"
        >
          <i class="pi pi-face-smile text-xs" />
        </button>
        <button
          v-if="!message.parentId"
          class="p-1.5 text-surface-400 dark:text-surface-500 hover:text-surface-600 dark:hover:text-surface-300"
          :title="$t('chat.contextMenu.replyInThread')"
          @click="emit('reply', message.id)"
        >
          <i class="pi pi-reply text-xs" />
        </button>
        <button
          class="p-1.5 text-surface-400 dark:text-surface-500 hover:text-surface-600 dark:hover:text-surface-300"
          :title="$t('chat.contextMenu.bookmark')"
          @click="emit('bookmark', message.id)"
        >
          <i :class="message.isBookmarked ? 'pi pi-bookmark-fill text-amber-500' : 'pi pi-bookmark'" class="text-xs" />
        </button>
        <button
          v-if="canPin"
          class="p-1.5 text-surface-400 dark:text-surface-500 hover:text-surface-600 dark:hover:text-surface-300"
          :title="$t('chat.contextMenu.pin')"
          @click="emit('pin', message.id)"
        >
          <i class="pi pi-thumbtack text-xs" />
        </button>
        <button
          v-if="canDelete"
          class="p-1.5 text-surface-400 hover:text-red-500"
          :title="$t('chat.contextMenu.delete')"
          @click="emit('delete', message.id)"
        >
          <i class="pi pi-trash text-xs" />
        </button>
        <!-- ⋯ ボタン（コンテキストメニュー表示） -->
        <button
          class="p-1.5 text-surface-400 dark:text-surface-500 hover:text-surface-600 dark:hover:text-surface-300"
          title="その他"
          @click.stop="openContextMenu($event.clientX, $event.clientY)"
        >
          <i class="pi pi-ellipsis-h text-xs" />
        </button>
      </div>

      <!-- 絵文字ピッカー -->
      <div
        v-if="showEmojiPicker"
        class="absolute -top-10 right-4 z-20 flex gap-1 rounded-lg border border-surface-300 dark:border-surface-600 bg-surface-0 dark:bg-surface-900 p-2 shadow-lg"
      >
        <button
          v-for="emoji in CHAT_PRESET_EMOJIS"
          :key="emoji"
          class="flex h-7 w-7 items-center justify-center rounded text-base hover:bg-surface-100 dark:hover:bg-surface-800"
          @click="toggleReaction(emoji)"
        >
          {{ emoji }}
        </button>
      </div>
    </template>

    <!-- コンテキストメニュー -->
    <ChatContextMenu
      :items="contextMenuItems"
      :visible="showContextMenu"
      :x="contextMenuX"
      :y="contextMenuY"
      @select="onContextMenuSelect"
      @close="showContextMenu = false"
    />
  </div>
</template>
