import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type {
  AbsenceNoticeRequest,
  AdvanceNoticeResponse,
  LateNoticeRequest,
} from '~/types/care'
import { useAdvanceNotice } from '~/composables/useAdvanceNotice'

/**
 * F03.12 §15 useAdvanceNotice のユニットテスト。
 *
 * <p>動作検証:</p>
 * <ul>
 *   <li>オンライン時: useAdvanceNoticeApi.submitLateNotice / submitAbsenceNotice が呼ばれる</li>
 *   <li>オフライン時: enqueueCareJob にキュー積みされ API は呼ばれない</li>
 *   <li>loadAdvanceNotices: getAdvanceNotices で notices state が更新される</li>
 * </ul>
 *
 * <p>useOnline は @vueuse/core のグローバル auto-import なので
 * vi.mock('@vueuse/core', ...) で差し替える。
 * useOfflineCareQueue 側の Dexie アクセスはオンライン経路では呼ばれないが、
 * オフライン経路では実 Dexie（fake-indexeddb）に書き込まれる前提でテスト。</p>
 */

// ============================================================
// useOnline のモック（true / false を切り替え可能）
// ============================================================
const onlineRef = ref(true)
vi.mock('@vueuse/core', () => ({
  useOnline: () => onlineRef,
}))

// ============================================================
// useAdvanceNoticeApi のモック
// ============================================================
const mockSubmitLateNotice = vi.fn()
const mockSubmitAbsenceNotice = vi.fn()
const mockGetAdvanceNotices = vi.fn()

vi.mock('~/composables/useAdvanceNoticeApi', () => ({
  useAdvanceNoticeApi: () => ({
    submitLateNotice: mockSubmitLateNotice,
    submitAbsenceNotice: mockSubmitAbsenceNotice,
    getAdvanceNotices: mockGetAdvanceNotices,
  }),
}))

// ============================================================
// useOfflineCareQueue.enqueueCareJob のモック
// ============================================================
const mockEnqueueCareJob = vi.fn()
vi.mock('~/composables/jobs/useOfflineCareQueue', () => ({
  enqueueCareJob: (...args: unknown[]) => mockEnqueueCareJob(...args),
}))

function makeResponse(over: Partial<AdvanceNoticeResponse> = {}): AdvanceNoticeResponse {
  return {
    userId: 101,
    displayName: '太郎',
    noticeType: 'LATE',
    expectedArrivalMinutesLate: 20,
    absenceReason: null,
    comment: null,
    createdAt: '2026-04-27T09:00:00.000Z',
    ...over,
  }
}

beforeEach(() => {
  onlineRef.value = true
  mockSubmitLateNotice.mockReset()
  mockSubmitAbsenceNotice.mockReset()
  mockGetAdvanceNotices.mockReset()
  mockEnqueueCareJob.mockReset()
})

describe('useAdvanceNotice.submitLate', () => {
  it('オンライン時は API.submitLateNotice を呼びレスポンスを返す', async () => {
    const teamId = ref(7)
    const eventId = ref(42)
    const expected = makeResponse({ noticeType: 'LATE' })
    mockSubmitLateNotice.mockResolvedValueOnce(expected)

    const { submitLate } = useAdvanceNotice(teamId, eventId)
    const body: LateNoticeRequest = {
      userId: 101,
      expectedArrivalMinutesLate: 20,
      comment: '電車遅延',
    }
    const result = await submitLate(body)

    expect(mockSubmitLateNotice).toHaveBeenCalledTimes(1)
    expect(mockSubmitLateNotice).toHaveBeenCalledWith(7, 42, body)
    expect(mockEnqueueCareJob).not.toHaveBeenCalled()
    expect(result).toEqual(expected)
  })

  it('オフライン時は enqueueCareJob を呼び null を返す', async () => {
    onlineRef.value = false
    mockEnqueueCareJob.mockResolvedValueOnce(123)

    const teamId = ref(7)
    const eventId = ref(42)
    const { submitLate } = useAdvanceNotice(teamId, eventId)
    const body: LateNoticeRequest = {
      userId: 101,
      expectedArrivalMinutesLate: 30,
    }

    const result = await submitLate(body)

    expect(mockSubmitLateNotice).not.toHaveBeenCalled()
    expect(mockEnqueueCareJob).toHaveBeenCalledTimes(1)
    expect(mockEnqueueCareJob).toHaveBeenCalledWith({
      type: 'LATE_NOTICE',
      teamId: 7,
      eventId: 42,
      payload: body,
    })
    expect(result).toBeNull()
  })
})

describe('useAdvanceNotice.submitAbsence', () => {
  it('オンライン時は API.submitAbsenceNotice を呼びレスポンスを返す', async () => {
    const teamId = ref(7)
    const eventId = ref(42)
    const expected = makeResponse({
      noticeType: 'ABSENCE',
      expectedArrivalMinutesLate: null,
      absenceReason: 'SICK',
    })
    mockSubmitAbsenceNotice.mockResolvedValueOnce(expected)

    const { submitAbsence } = useAdvanceNotice(teamId, eventId)
    const body: AbsenceNoticeRequest = {
      userId: 101,
      absenceReason: 'SICK',
      comment: '熱があります',
    }
    const result = await submitAbsence(body)

    expect(mockSubmitAbsenceNotice).toHaveBeenCalledTimes(1)
    expect(mockSubmitAbsenceNotice).toHaveBeenCalledWith(7, 42, body)
    expect(mockEnqueueCareJob).not.toHaveBeenCalled()
    expect(result).toEqual(expected)
  })

  it('オフライン時は enqueueCareJob を呼び null を返す', async () => {
    onlineRef.value = false
    mockEnqueueCareJob.mockResolvedValueOnce(124)

    const teamId = ref(8)
    const eventId = ref(50)
    const { submitAbsence } = useAdvanceNotice(teamId, eventId)
    const body: AbsenceNoticeRequest = {
      userId: 202,
      absenceReason: 'PERSONAL_REASON',
    }
    const result = await submitAbsence(body)

    expect(mockSubmitAbsenceNotice).not.toHaveBeenCalled()
    expect(mockEnqueueCareJob).toHaveBeenCalledTimes(1)
    expect(mockEnqueueCareJob).toHaveBeenCalledWith({
      type: 'ABSENCE_NOTICE',
      teamId: 8,
      eventId: 50,
      payload: body,
    })
    expect(result).toBeNull()
  })
})

describe('useAdvanceNotice.loadAdvanceNotices', () => {
  it('getAdvanceNotices の結果で notices を更新する', async () => {
    const teamId = ref(7)
    const eventId = ref(42)
    const list = [
      makeResponse({ userId: 101, noticeType: 'LATE' }),
      makeResponse({
        userId: 102,
        displayName: '次郎',
        noticeType: 'ABSENCE',
        expectedArrivalMinutesLate: null,
        absenceReason: 'SICK',
      }),
    ]
    mockGetAdvanceNotices.mockResolvedValueOnce(list)

    const { notices, loading, error, loadAdvanceNotices } = useAdvanceNotice(
      teamId,
      eventId,
    )
    expect(notices.value).toEqual([])

    await loadAdvanceNotices()

    expect(mockGetAdvanceNotices).toHaveBeenCalledWith(7, 42)
    expect(notices.value).toEqual(list)
    expect(loading.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('getAdvanceNotices が失敗したら error に格納し notices は空配列', async () => {
    const teamId = ref(7)
    const eventId = ref(42)
    mockGetAdvanceNotices.mockRejectedValueOnce(new Error('forbidden'))

    const { notices, error, loadAdvanceNotices } = useAdvanceNotice(teamId, eventId)
    await loadAdvanceNotices()

    expect(notices.value).toEqual([])
    expect(error.value).toBe('forbidden')
  })
})
