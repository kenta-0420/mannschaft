/**
 * F04.2.1 チャットマルチタブUI — タブ状態ストア
 *
 * - タブの追加・閉じる・切り替え・一括操作
 * - 最大タブ数: 10（MAX_TABS）
 * - 同一チャンネルの重複タブを許可（VSCode 方式）
 * - ログインユーザーごとに localStorage へ永続化
 * - ページロード時に restore() でタブを復元
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatTab, ChatChannelResponse } from '~/types/chat'
import { useChatTabsPersistence } from '~/composables/useChatTabsPersistence'

/** UUID v4 生成（crypto.randomUUID が使えない環境向けのフォールバック付き） */
function generateUuidV4Fallback(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

function newUuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return generateUuidV4Fallback()
}

export const useChatTabsStore = defineStore('chatTabs', () => {
  /** タブ上限数 */
  const MAX_TABS = 10

  /** 現在開いているタブ一覧 */
  const tabs = ref<ChatTab[]>([])

  /** アクティブなタブのID */
  const activeTabId = ref<string | null>(null)

  const persistence = useChatTabsPersistence()

  /** 現在開いているチャンネルIDの集合（重複タブ対応のため Set で管理） */
  const openChannelIds = computed(() => new Set(tabs.value.map(t => t.channelId)))

  /**
   * タブを追加し、追加したタブをアクティブにする。
   * 上限（MAX_TABS）に達した場合は追加せず reason を返す。
   * 同一チャンネルの重複タブは許可する。
   */
  function addTab(channel: ChatChannelResponse): { ok: boolean; reason?: string } {
    if (tabs.value.length >= MAX_TABS) {
      return { ok: false, reason: 'MAX_TABS_REACHED' }
    }
    const tab: ChatTab = {
      id: newUuid(),
      channelId: channel.id,
      channel,
      createdAt: Date.now(),
    }
    tabs.value.push(tab)
    activeTabId.value = tab.id
    _persist()
    return { ok: true }
  }

  /**
   * 指定タブを閉じる。
   * アクティブタブが閉じられた場合、直前のタブ（なければ直後のタブ）に移動する。
   */
  function closeTab(tabId: string): void {
    const idx = tabs.value.findIndex(t => t.id === tabId)
    if (idx < 0) return
    tabs.value.splice(idx, 1)
    if (activeTabId.value === tabId) {
      // 直前タブ → 直後タブ → null の優先順で移動
      activeTabId.value = tabs.value[idx - 1]?.id ?? tabs.value[idx]?.id ?? null
    }
    _persist()
  }

  /**
   * 指定タブ以外をすべて閉じる（右クリックメニュー: 他のタブを閉じる）。
   */
  function closeOtherTabs(tabId: string): void {
    tabs.value = tabs.value.filter(t => t.id === tabId)
    if (!tabs.value.some(t => t.id === activeTabId.value)) {
      activeTabId.value = tabs.value[0]?.id ?? null
    }
    _persist()
  }

  /**
   * 指定タブより右側のタブをすべて閉じる（右クリックメニュー: 右のタブを閉じる）。
   */
  function closeRightTabs(tabId: string): void {
    const idx = tabs.value.findIndex(t => t.id === tabId)
    if (idx < 0) return
    tabs.value = tabs.value.slice(0, idx + 1)
    if (!tabs.value.some(t => t.id === activeTabId.value)) {
      activeTabId.value = tabs.value[idx]?.id ?? null
    }
    _persist()
  }

  /**
   * 指定タブをアクティブにする。
   * 存在しないタブIDは無視する。
   */
  function switchTab(tabId: string): void {
    if (tabs.value.some(t => t.id === tabId)) {
      activeTabId.value = tabId
      _persist()
    }
  }

  /**
   * 全タブを閉じ、localStorage から永続化データも削除する。
   */
  function closeAllTabs(): void {
    tabs.value = []
    activeTabId.value = null
    const auth = useAuthStore()
    const userId = auth.user?.id ?? null
    if (userId) {
      persistence.clear(userId)
    }
  }

  /**
   * clearAll は closeAllTabs のエイリアス（後方互換）。
   */
  function clearAll(): void {
    closeAllTabs()
  }

  /**
   * ページロード時にlocalStorageからタブを復元する。
   * APIでチャンネル情報を再取得し、取得できなかったタブは自動的に閉じる。
   * 取得失敗タブがある場合は useNotification().info() で通知する。
   */
  async function restore(): Promise<void> {
    const auth = useAuthStore()
    const userId = auth.user?.id ?? null
    if (!userId) return

    const saved = persistence.load(userId)
    if (!saved || saved.tabs.length === 0) return

    const api = useChatApi()
    const results = await Promise.allSettled(
      saved.tabs.map(t => api.getChannel(t.channelId)),
    )

    const restored: ChatTab[] = []
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') {
        const savedTab = saved.tabs[i]
        if (!savedTab) return
        restored.push({
          id: savedTab.id,
          channelId: r.value.data.id,
          channel: r.value.data,
          createdAt: Date.now(),
        })
      }
    })

    tabs.value = restored

    // 保存済みのアクティブタブIDが復元されたタブに存在する場合はそれを使う
    activeTabId.value =
      saved.activeTabId !== null && restored.some(t => t.id === saved.activeTabId)
        ? saved.activeTabId
        : (restored[0]?.id ?? null)

    const skipped = saved.tabs.length - restored.length
    if (skipped > 0) {
      if (import.meta.client) {
        console.warn(`[ChatTabs] ${skipped}件のタブが利用できなくなったため閉じました`)
      }
      const { info } = useNotification()
      info(`${skipped}件のチャンネルにアクセスできなくなったため閉じました`)
    }
  }

  /** タブ状態をlocalStorageに保存する（内部ヘルパー） */
  function _persist(): void {
    const auth = useAuthStore()
    const userId = auth.user?.id ?? null
    if (!userId) return
    persistence.save(userId, tabs.value, activeTabId.value)
  }

  return {
    tabs,
    activeTabId,
    openChannelIds,
    addTab,
    closeTab,
    closeOtherTabs,
    closeRightTabs,
    switchTab,
    clearAll,
    closeAllTabs,
    restore,
  }
})
