/**
 * F04.2.1 チャットマルチタブUI — タブ永続化コンポーザブル
 *
 * localStorage へのタブ状態の保存・読み込み・削除を担う。
 * ユーザーIDごとに別キーで管理し、不正なデータは自動除去する。
 */
import type { ChatTab } from '~/types/chat'

const STORAGE_KEY_PREFIX = 'chatTabs:userId='

/** UUID v4 の形式チェック用正規表現 */
const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/** localStorage に保存するデータ構造（チャンネル詳細は含まない） */
interface PersistedTabEntry {
  id: string
  channelId: number
}

interface PersistedData {
  tabs: PersistedTabEntry[]
  activeTabId: string | null
}

export function useChatTabsPersistence() {
  function storageKey(userId: number | string): string {
    return `${STORAGE_KEY_PREFIX}${userId}`
  }

  /**
   * タブ状態を localStorage に保存する。
   * チャンネル詳細（channel スナップショット）は保存しない。
   */
  function save(userId: number | string, tabs: ChatTab[], activeTabId: string | null): void {
    if (!import.meta.client) return
    const data: PersistedData = {
      tabs: tabs.map(t => ({ id: t.id, channelId: t.channelId })),
      activeTabId,
    }
    localStorage.setItem(storageKey(userId), JSON.stringify(data))
  }

  /**
   * localStorage からタブ状態を読み込む。
   * 不正なデータは除外し、壊れたエントリは localStorage から削除する。
   */
  function load(userId: number | string): PersistedData | null {
    if (!import.meta.client) return null
    const raw = localStorage.getItem(storageKey(userId))
    if (!raw) return null
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>

      const validTabs = ((parsed.tabs ?? []) as unknown[]).filter(
        (t): t is PersistedTabEntry =>
          typeof t === 'object' &&
          t !== null &&
          typeof (t as Record<string, unknown>).id === 'string' &&
          UUID_V4_RE.test((t as Record<string, unknown>).id as string) &&
          Number.isInteger((t as Record<string, unknown>).channelId) &&
          ((t as Record<string, unknown>).channelId as number) > 0,
      )

      const activeTabId =
        typeof parsed.activeTabId === 'string' && UUID_V4_RE.test(parsed.activeTabId)
          ? parsed.activeTabId
          : null

      return { tabs: validTabs, activeTabId }
    } catch {
      // JSON パース失敗 → 壊れたデータを削除
      localStorage.removeItem(storageKey(userId))
      return null
    }
  }

  /**
   * 指定ユーザーのタブ状態を localStorage から削除する。
   */
  function clear(userId: number | string): void {
    if (!import.meta.client) return
    localStorage.removeItem(storageKey(userId))
  }

  return { save, load, clear }
}
