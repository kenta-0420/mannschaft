<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useWindowSize } from '@vueuse/core'
import { storeToRefs } from 'pinia'
import type { ChatTab, ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
})

// ─── ストア・ルーター ────────────────────────────────────────────────
const store = useChatTabsStore()
const { tabs, activeTabId } = storeToRefs(store)
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { warn } = useNotification()

// ─── レスポンシブ ───────────────────────────────────────────────────
const { width } = useWindowSize()
const isDesktop = computed(() => width.value >= 768)

// ─── UI 状態 ────────────────────────────────────────────────────────
const showAddDropdown = ref(false)
const contextMenu = reactive({
  show: false,
  tabId: null as string | null,
  channelId: null as number | null,
  position: { x: 0, y: 0 },
})

// v-if="contextMenu.show" が true の場合は必ず非 null（テンプレートで ! 不要にする）
const contextMenuTabId = computed<string>(() => contextMenu.tabId ?? '')
const contextMenuChannelId = computed<number>(() => contextMenu.channelId ?? 0)

// ─── 既存サイドバー用状態（既存機能を全て維持） ─────────────────────
const showCreateDialog = ref(false)
const showContactSearch = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)
const leftTab = ref<'chat' | 'contacts' | 'requests'>('chat')

function onCreated() {
  listRef.value?.refresh()
}

// ─── タブ操作 ───────────────────────────────────────────────────────

/** + ドロップダウンからチャンネルを選択してタブ追加 */
function handleAddTab(channel: ChatChannelResponse) {
  const result = store.addTab(channel)
  showAddDropdown.value = false
  if (!result.ok && result.reason === 'MAX_TABS_REACHED') {
    warn(t('chat.tab.maxReached', { max: 10 }))
  }
}

/** サイドバーのチャンネル一覧からタブを追加（PC 専用動線） */
function onSelectChannel(ch: ChatChannelResponse) {
  const result = store.addTab(ch)
  if (!result.ok && result.reason === 'MAX_TABS_REACHED') {
    warn(t('chat.tab.maxReached', { max: 10 }))
  }
  leftTab.value = 'chat'
}

/** タブの閉じる（× ボタン） */
function handleCloseTab(tabId: string) {
  store.closeTab(tabId)
}

/** タブの切り替え */
function switchTab(tabId: string) {
  store.switchTab(tabId)
}

// ─── 右クリックコンテキストメニュー ────────────────────────────────

function onContextMenu(event: { tabId: string; x: number; y: number }) {
  const tab = tabs.value.find((tab: ChatTab) => tab.id === event.tabId)
  if (!tab) return
  contextMenu.tabId = event.tabId
  contextMenu.channelId = tab.channelId
  contextMenu.position = { x: event.x, y: event.y }
  contextMenu.show = true
}

function onContextMenuAction({ tabId, action }: { tabId: string; action: string }) {
  contextMenu.show = false
  if (action === 'close') {
    store.closeTab(tabId)
  } else if (action === 'closeOthers') {
    store.closeOtherTabs(tabId)
  } else if (action === 'closeRight') {
    store.closeRightTabs(tabId)
  }
  // openInNewWindow は ChatTabContextMenu 内で処理済み
}

// ─── URL クエリ ?channel={id} の自動オープン（設計書 §3.8） ────────

async function openByChannelId(channelId: number): Promise<void> {
  // 既存タブに同じチャンネルがあればフォーカス
  const existing = tabs.value.find((tab: ChatTab) => tab.channelId === channelId)
  if (existing) {
    store.switchTab(existing.id)
    return
  }
  // なければ新規タブとして追加（APIでチャンネル情報を取得）
  try {
    const api = useChatApi()
    const response = await api.getChannel(channelId)
    store.addTab(response.data)
  } catch {
    // Silent スキップ（403/404 等）
    warn(t('chat.tab.channelNotAccessible'))
  }
}

// ─── キーボードショートカット（設計書 §6.2 準拠） ───────────────────

function handleKeydown(event: KeyboardEvent): void {
  if (!event.altKey) return

  // Alt+T: + ドロップダウンを開く
  if (event.key === 't' || event.key === 'T') {
    event.preventDefault()
    if (tabs.value.length < 10) {
      showAddDropdown.value = true
    }
    return
  }

  // Alt+W: 現在のタブを閉じる
  if (event.key === 'w' || event.key === 'W') {
    event.preventDefault()
    if (activeTabId.value) {
      store.closeTab(activeTabId.value)
    }
    return
  }

  // Alt+]: 次のタブへ
  if (event.key === ']') {
    event.preventDefault()
    if (tabs.value.length === 0) return
    const idx = tabs.value.findIndex((tab: ChatTab) => tab.id === activeTabId.value)
    const nextIdx = (idx + 1) % tabs.value.length
    const nextTab = tabs.value[nextIdx]
    if (nextTab) store.switchTab(nextTab.id)
    return
  }

  // Alt+[: 前のタブへ
  if (event.key === '[') {
    event.preventDefault()
    if (tabs.value.length === 0) return
    const idx = tabs.value.findIndex((tab: ChatTab) => tab.id === activeTabId.value)
    const prevIdx = (idx - 1 + tabs.value.length) % tabs.value.length
    const prevTab = tabs.value[prevIdx]
    if (prevTab) store.switchTab(prevTab.id)
    return
  }

  // Alt+1〜9: インデックスジャンプ（1始まり）、Alt+0: 10個目
  const num = event.key === '0' ? 10 : parseInt(event.key, 10)
  if (Number.isInteger(num) && num >= 1 && num <= 10) {
    event.preventDefault()
    const target = tabs.value[num - 1]
    if (target) store.switchTab(target.id)
  }
}

// ─── ライフサイクル ─────────────────────────────────────────────────

onMounted(async () => {
  // localStorage からタブを復元
  await store.restore()

  // URL クエリ ?channel={id} を消費して自動オープン
  const cid = Number(route.query.channel)
  if (Number.isInteger(cid) && cid > 0) {
    await openByChannelId(cid)
    router.replace({ query: {} })
  }

  // キーボードショートカット登録
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="flex h-[calc(100vh-12rem)] flex-col overflow-hidden rounded-xl border-2 border-surface-400 dark:border-surface-500">
    <!-- タブバー（PC・スマホ共通） -->
    <ChatTabBar
      :tabs="tabs"
      :active-tab-id="activeTabId"
      :is-max-reached="tabs.length >= 10"
      @switch="switchTab"
      @close="handleCloseTab"
      @open-add="showAddDropdown = true"
      @contextmenu="onContextMenu"
    />

    <!-- 本体 -->
    <div class="flex min-h-0 flex-1 overflow-hidden">
      <!-- PC のみサイドバー -->
      <aside
        v-if="isDesktop"
        class="flex w-64 shrink-0 flex-col border-r border-surface-200 bg-surface-50 dark:border-surface-700 dark:bg-surface-800"
      >
        <!-- サイドバータブ（chat / contacts / requests） -->
        <div class="flex border-b border-surface-200 dark:border-surface-700">
          <button
            type="button"
            class="flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'chat'
                ? 'border-b-2 border-primary text-primary'
                : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
            "
            @click="leftTab = 'chat'"
          >
            {{ $t('chat.sidebar.chat') }}
          </button>
          <button
            type="button"
            class="relative flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'contacts'
                ? 'border-b-2 border-primary text-primary'
                : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
            "
            @click="leftTab = 'contacts'"
          >
            {{ $t('chat.sidebar.contacts') }}
          </button>
          <button
            type="button"
            class="relative flex-1 py-2 text-sm font-medium transition-colors"
            :class="
              leftTab === 'requests'
                ? 'border-b-2 border-primary text-primary'
                : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
            "
            @click="leftTab = 'requests'"
          >
            {{ $t('chat.sidebar.requests') }}
            <ContactRequestBadge class="absolute -right-1 -top-1" />
          </button>
        </div>

        <!-- サイドバーコンテンツ -->
        <div class="flex-1 overflow-y-auto p-2">
          <template v-if="leftTab === 'chat'">
            <ChatChannelList
              ref="listRef"
              @select="onSelectChannel"
              @create="showCreateDialog = true"
            />
          </template>
          <template v-else-if="leftTab === 'contacts'">
            <div class="mb-2">
              <Button
                :label="$t('chat.sidebar.addByHandle')"
                icon="pi pi-user-plus"
                size="small"
                class="w-full"
                outlined
                @click="showContactSearch = true"
              />
            </div>
            <ContactList />
          </template>
          <template v-else>
            <ContactRequestList />
          </template>
        </div>
      </aside>

      <!-- タブごとに ChatMessagePanel を v-show で並列保持（§4.2 設計準拠） -->
      <div class="relative flex-1 overflow-hidden bg-surface-0 dark:bg-surface-900">
        <template v-if="tabs.length > 0">
          <ChatMessagePanel
            v-for="tab in tabs"
            v-show="tab.id === activeTabId"
            :key="tab.id"
            :channel="tab.channel"
          />
        </template>

        <!-- タブが0個の時の空状態 -->
        <div
          v-else
          class="flex h-full flex-col items-center justify-center text-surface-400"
        >
          <i class="pi pi-comments mb-3 text-4xl" aria-hidden="true" />
          <p>{{ $t('chat.tab.selectChannelEmpty') }}</p>
        </div>
      </div>
    </div>

    <!-- + ドロップダウン（PC: 通常ドロップダウン / スマホ: Bottom Sheet） -->
    <ChatAddTabDropdown
      v-if="showAddDropdown"
      :is-mobile="!isDesktop"
      @select="handleAddTab"
      @close="showAddDropdown = false"
    />

    <!-- 右クリックコンテキストメニュー -->
    <ChatTabContextMenu
      v-if="contextMenu.show"
      :tab-id="contextMenuTabId"
      :channel-id="contextMenuChannelId"
      :position="contextMenu.position"
      @action="onContextMenuAction"
      @close="contextMenu.show = false"
    />

    <!-- チャンネル作成ダイアログ（既存機能維持） -->
    <ChatCreateDialog v-model:visible="showCreateDialog" @created="onCreated" />

    <!-- 連絡先検索ダイアログ（既存機能維持） -->
    <ContactSearchDialog v-model:visible="showContactSearch" @added="listRef?.refresh()" />
  </div>
</template>
