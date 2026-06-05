<script setup lang="ts">
/**
 * F17.1 村機能 — 井戸端会議タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.10
 *
 * 構成（2 ペイン）:
 *   - 左ペイン: 日付ナビ（過去 7 日分の日次スレッド一覧 + メッセージ件数バッジ）
 *   - 右ペイン: 選択された日付のメッセージ表示
 *       - 「今日」選択時: リアルタイムチャット UI（送信フォーム表示 + WebSocket 購読）
 *       - 過去日選択時: 読み取り専用（既存 DailyThreadResponse.messages から表示）
 *
 * 既存資産の再利用:
 *   - useVillageApi: getVillage / getLobbyChannel / listDailyThreads / getDailyThread
 *   - useChatApi: getMessages / sendMessage / subscribeChannel / unsubscribeChannel
 *   - VillageHeader: 上部ヘッダー + タブナビ
 *
 * 注意:
 *   - FE1 の types/village.ts の DailyThreadResponse には threadDate / messages フィールドが
 *     定義されていないが、設計書 §4.10.3 では threadDate と messages が返ることが明記されており、
 *     バックエンド DTO (DailyThreadResponse.java) でも threadDate を返している。
 *     型補強のため、本ファイル内で拡張インターフェースを用意する（FE1 の改変はしない）。
 */
import dayjs from 'dayjs'
import type { Ref } from 'vue'
import type { ChatMessageResponse } from '~/types/chat'
import type {
  DailyThreadResponse,
  LobbyChannelResponse,
  VillageResponse,
} from '~/types/village'
import { useVillageLobbyPresence } from '~/composables/village/useVillageLobbyPresence'

definePageMeta({
  middleware: 'auth',
  key: route => route.fullPath,
})

// =============================================================================
// 型補強 — FE1 の DailyThreadResponse には threadDate / messages が無い
// =============================================================================

/** 日次スレッド一覧アイテム（threadDate を含む拡張型） */
interface LobbyDailyThreadItem extends DailyThreadResponse {
  /** 設計書 §4.10.2 で返る ISO 日付（YYYY-MM-DD） */
  threadDate: string
}

/** 日次スレッド詳細レスポンス（messages を含む拡張型） */
interface LobbyDailyThreadDetail extends DailyThreadResponse {
  threadDate: string
  messages: ChatMessageResponse[]
  nextCursor: string | null
}

// =============================================================================
// ページ初期化
// =============================================================================

const route = useRoute()
const { t } = useI18n()
const villageApi = useVillageApi()
const chatApi = useChatApi()
const { userTimezone } = useDatetime()

const villageId = String(route.params.id)
const presence = useVillageLobbyPresence(villageId)

// 状態
const village = ref<VillageResponse | null>(null)
const lobbyChannel = ref<LobbyChannelResponse | null>(null)
const dailyThreads = ref<LobbyDailyThreadItem[]>([])
const selectedDate = ref<string>('') // YYYY-MM-DD
const messages = ref<ChatMessageResponse[]>([])
const messageBody = ref<string>('')
const loading = ref<boolean>(false)
const sending = ref<boolean>(false)
const scrollPanelRef = ref<{ $el?: HTMLElement } | null>(null)

// =============================================================================
// 日付ヘルパ
// =============================================================================

/** 本日の ISO 日付（ユーザーTZ基準） */
const todayIso = computed(() => dayjs().tz(userTimezone.value).format('YYYY-MM-DD'))

/** 「今日」ボタンが選択されているか */
const isTodaySelected = computed(() => selectedDate.value === todayIso.value)

/** 日付の表示ラベル（今日 / 昨日 / それ以外は localized 短縮形） */
function formatDateLabel(iso: string): string {
  if (iso === todayIso.value) return t('village.lobby.today')
  const today = dayjs.tz(todayIso.value, userTimezone.value)
  const target = dayjs.tz(iso, userTimezone.value)
  const diffDays = today.diff(target, 'day')
  if (diffDays === 1) return t('village.lobby.yesterday')
  // 月日表記
  return target.format('M月D日')
}

// =============================================================================
// データ取得
// =============================================================================

async function loadVillage() {
  village.value = await villageApi.getVillage(villageId)
}

async function loadLobbyChannel() {
  lobbyChannel.value = await villageApi.getLobbyChannel(villageId)
}

async function loadDailyThreads() {
  const res = await villageApi.listDailyThreads(villageId, 7)
  // 拡張型に reshape（threadDate を含むことを設計書で保証）
  const threads = (res.threads ?? []) as LobbyDailyThreadItem[]
  // 念のため新しい順にソート
  dailyThreads.value = [...threads].sort((a, b) =>
    a.threadDate < b.threadDate ? 1 : a.threadDate > b.threadDate ? -1 : 0,
  )
}

/** 過去日: 既存 DailyThreadResponse.messages から取得（読み取り専用） */
async function loadPastDayMessages(date: string) {
  loading.value = true
  try {
    const res = (await villageApi.getDailyThread(villageId, date)) as LobbyDailyThreadDetail
    messages.value = res.messages ?? []
  }
  finally {
    loading.value = false
  }
}

/** 今日: 既存 chat API でメッセージ取得 + WebSocket 購読 */
async function loadTodayMessages() {
  if (!lobbyChannel.value) return
  loading.value = true
  try {
    const res = await chatApi.getMessages(lobbyChannel.value.chatChannelId)
    // chatApi.getMessages は ChatMessageListResponse（data 配列 + meta）を返す
    // バックエンドが古い順か新しい順かを問わず、表示は古い順で揃える
    const sorted = [...res.data].sort((a, b) =>
      a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : 0,
    )
    messages.value = sorted
    nextTick(() => scrollToBottom())
  }
  finally {
    loading.value = false
  }
}

// =============================================================================
// WebSocket リアルタイム受信
// =============================================================================

const wsEventBus = useEventBus<{ type: string; data: unknown }>('chat:ws:event')

const offWsEvent = wsEventBus.on((event) => {
  // 今日のチャネルへの MESSAGE_CREATED のみ反映
  if (!isTodaySelected.value || !lobbyChannel.value) return
  if (event.type === 'MESSAGE_CREATED') {
    const newMsg = event.data as ChatMessageResponse
    if (newMsg.channelId !== lobbyChannel.value.chatChannelId) return
    if (!messages.value.some(m => m.id === newMsg.id)) {
      messages.value.push(newMsg)
      nextTick(() => scrollToBottom())
    }
  }
  else if (event.type === 'MESSAGE_DELETED') {
    const deleted = event.data as { id: number }
    const idx = messages.value.findIndex(m => m.id === deleted.id)
    if (idx !== -1) {
      const original = messages.value[idx]
      if (original) {
        messages.value[idx] = { ...original, isDeleted: true, body: null, sender: null }
      }
    }
  }
})

// =============================================================================
// 日付選択 / 送信
// =============================================================================

async function onSelectDate(date: string) {
  if (selectedDate.value === date) return
  selectedDate.value = date
  if (date === todayIso.value) {
    await loadTodayMessages()
  }
  else {
    await loadPastDayMessages(date)
  }
}

async function onSend() {
  const body = messageBody.value.trim()
  if (!body || !lobbyChannel.value || sending.value) return
  sending.value = true
  try {
    const res = await chatApi.sendMessage(lobbyChannel.value.chatChannelId, body)
    // バックエンドは { data: ChatMessageResponse } で返す（useChatApi 参照）
    const created = res.data
    // WebSocket でも届く可能性があるため重複チェック
    if (!messages.value.some(m => m.id === created.id)) {
      messages.value.push(created)
      nextTick(() => scrollToBottom())
    }
    messageBody.value = ''
  }
  finally {
    sending.value = false
  }
}

function scrollToBottom() {
  const root = scrollPanelRef.value?.$el
  if (!root) return
  // PrimeVue ScrollPanel 内のスクロール用要素を探索
  const content = root.querySelector<HTMLElement>('.p-scrollpanel-content')
  if (content) {
    content.scrollTop = content.scrollHeight
  }
  else {
    root.scrollTop = root.scrollHeight
  }
}

// =============================================================================
// アクション handler（VillageHeader 経由）
// =============================================================================

async function onJoin() {
  if (!village.value) return
  await villageApi.joinVillage(village.value.id, { subjectType: 'USER', subjectId: 0 })
  await loadVillage()
}

function onRequestJoin() {
  navigateTo(`/villages/${villageId}/join-request`)
}

async function onLeave() {
  // 退村は MembershipResponse を別途取得する必要があるため、Phase 1 ではメンバーページに誘導
  navigateTo(`/villages/${villageId}/members`)
}

async function onPin() {
  if (!village.value) return
  await villageApi.addPin(village.value.id)
  await loadVillage()
}

async function onUnpin() {
  if (!village.value) return
  await villageApi.removePin(village.value.id)
  await loadVillage()
}

/** 編集ダイアログ表示状態 — VillageEditDialog (FE α2 で新規実装) を組み込む */
const showEditDialog = ref(false)
function onEdit() {
  showEditDialog.value = true
}

/** 編集 Dialog から更新成功時に村情報を差し替え */
function onVillageUpdated(updated: VillageResponse) {
  village.value = updated
}

/** 通報ダイアログ表示状態 — VillageReportDialog (FE5 完成済) を組み込む */
const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

// =============================================================================
// ライフサイクル
// =============================================================================

async function initialize() {
  await Promise.all([loadVillage(), loadLobbyChannel(), loadDailyThreads()])
  // 今日のスレッドが一覧に無ければ仮想的に追加
  const hasToday = dailyThreads.value.some(t => t.threadDate === todayIso.value)
  if (!hasToday) {
    // バックエンド未生成の場合に備えてダミー要素を先頭に挿入（メッセージ件数 0）
    dailyThreads.value = [
      {
        id: lobbyChannel.value?.todayThreadId ?? '',
        villageId,
        chatChannelId: lobbyChannel.value?.chatChannelId ?? 0,
        threadDate: todayIso.value,
        messageCount: 0,
        summary: null,
        createdAt: dayjs().toISOString(),
      } as LobbyDailyThreadItem,
      ...dailyThreads.value,
    ]
  }
  // デフォルトで今日を選択
  selectedDate.value = todayIso.value
  await loadTodayMessages()
  // WebSocket 購読開始
  if (lobbyChannel.value) {
    chatApi.subscribeChannel(lobbyChannel.value.chatChannelId)
  }
  // 在席インジケーター開始（今日のみ）
  presence.start()
}

onMounted(() => {
  initialize()
})

onBeforeUnmount(() => {
  offWsEvent()
  if (lobbyChannel.value) {
    chatApi.unsubscribeChannel(lobbyChannel.value.chatChannelId)
  }
  presence.stop()
})

// =============================================================================
// 型ガード（テンプレ表示用）
// =============================================================================

const villageRef = village as Ref<VillageResponse | null>
</script>

<template>
  <div>
    <VillageHeader
      v-if="villageRef"
      :village="villageRef"
      active-tab="lobby"
      @join="onJoin"
      @request-join="onRequestJoin"
      @leave="onLeave"
      @pin="onPin"
      @unpin="onUnpin"
      @report-click="onReportClick"
      @edit="onEdit"
    />

    <div class="flex h-[calc(100vh-18rem)] overflow-hidden rounded-xl border border-surface-200 dark:border-surface-700 mt-4 mx-2 sm:mx-4">
      <!-- ============================================================ -->
      <!-- A. 左ペイン: 日付ナビ                                          -->
      <!-- ============================================================ -->
      <aside class="w-44 sm:w-56 shrink-0 border-r border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800 overflow-y-auto">
        <div class="p-3 border-b border-surface-200 dark:border-surface-700">
          <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
            <i class="pi pi-calendar mr-2" />
            {{ t('village.lobby.daily') }}
          </h3>
        </div>
        <ul class="py-1">
          <li v-for="thread in dailyThreads" :key="thread.threadDate">
            <button
              type="button"
              class="w-full flex items-center justify-between gap-2 px-3 py-2 text-left text-sm transition-colors"
              :class="
                selectedDate === thread.threadDate
                  ? 'bg-primary-100 dark:bg-primary-900 text-primary-800 dark:text-primary-100 font-semibold'
                  : 'hover:bg-surface-100 dark:hover:bg-surface-700 text-surface-700 dark:text-surface-200'
              "
              @click="onSelectDate(thread.threadDate)"
            >
              <span class="flex items-center gap-2 min-w-0">
                <i
                  :class="
                    thread.threadDate === todayIso ? 'pi pi-circle-fill text-primary-500' : 'pi pi-circle text-surface-400'
                  "
                  class="text-[6px]"
                />
                <span class="truncate">{{ formatDateLabel(thread.threadDate) }}</span>
              </span>
              <Badge
                v-if="thread.messageCount > 0"
                :value="thread.messageCount"
                severity="secondary"
                size="small"
              />
            </button>
          </li>
        </ul>
      </aside>

      <!-- ============================================================ -->
      <!-- B. 右ペイン: メッセージ                                        -->
      <!-- ============================================================ -->
      <section class="flex-1 flex flex-col bg-surface-0 dark:bg-surface-900 min-w-0">
        <!-- 状態ラベル -->
        <header class="px-4 py-2 border-b border-surface-200 dark:border-surface-700 flex items-center justify-between">
          <h2 class="text-base font-semibold text-surface-800 dark:text-surface-100">
            <i class="pi pi-comments mr-2" />
            {{ formatDateLabel(selectedDate) }}
          </h2>
          <span
            v-if="isTodaySelected"
            class="text-xs text-primary-700 dark:text-primary-300 flex items-center gap-1"
          >
            <i class="pi pi-bolt" />
            {{ t('village.lobby.realtime') }}
          </span>
          <!-- 在席インジケーター（今日のみ） -->
          <div
            v-if="isTodaySelected && presence.activeCount.value > 0"
            class="flex items-center gap-1.5 text-xs text-surface-500 dark:text-surface-400"
          >
            <div class="flex -space-x-1.5">
              <div
                v-for="m in presence.members.value.slice(0, 5)"
                :key="m.userId"
                class="w-6 h-6 rounded-full bg-primary-100 dark:bg-primary-800 border-2 border-white dark:border-surface-900 flex items-center justify-center text-[9px] font-bold text-primary-700 dark:text-primary-200 uppercase"
                :title="m.nickname || '?'"
              >
                {{ m.nickname ? m.nickname[0] : '?' }}
              </div>
            </div>
            <span>{{ t('village.lobby.presence.count', { count: presence.activeCount.value }) }}</span>
          </div>
        </header>

        <!-- メッセージ表示 -->
        <ScrollPanel
          ref="scrollPanelRef"
          class="flex-1"
          style="width: 100%; height: 100%;"
        >
          <div class="p-4 space-y-3">
            <div v-if="loading" class="text-center text-surface-400 py-6">
              <i class="pi pi-spin pi-spinner text-xl" />
            </div>
            <div
              v-else-if="messages.length === 0"
              class="text-center text-surface-400 py-12 text-sm"
            >
              {{ t('village.lobby.empty') }}
            </div>
            <div
              v-for="msg in messages"
              v-else
              :key="msg.id"
              class="flex gap-3"
            >
              <div class="w-9 h-9 rounded-full bg-surface-300 dark:bg-surface-600 flex items-center justify-center text-white text-sm shrink-0">
                <i class="pi pi-user" />
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 text-xs text-surface-500">
                  <span class="font-semibold text-surface-700 dark:text-surface-200">
                    {{ msg.sender?.displayName ?? '—' }}
                  </span>
                  <span>{{ dayjs.tz(msg.createdAt, userTimezone).format('HH:mm') }}</span>
                  <span v-if="msg.isEdited" class="italic">*</span>
                </div>
                <div
                  class="text-sm whitespace-pre-wrap break-words"
                  :class="msg.isDeleted ? 'italic text-surface-400' : 'text-surface-800 dark:text-surface-100'"
                >
                  {{ msg.isDeleted ? '—' : msg.body }}
                </div>
              </div>
            </div>
          </div>
        </ScrollPanel>

        <!-- 送信フォーム（今日のみ） -->
        <footer
          v-if="isTodaySelected"
          class="border-t border-surface-200 dark:border-surface-700 p-3 bg-surface-50 dark:bg-surface-800"
        >
          <div class="flex items-end gap-2">
            <Textarea
              v-model="messageBody"
              :placeholder="t('village.lobby.placeholder')"
              :rows="2"
              auto-resize
              class="flex-1"
              :disabled="sending"
              @keydown.enter.exact.prevent="onSend"
            />
            <Button
              :label="t('village.action.submit')"
              icon="pi pi-send"
              :loading="sending"
              :disabled="!messageBody.trim()"
              @click="onSend"
            />
          </div>
        </footer>
      </section>
    </div>

    <!-- 通報ダイアログ — 対象は村本体 (VILLAGE) -->
    <VillageReportDialog
      v-if="villageRef"
      v-model:visible="showReportDialog"
      :village-id="villageRef.id"
      target-type="VILLAGE"
      :target-ref-id="villageRef.id"
    />

    <!-- 村本体編集ダイアログ — 村長のみ（VillageHeader 側で制御） -->
    <VillageEditDialog
      v-if="villageRef"
      v-model:visible="showEditDialog"
      :village="villageRef"
      @updated="onVillageUpdated"
    />
  </div>
</template>
