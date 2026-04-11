import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 useActionMemoStore のユニットテスト。
 *
 * - createMemo の楽観的 UI（成功時の本物 ID 置換 / 失敗時のロールバック）
 * - mood_enabled = false 時の silent NULL 化
 * - 下書き save / load / clear
 */

// === Mock: useActionMemoApi ===
const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  getMemo: vi.fn(),
  updateMemo: vi.fn(),
  deleteMemo: vi.fn(),
  linkTodo: vi.fn(),
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
}

// 自動 import の useActionMemoApi をモックで置き換える
vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

// useAuthStore の最小モック（_currentUserIdOrAnon が呼ぶ）
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

function makeMemo(overrides: Partial<ActionMemo> = {}): ActionMemo {
  return {
    id: 100,
    memoDate: '2026-04-09',
    content: 'test memo',
    mood: null,
    relatedTodoId: null,
    timelinePostId: null,
    tags: [],
    createdAt: '2026-04-09T08:00:00',
    ...overrides,
  }
}

describe('useActionMemoStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.createMemo.mockReset()
    apiMock.fetchMemos.mockReset()
    apiMock.deleteMemo.mockReset()
    apiMock.getSettings.mockReset()
    apiMock.updateSettings.mockReset()
    if (typeof window !== 'undefined') {
      window.localStorage?.clear()
    }
  })

  describe('createMemo: 楽観的 UI', () => {
    it('正常系: 一時 ID で先頭追加 → 成功で本物 ID に置換される', async () => {
      const store = useActionMemoStore()
      const created = makeMemo({ id: 4521, content: '朝散歩' })
      apiMock.createMemo.mockResolvedValueOnce(created)

      const promise = store.createMemo({ content: '朝散歩' })
      // 楽観的に追加されている時点では一時 ID（負数）を持つ
      expect(store.memos.length).toBe(1)
      expect(store.memos[0]!.id).toBeLessThan(0)
      expect(store.memos[0]!.content).toBe('朝散歩')

      const result = await promise
      // 成功 → 本物 ID に置換
      expect(result?.id).toBe(4521)
      expect(store.memos.length).toBe(1)
      expect(store.memos[0]!.id).toBe(4521)
      expect(store.error).toBeNull()
      expect(store.lastError).toBeNull()
    })

    it('失敗時: API エラーで楽観的に追加した一時メモがロールバックされる', async () => {
      const store = useActionMemoStore()
      const error = Object.assign(new Error('boom'), {
        response: { status: 500, _data: { message: 'server error' } },
      })
      apiMock.createMemo.mockRejectedValueOnce(error)

      const result = await store.createMemo({ content: '失敗するメモ' })

      expect(result).toBeNull()
      expect(store.memos).toHaveLength(0)
      expect(store.lastError).toBe('UNKNOWN')
    })

    it('429 エラーは lastError = RATE_LIMIT に分類される', async () => {
      const store = useActionMemoStore()
      const error = Object.assign(new Error('rate limited'), { status: 429 })
      apiMock.createMemo.mockRejectedValueOnce(error)

      await store.createMemo({ content: '連打したメモ' })

      expect(store.lastError).toBe('RATE_LIMIT')
      expect(store.error).toBe('action_memo.error.rate_limit')
      expect(store.memos).toHaveLength(0) // ロールバック済み
    })
  })

  describe('mood_enabled = false の挙動', () => {
    it('mood を送信しても store の楽観的 memo は mood = null', async () => {
      const store = useActionMemoStore()
      // settings はデフォルトで moodEnabled = false
      expect(store.isMoodEnabled).toBe(false)

      // createMemo は API モックで成功するように設定
      apiMock.createMemo.mockResolvedValueOnce(makeMemo({ id: 1, mood: null }))

      const promise = store.createMemo({ content: 'good memo', mood: 'GOOD' })
      // 楽観的レコードを確認
      expect(store.memos[0]!.mood).toBeNull()
      await promise
      expect(store.memos[0]!.mood).toBeNull()
    })

    it('mood_enabled = true の場合は楽観的レコードに mood が乗る', async () => {
      const store = useActionMemoStore()
      store.settings.moodEnabled = true
      apiMock.createMemo.mockResolvedValueOnce(makeMemo({ id: 2, mood: 'GREAT' }))

      const promise = store.createMemo({ content: 'great', mood: 'GREAT' })
      expect(store.memos[0]!.mood).toBe('GREAT')
      await promise
    })
  })

  describe('Draft persistence', () => {
    it('saveDraft → loadDraft で復元できる', () => {
      const store = useActionMemoStore()
      store.saveDraft(42, '書きかけのメモ')
      expect(store.loadDraft(42)).toBe('書きかけのメモ')
    })

    it('clearDraft で削除される', () => {
      const store = useActionMemoStore()
      store.saveDraft(42, 'temp')
      store.clearDraft(42)
      expect(store.loadDraft(42)).toBe('')
    })

    it('空文字を save しても空文字が返る', () => {
      const store = useActionMemoStore()
      store.saveDraft(42, '何か')
      store.saveDraft(42, '')
      expect(store.loadDraft(42)).toBe('')
    })
  })
})
