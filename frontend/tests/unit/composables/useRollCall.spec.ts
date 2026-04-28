import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import type {
  RollCallCandidate,
  RollCallEntry,
  RollCallSessionResponse,
} from '~/types/care'

/**
 * F03.12 §14 useRollCall のユニットテスト。
 *
 * <h3>カバー範囲</h3>
 * <ul>
 *   <li>loadCandidates が API.getCandidates を呼び candidates を更新する</li>
 *   <li>submit がオンライン時に submitRollCall を冪等キー付き body で呼ぶ</li>
 *   <li>submit がオンライン失敗時に error を埋めて throw する</li>
 *   <li>submit がオフライン時に enqueueCareJob を呼んで null を返し、Toast を出す</li>
 *   <li>patchEntry が API.patchEntry を呼ぶ</li>
 * </ul>
 */

// ============================================================
// useRollCallApi のモック
// ============================================================

const mockGetCandidates = vi.fn()
const mockSubmitRollCall = vi.fn()
const mockGetSessions = vi.fn()
const mockPatchEntry = vi.fn()

vi.mock('~/composables/useRollCallApi', () => ({
  useRollCallApi: () => ({
    getCandidates: mockGetCandidates,
    submitRollCall: mockSubmitRollCall,
    getSessions: mockGetSessions,
    patchEntry: mockPatchEntry,
  }),
}))

// ============================================================
// useOfflineCareQueue のモック（useDismissal.spec.ts と同じ factory 形式）
// ============================================================

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

// ============================================================
// 動的 import（モック適用後）
// ============================================================

const { useRollCall } = await import('~/composables/useRollCall')

// ============================================================
// ヘルパ
// ============================================================

function sampleCandidate(overrides: Partial<RollCallCandidate> = {}): RollCallCandidate {
  return {
    userId: 101,
    displayName: '山田太郎',
    avatarUrl: null,
    rsvpStatus: 'ATTENDING',
    isAlreadyCheckedIn: false,
    isUnderCare: false,
    watcherCount: 0,
    ...overrides,
  }
}

function sampleEntry(overrides: Partial<RollCallEntry> = {}): RollCallEntry {
  return { userId: 101, status: 'PRESENT', ...overrides }
}

function sampleSessionResponse(overrides: Partial<RollCallSessionResponse> = {}): RollCallSessionResponse {
  return {
    rollCallSessionId: 'srv-uuid-1',
    createdCount: 1,
    updatedCount: 0,
    guardianNotificationsSent: 0,
    guardianSetupWarnings: [],
    ...overrides,
  }
}

/** navigator.onLine を一時的に書き換える。 */
function setOnline(value: boolean): void {
  Object.defineProperty(globalThis.navigator, 'onLine', {
    configurable: true,
    get: () => value,
  })
}

// ============================================================
// テスト
// ============================================================

beforeEach(() => {
  mockGetCandidates.mockReset()
  mockSubmitRollCall.mockReset()
  mockGetSessions.mockReset()
  mockPatchEntry.mockReset()
  mockEnqueueCareJob.mockReset()
  setOnline(true)
})

describe('useRollCall.loadCandidates', () => {
  it('正常系: API を呼び candidates を更新する', async () => {
    const data = [sampleCandidate(), sampleCandidate({ userId: 102, displayName: '鈴木花子' })]
    mockGetCandidates.mockResolvedValueOnce(data)

    const teamId = ref(1)
    const eventId = ref(2)
    const rc = useRollCall(teamId, eventId)

    await rc.loadCandidates()

    expect(mockGetCandidates).toHaveBeenCalledWith(1, 2)
    expect(rc.candidates.value).toHaveLength(2)
    expect(rc.candidates.value[0]?.displayName).toBe('山田太郎')
    expect(rc.loading.value).toBe(false)
    expect(rc.error.value).toBeNull()
  })

  it('異常系: API 失敗時 error を埋めて throw する', async () => {
    mockGetCandidates.mockRejectedValueOnce(new Error('boom'))
    const rc = useRollCall(ref(1), ref(2))
    await expect(rc.loadCandidates()).rejects.toThrow('boom')
    expect(rc.error.value).toBe('boom')
    expect(rc.loading.value).toBe(false)
  })
})

describe('useRollCall.submit (オンライン)', () => {
  it('submitRollCall を冪等キー付き body で呼び、結果を返す', async () => {
    const response = sampleSessionResponse({
      rollCallSessionId: 'srv-uuid-xyz',
      createdCount: 2,
      guardianSetupWarnings: ['鈴木一郎'],
    })
    mockSubmitRollCall.mockResolvedValueOnce(response)

    const rc = useRollCall(ref(7), ref(11))
    const result = await rc.submit(
      [sampleEntry(), sampleEntry({ userId: 102, status: 'LATE', lateArrivalMinutes: 5 })],
      true,
    )

    expect(mockSubmitRollCall).toHaveBeenCalledTimes(1)
    const [teamId, eventId, body] = mockSubmitRollCall.mock.calls[0] as [number, number, { rollCallSessionId: string; entries: RollCallEntry[]; notifyGuardiansImmediately: boolean }]
    expect(teamId).toBe(7)
    expect(eventId).toBe(11)
    expect(typeof body.rollCallSessionId).toBe('string')
    expect(body.rollCallSessionId.length).toBeGreaterThanOrEqual(8)
    expect(body.notifyGuardiansImmediately).toBe(true)
    expect(body.entries).toHaveLength(2)

    expect(result).toBe(response)
    expect(rc.offlineQueued.value).toBe(false)
    // 警告は呼び出し側がレスポンスから直接受け取る設計
    expect(result?.guardianSetupWarnings).toEqual(['鈴木一郎'])
  })

  it('API 例外時: error を埋めて throw する', async () => {
    mockSubmitRollCall.mockRejectedValueOnce(new Error('server-down'))
    const rc = useRollCall(ref(1), ref(2))
    await expect(rc.submit([sampleEntry()], false)).rejects.toThrow('server-down')
    expect(rc.error.value).toBe('server-down')
    expect(rc.submitting.value).toBe(false)
  })
})

describe('useRollCall.submit (オフライン)', () => {
  it('navigator.onLine=false の時に enqueueCareJob を呼び、null を返し offlineQueued=true', async () => {
    setOnline(false)
    mockEnqueueCareJob.mockResolvedValueOnce(99)

    const rc = useRollCall(ref(3), ref(4))
    const entries = [sampleEntry({ userId: 201, status: 'ABSENT', absenceReason: 'SICK' })]
    const result = await rc.submit(entries, false)

    expect(mockSubmitRollCall).not.toHaveBeenCalled()
    expect(mockEnqueueCareJob).toHaveBeenCalledTimes(1)
    const [job] = mockEnqueueCareJob.mock.calls[0] as [{ type: string; teamId: number; eventId: number; payload: { rollCallSessionId: string; entries: RollCallEntry[]; notifyGuardiansImmediately: boolean } }]
    expect(job.type).toBe('ROLL_CALL')
    expect(job.teamId).toBe(3)
    expect(job.eventId).toBe(4)
    expect(typeof job.payload.rollCallSessionId).toBe('string')
    expect(job.payload.entries).toEqual(entries)
    expect(job.payload.notifyGuardiansImmediately).toBe(false)

    expect(result).toBeNull()
    expect(rc.offlineQueued.value).toBe(true)
  })
})

describe('useRollCall.loadSessions / patchEntry', () => {
  it('loadSessions: API を呼び sessionIds を更新する', async () => {
    mockGetSessions.mockResolvedValueOnce(['uuid-a', 'uuid-b'])
    const rc = useRollCall(ref(1), ref(2))
    await rc.loadSessions()
    expect(mockGetSessions).toHaveBeenCalledWith(1, 2)
    expect(rc.sessionIds.value).toEqual(['uuid-a', 'uuid-b'])
  })

  it('patchEntry: API を呼ぶ（status のみ）', async () => {
    mockPatchEntry.mockResolvedValueOnce(undefined)
    const rc = useRollCall(ref(5), ref(6))
    await rc.patchEntry(101, 'PRESENT')
    expect(mockPatchEntry).toHaveBeenCalledTimes(1)
    const [teamId, eventId, userId, body] = mockPatchEntry.mock.calls[0] as [number, number, number, { userId: number; status: string; lateArrivalMinutes?: number; absenceReason?: string }]
    expect(teamId).toBe(5)
    expect(eventId).toBe(6)
    expect(userId).toBe(101)
    expect(body.status).toBe('PRESENT')
    expect(body.userId).toBe(101)
    expect(body.lateArrivalMinutes).toBeUndefined()
    expect(body.absenceReason).toBeUndefined()
  })

  it('patchEntry: LATE の場合 lateArrivalMinutes が body に乗る', async () => {
    mockPatchEntry.mockResolvedValueOnce(undefined)
    const rc = useRollCall(ref(5), ref(6))
    await rc.patchEntry(101, 'LATE', 15)
    const [, , , body] = mockPatchEntry.mock.calls[0] as [number, number, number, { lateArrivalMinutes?: number }]
    expect(body.lateArrivalMinutes).toBe(15)
  })
})
