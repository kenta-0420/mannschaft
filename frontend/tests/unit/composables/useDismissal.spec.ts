import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type { DismissalStatusResponse } from '~/types/care'

/**
 * F03.12 Phase10 §16 useDismissal 上位 composable のユニットテスト。
 *
 * - loadStatus: API 経由で status が更新される
 * - submit (オンライン): submitDismissal が呼ばれ status が更新される
 * - submit (オフライン): enqueueCareJob が type='DISMISSAL' で呼ばれる
 * - submit: 既に dismissed=true なら submit を呼ばず error をセットする（二重送信防止）
 */

// ---- API モック ----
const mockGetDismissalStatus = vi.fn()
const mockSubmitDismissal = vi.fn()
vi.mock('~/composables/useDismissalApi', () => ({
  useDismissalApi: () => ({
    getDismissalStatus: mockGetDismissalStatus,
    submitDismissal: mockSubmitDismissal,
  }),
}))

// ---- オフラインキューモック ----
const mockEnqueueCareJob = vi.fn()
vi.mock('~/composables/jobs/useOfflineCareQueue', () => ({
  useOfflineCareQueue: () => ({
    enqueueCareJob: mockEnqueueCareJob,
    flushPendingCareJobs: vi.fn(),
    getPendingCareJobCount: vi.fn(),
    getPendingCareJobs: vi.fn(),
    clearAllCareJobs: vi.fn(),
  }),
}))

// ---- 通知モック ----
const mockNotifySuccess = vi.fn()
const mockNotifyInfo = vi.fn()
const mockNotifyWarn = vi.fn()
const mockNotifyError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockNotifySuccess,
    info: mockNotifyInfo,
    warn: mockNotifyWarn,
    error: mockNotifyError,
  }),
}))

// テスト対象（モック設定後に動的 import）
const { useDismissal } = await import('~/composables/useDismissal')

function makeStatus(overrides: Partial<DismissalStatusResponse> = {}): DismissalStatusResponse {
  return {
    dismissalNotificationSentAt: null,
    dismissalNotifiedByUserId: null,
    reminderCount: 0,
    lastReminderAt: null,
    dismissed: false,
    ...overrides,
  }
}

beforeEach(() => {
  mockGetDismissalStatus.mockReset()
  mockSubmitDismissal.mockReset()
  mockEnqueueCareJob.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyInfo.mockReset()
  mockNotifyWarn.mockReset()
  mockNotifyError.mockReset()
  // navigator.onLine をデフォルト true（オンライン）に設定
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
})

describe('useDismissal', () => {
  describe('loadStatus', () => {
    it('API 経由で status が更新される', async () => {
      const teamId = ref(1)
      const eventId = ref(2)
      const expected = makeStatus({ dismissed: false })
      mockGetDismissalStatus.mockResolvedValueOnce(expected)

      const { status, loadStatus, error, loading } = useDismissal(teamId, eventId)

      await loadStatus()

      expect(mockGetDismissalStatus).toHaveBeenCalledWith(1, 2)
      expect(status.value).toEqual(expected)
      expect(error.value).toBeNull()
      expect(loading.value).toBe(false)
    })

    it('API が失敗したら error にセットする', async () => {
      const teamId = ref(1)
      const eventId = ref(2)
      mockGetDismissalStatus.mockRejectedValueOnce(new Error('boom'))

      const { error, loadStatus, status, loading } = useDismissal(teamId, eventId)
      await loadStatus()

      expect(error.value).toBe('boom')
      expect(status.value).toBeNull()
      expect(loading.value).toBe(false)
    })
  })

  describe('submit (オンライン)', () => {
    it('submitDismissal を呼んで status を更新し成功トーストを出す', async () => {
      const teamId = ref(10)
      const eventId = ref(20)
      const next = makeStatus({
        dismissed: true,
        dismissalNotificationSentAt: '2026-04-27T10:00:00.000Z',
      })
      mockSubmitDismissal.mockResolvedValueOnce(next)

      const { submit, status } = useDismissal(teamId, eventId)
      const result = await submit({
        message: 'お疲れさまでした',
        notifyGuardians: true,
      })

      expect(mockSubmitDismissal).toHaveBeenCalledWith(10, 20, {
        message: 'お疲れさまでした',
        notifyGuardians: true,
      })
      expect(result).toEqual(next)
      expect(status.value).toEqual(next)
      expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
      expect(mockEnqueueCareJob).not.toHaveBeenCalled()
    })

    it('API 失敗時は error をセットしてエラートーストを出す', async () => {
      const teamId = ref(10)
      const eventId = ref(20)
      mockSubmitDismissal.mockRejectedValueOnce(new Error('5xx'))

      const { submit, error } = useDismissal(teamId, eventId)
      const result = await submit({})

      expect(result).toBeNull()
      expect(error.value).toBe('5xx')
      expect(mockNotifyError).toHaveBeenCalledTimes(1)
    })
  })

  describe('submit (オフライン)', () => {
    beforeEach(() => {
      Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    })

    it('navigator.onLine=false なら enqueueCareJob を呼びキューに積む', async () => {
      const teamId = ref(7)
      const eventId = ref(99)
      mockEnqueueCareJob.mockResolvedValueOnce(42)

      const { submit, status } = useDismissal(teamId, eventId)
      const result = await submit({ notifyGuardians: false })

      expect(mockEnqueueCareJob).toHaveBeenCalledWith({
        type: 'DISMISSAL',
        teamId: 7,
        eventId: 99,
        payload: { notifyGuardians: false },
      })
      expect(mockSubmitDismissal).not.toHaveBeenCalled()
      expect(result).toBeNull()
      expect(status.value).toBeNull()
      expect(mockNotifyInfo).toHaveBeenCalledTimes(1)
    })

    it('enqueue 失敗時は error にセットする', async () => {
      const teamId = ref(7)
      const eventId = ref(99)
      mockEnqueueCareJob.mockRejectedValueOnce(new Error('IndexedDB unavailable'))

      const { submit, error } = useDismissal(teamId, eventId)
      const result = await submit({})

      expect(result).toBeNull()
      expect(error.value).toBe('IndexedDB unavailable')
      expect(mockNotifyError).toHaveBeenCalledTimes(1)
    })
  })

  describe('二重送信防止', () => {
    it('既に dismissed=true なら submit を呼ばず warn トーストを出す', async () => {
      const teamId = ref(1)
      const eventId = ref(2)
      // 先に loadStatus で dismissed=true 状態を作る
      mockGetDismissalStatus.mockResolvedValueOnce(
        makeStatus({ dismissed: true, dismissalNotificationSentAt: '2026-04-27T00:00:00Z' }),
      )

      const { loadStatus, submit, error } = useDismissal(teamId, eventId)
      await loadStatus()
      const result = await submit({})

      expect(result).toBeNull()
      expect(mockSubmitDismissal).not.toHaveBeenCalled()
      expect(mockEnqueueCareJob).not.toHaveBeenCalled()
      expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
      expect(error.value).toBe('既に解散通知を送信済みです')
    })
  })
})
