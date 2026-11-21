import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { RollCallCandidate } from '~/types/care'
import { useRollCall } from '~/composables/useRollCall'

/**
 * 点呼候補・履歴の取り直しで古い応答が新しい表示を上書きしないことの試練（CMP-260925-0903）。
 *
 * 実物の useRollCall を通し、モックは API 境界（useRollCallApi）だけにする。
 */

const { getCandidates, getSessions } = vi.hoisted(() => ({ getCandidates: vi.fn(), getSessions: vi.fn() }))
mockNuxtImport('useRollCallApi', () => () => ({ getCandidates, getSessions }))

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function candidate(userId: number): RollCallCandidate {
  return { userId, displayName: `user-${userId}` } as RollCallCandidate
}

async function mountRollCall() {
  let handle: ReturnType<typeof useRollCall> | null = null
  const teamId = ref('team-a')
  const eventId = ref(1)
  const wrapper = await mountSuspended(defineComponent({
    setup() {
      handle = useRollCall(teamId, eventId)
      return () => h('div')
    },
  }))
  if (!handle) throw new Error('harness not initialised')
  return { rc: handle as ReturnType<typeof useRollCall>, wrapper }
}

describe('useRollCall 最後の要求の応答だけを反映する（CMP-260925-0903）', () => {
  beforeEach(() => {
    getCandidates.mockReset()
    getSessions.mockReset()
  })

  it('遅い応答 A が速い応答 B より後に届いても、候補は B のまま上書きされない', async () => {
    const a = deferred<RollCallCandidate[]>()
    const b = deferred<RollCallCandidate[]>()
    getCandidates.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { rc } = await mountRollCall()

    const first = rc.loadCandidates()
    const second = rc.loadCandidates()
    b.resolve([candidate(2)])
    await second
    a.resolve([candidate(1)])
    await first

    expect(rc.candidates.value.map(c => c.userId)).toEqual([2])
    expect(rc.loading.value).toBe(false)
  })

  it('古い要求の失敗が、最新の成功の表示と error を汚さない', async () => {
    const a = deferred<RollCallCandidate[]>()
    const b = deferred<RollCallCandidate[]>()
    getCandidates.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { rc } = await mountRollCall()

    const first = rc.loadCandidates()
    const second = rc.loadCandidates()
    b.resolve([candidate(2)])
    await second
    a.reject(new Error('stale failure'))
    await first

    expect(rc.candidates.value.map(c => c.userId)).toEqual([2])
    expect(rc.error.value).toBeNull()
  })

  it('最新の要求の失敗は従来どおり error に載せて再送出する', async () => {
    getCandidates.mockRejectedValueOnce(new Error('latest failure'))
    const { rc } = await mountRollCall()

    await expect(rc.loadCandidates()).rejects.toThrow('latest failure')
    expect(rc.error.value).toBe('latest failure')
    expect(rc.loading.value).toBe(false)
  })

  it('履歴（sessionIds）も逆順応答で上書きされず、候補取得とは干渉しない', async () => {
    const a = deferred<string[]>()
    const b = deferred<string[]>()
    getSessions.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    getCandidates.mockResolvedValueOnce([candidate(9)])
    const { rc } = await mountRollCall()

    const first = rc.loadSessions()
    const second = rc.loadSessions()
    await rc.loadCandidates() // 別種の取得は履歴の世代を進めない
    b.resolve(['new'])
    await second
    a.resolve(['old'])
    await first

    expect(rc.sessionIds.value).toEqual(['new'])
    expect(rc.candidates.value.map(c => c.userId)).toEqual([9])
    expect(rc.loading.value).toBe(false)
  })

  it('アンマウント後に届いた応答で状態を更新しない', async () => {
    const a = deferred<RollCallCandidate[]>()
    getCandidates.mockReturnValueOnce(a.promise)
    const { rc, wrapper } = await mountRollCall()

    const pending = rc.loadCandidates()
    wrapper.unmount()
    a.resolve([candidate(1)])
    await pending

    expect(rc.candidates.value).toEqual([])
  })
})
