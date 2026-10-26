import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * useVillagePhase3Api（寄合）の API 契約テスト。
 *
 * 背景: FE の手書き型・実装が BE (`VillageMeetupController`) の実契約から逸脱しており、
 * 投票 405 / 集計 404 / 作成 400 などを実機で引き起こしていた。
 * 既存の E2E は「FE の誤った思い込み」をモックしていたため、この逸脱を検知できなかった。
 *
 * 本テストは **BE の実物**（Controller / DTO を直接確認したもの）を正としてモックし、
 * FE が投げる HTTP verb / パス / body が BE 契約と一致することを検証する。
 *
 * BE 実体（backend/.../village/controller/VillageMeetupController.java）:
 * - POST   /api/v1/villages/{vid}/meetups                                            — 作成（candidateDates は List<MeetupCandidateDateInput> object 配列 {date,time?}。#2357）
 * - GET    /api/v1/villages/{vid}/meetups?status=                                    — 一覧（status は PLANNING/CONFIRMED/CANCELLED）
 * - POST   /api/v1/villages/{vid}/meetups/{mid}/candidate-dates                      — 候補日追加（201・候補日単体を返す）
 * - DELETE /api/v1/villages/{vid}/meetups/{mid}/candidate-dates/{cid}                — 候補日削除（204・本体なし）
 * - PUT    /api/v1/villages/{vid}/meetups/{mid}/candidate-dates/{cid}/vote           — 投票（204・本体なし / body は voteType のみ）
 * - GET    /api/v1/villages/{vid}/meetups/{mid}/votes                                — 投票集計
 */

interface ApiCall {
  path: string
  method: string
  body?: unknown
}

const calls: ApiCall[] = []
let nextResponse: unknown = null

const mockApi = vi.fn((path: string, opts?: { method?: string, body?: unknown }) => {
  calls.push({ path, method: opts?.method ?? 'GET', body: opts?.body })
  return Promise.resolve(nextResponse)
})

mockNuxtImport('useApi', () => () => mockApi)

const { useVillagePhase3Api } = await import('~/composables/village/useVillagePhase3Api')

const VILLAGE_ID = '01900000-0000-7000-8000-000000000001'
const MEETUP_ID = '01900000-0000-7000-8200-000000000001'
const CANDIDATE_DATE_ID = '01900000-0000-7000-8100-000000000001'

beforeEach(() => {
  calls.length = 0
  nextResponse = null
  mockApi.mockClear()
})

describe('useVillagePhase3Api — 寄合の BE 契約', () => {
  describe('castVote', () => {
    it('PUT /candidate-dates/{cid}/vote を呼ぶ（candidateDateId はパス変数）', async () => {
      const { castVote } = useVillagePhase3Api()
      // BE は 204 No Content。ofetch は本体なしを null で返す。
      nextResponse = null

      await castVote(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID, { voteType: 'AVAILABLE' })

      expect(calls).toHaveLength(1)
      expect(calls[0]!.method).toBe('PUT')
      expect(calls[0]!.path).toBe(
        `/api/v1/villages/${VILLAGE_ID}/meetups/${MEETUP_ID}/candidate-dates/${CANDIDATE_DATE_ID}/vote`,
      )
    })

    it('body は voteType のみ（BE の MeetupVoteRequest に comment は存在しない）', async () => {
      const { castVote } = useVillagePhase3Api()
      nextResponse = null

      await castVote(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID, { voteType: 'UNAVAILABLE' })

      expect(calls[0]!.body).toEqual({ voteType: 'UNAVAILABLE' })
    })

    it('204（本体なし）でも例外にならず undefined を返す', async () => {
      const { castVote } = useVillagePhase3Api()
      nextResponse = null

      await expect(
        castVote(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID, { voteType: 'MAYBE' }),
      ).resolves.toBeUndefined()
    })
  })

  describe('getVoteSummary', () => {
    it('GET /votes を呼ぶ（/votes/summary ではない）', async () => {
      const { getVoteSummary } = useVillagePhase3Api()
      nextResponse = {
        data: {
          meetupId: MEETUP_ID,
          candidates: [
            {
              candidateDateId: CANDIDATE_DATE_ID,
              candidateDate: '2026-06-01',
              availableCount: 2,
              maybeCount: 1,
              unavailableCount: 0,
            },
          ],
        },
      }

      const summary = await getVoteSummary(VILLAGE_ID, MEETUP_ID)

      expect(calls[0]!.method).toBe('GET')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/meetups/${MEETUP_ID}/votes`)
      // BE の集計は候補日別の available/maybe/unavailable 件数
      expect(summary.candidates[0]).toEqual({
        candidateDateId: CANDIDATE_DATE_ID,
        candidateDate: '2026-06-01',
        availableCount: 2,
        maybeCount: 1,
        unavailableCount: 0,
      })
    })
  })

  describe('createMeetup', () => {
    it('candidateDates を object 配列として送る（BE: List<MeetupCandidateDateInput>, #2357）', async () => {
      const { createMeetup } = useVillagePhase3Api()
      nextResponse = { data: { id: MEETUP_ID } }

      await createMeetup(VILLAGE_ID, {
        title: 'たまねぎ収穫祭の打ち合わせ',
        description: null,
        location: '集会所',
        candidateDates: [{ date: '2026-06-01' }, { date: '2026-06-02' }],
      })

      expect(calls[0]!.method).toBe('POST')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/meetups`)
      expect(calls[0]!.body).toEqual({
        title: 'たまねぎ収穫祭の打ち合わせ',
        description: null,
        location: '集会所',
        candidateDates: [{ date: '2026-06-01' }, { date: '2026-06-02' }],
      })
    })
  })

  describe('listMeetups', () => {
    it('status に PLANNING を渡せる（BE の enum は PLANNING/CONFIRMED/CANCELLED）', async () => {
      const { listMeetups } = useVillagePhase3Api()
      nextResponse = { data: [] }

      await listMeetups(VILLAGE_ID, { status: 'PLANNING', page: 0, size: 50 })

      expect(calls[0]!.path).toBe(
        `/api/v1/villages/${VILLAGE_ID}/meetups?status=PLANNING&page=0&size=50`,
      )
    })
  })

  describe('removeCandidateDate', () => {
    it('DELETE で 204（本体なし）を受けても res.data を触らず undefined を返す', async () => {
      const { removeCandidateDate } = useVillagePhase3Api()
      nextResponse = null

      await expect(
        removeCandidateDate(VILLAGE_ID, MEETUP_ID, CANDIDATE_DATE_ID),
      ).resolves.toBeUndefined()
      expect(calls[0]!.method).toBe('DELETE')
      expect(calls[0]!.path).toBe(
        `/api/v1/villages/${VILLAGE_ID}/meetups/${MEETUP_ID}/candidate-dates/${CANDIDATE_DATE_ID}`,
      )
    })
  })

  describe('addCandidateDate', () => {
    it('候補日単体（MeetupCandidateDateResponse）を返す', async () => {
      const { addCandidateDate } = useVillagePhase3Api()
      nextResponse = {
        data: {
          id: CANDIDATE_DATE_ID,
          meetupId: MEETUP_ID,
          candidateDate: '2026-06-03',
          sortOrder: 2,
        },
      }

      const added = await addCandidateDate(VILLAGE_ID, MEETUP_ID, {
        candidateDate: '2026-06-03',
        sortOrder: 2,
      })

      expect(calls[0]!.method).toBe('POST')
      expect(added.candidateDate).toBe('2026-06-03')
      expect(added.sortOrder).toBe(2)
    })
  })
})
