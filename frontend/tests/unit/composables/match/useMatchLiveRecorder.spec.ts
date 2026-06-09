import { describe, it, expect, vi } from 'vitest'
import { effectScope } from 'vue'
import { useMatchLiveRecorder, type RecordContext } from '~/composables/match/useMatchLiveRecorder'
import type { MatchEventRequest, MatchEventResponse } from '~/types/match'

/**
 * F08.10 useMatchLiveRecorder ユニットテスト（連鎖・交代・undo・409・sports/01_soccer.md §8.1/8.2）。
 *
 * 観点:
 *   REC-001: 単発記録（カード）→ events 先頭挿入＋undo 可能
 *   REC-002: 得点起点の連鎖（GOAL+ASSIST が linked_event_id で結ばれる）
 *   REC-003: アシスト起点の連鎖（逆順でも結ばれる）
 *   REC-004: 交代（SUB_OUT+SUB_IN）が原子的に記録され、undo で両方取り消す
 *   REC-005: undo は直前 1 操作のみ取り消す
 *   REC-006: 409 競合は reload→1 回再試行する（フォームは閉じない）
 *   REC-007: 連鎖の片側削除で相手側 linkedEventId が外れる（単独化）
 *   REC-008: 交代の SUB_IN 失敗時は SUB_OUT を巻き戻す（中途半端な OUT を残さない）
 */

const CTX: RecordContext = { period: 'FIRST_HALF', minute: 12, teamSide: 'HOME' }

/** sender のスタブ: 呼ばれた body を記録し連番 id を返す。 */
function makeSender() {
  let n = 0
  const sent: MatchEventRequest[] = []
  const sender = vi.fn(async (body: MatchEventRequest): Promise<MatchEventResponse> => {
    sent.push(body)
    n += 1
    return { ...body, id: `ev-${n}`, createdAt: `2026-06-09T00:00:0${n}Z` } as MatchEventResponse
  })
  return { sender, sent }
}

function build(overrides: Parameters<typeof useMatchLiveRecorder>[0] extends infer T ? Partial<T> : never = {}) {
  const { sender } = makeSender()
  const deleted: string[] = []
  const deleter = vi.fn(async (id: string) => { deleted.push(id) })
  const reload = vi.fn(async (): Promise<MatchEventResponse[]> => [])
  const recorder = useMatchLiveRecorder({ sender, deleter, reload, ...overrides })
  return { recorder, sender, deleter, reload, deleted }
}

describe('useMatchLiveRecorder', () => {
  it('REC-001: 単発記録（カード）で events 先頭挿入＋undo 可能', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder } = build()
      const ev = await recorder.recordSingle('YELLOW_CARD', CTX, { playerName: '7' }, { cardReasonCode: 'C2' })
      expect(ev.eventType).toBe('YELLOW_CARD')
      expect(ev.cardReasonCode).toBe('C2')
      expect(recorder.events.value[0]?.id).toBe(ev.id)
      expect(recorder.canUndo.value).toBe(true)
    })
    scope.stop()
  })

  it('REC-002: 得点起点の連鎖で GOAL/ASSIST が linked_event_id で結ばれる', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder } = build()
      const { goal, assist } = await recorder.recordGoalWithAssist(
        CTX, { playerName: '9' }, { playerName: '7' },
      )
      expect(goal.eventType).toBe('GOAL')
      expect(assist?.eventType).toBe('ASSIST')
      // ASSIST は goal を指す（後発側が linkedEventId を持つ）。
      expect(assist?.linkedEventId).toBe(goal.id)
      // GOAL 側にも逆参照が張られる（双方向・ローカル反映）。
      const goalRow = recorder.events.value.find((e) => e.id === goal.id)
      expect(goalRow?.linkedEventId).toBe(assist?.id)
    })
    scope.stop()
  })

  it('REC-002b: 得点のみ（アシスト無し）でも記録できる', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder, sender } = build()
      const { goal, assist } = await recorder.recordGoalWithAssist(CTX, { playerName: '9' }, null, 'PENALTY_GOAL')
      expect(goal.eventType).toBe('PENALTY_GOAL')
      expect(assist).toBeNull()
      expect(sender).toHaveBeenCalledTimes(1)
    })
    scope.stop()
  })

  it('REC-003: アシスト起点の連鎖（逆順でも結ばれる）', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder } = build()
      const { assist, goal } = await recorder.recordAssistThenGoal(
        CTX, { playerName: '7' }, { playerName: '9' },
      )
      expect(goal?.linkedEventId).toBe(assist.id)
      const assistRow = recorder.events.value.find((e) => e.id === assist.id)
      expect(assistRow?.linkedEventId).toBe(goal?.id)
    })
    scope.stop()
  })

  it('REC-004: 交代が原子的に記録され、undo で両方取り消す', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder, sender, deleted } = build()
      const { subOut, subIn } = await recorder.recordSubstitution(CTX, { playerName: 'OUT' }, { playerName: 'IN' })
      expect(sender).toHaveBeenCalledTimes(2)
      expect(subOut.eventType).toBe('SUB_OUT')
      expect(subIn.eventType).toBe('SUB_IN')
      expect(recorder.events.value.length).toBe(2)
      await recorder.undoLast()
      expect(deleted.sort()).toEqual([subIn.id, subOut.id].sort())
      expect(recorder.events.value.length).toBe(0)
    })
    scope.stop()
  })

  it('REC-005: undo は直前 1 操作のみ取り消す', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder } = build()
      await recorder.recordSingle('SAVE', CTX, { playerName: 'GK' })
      const second = await recorder.recordSingle('INJURY', CTX, { playerName: '5' })
      await recorder.undoLast()
      // 2 件目だけ消え、1 件目は残る。
      expect(recorder.events.value.some((e) => e.id === second.id)).toBe(false)
      expect(recorder.events.value.length).toBe(1)
      expect(recorder.canUndo.value).toBe(true)
    })
    scope.stop()
  })

  it('REC-006: 409 競合は reload→1 回再試行する', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      let calls = 0
      const sender = vi.fn(async (body: MatchEventRequest): Promise<MatchEventResponse> => {
        calls += 1
        if (calls === 1) throw { statusCode: 409 }
        return { ...body, id: 'ev-retry' } as MatchEventResponse
      })
      const reload = vi.fn(async (): Promise<MatchEventResponse[]> => [])
      const onConflict = vi.fn()
      const recorder = useMatchLiveRecorder({ sender, deleter: vi.fn(), reload, onConflict })
      const ev = await recorder.recordSingle('GOAL', CTX, { playerName: '9' })
      expect(onConflict).toHaveBeenCalledTimes(1)
      expect(reload).toHaveBeenCalledTimes(1)
      expect(sender).toHaveBeenCalledTimes(2)
      expect(ev.id).toBe('ev-retry')
    })
    scope.stop()
  })

  it('REC-007: 連鎖の片側削除で相手側 linkedEventId が外れる', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const { recorder } = build()
      const { goal, assist } = await recorder.recordGoalWithAssist(CTX, { playerName: '9' }, { playerName: '7' })
      // ASSIST を削除 → GOAL の linkedEventId が外れて単独化。
      await recorder.deleteEvent(assist!.id!)
      const goalRow = recorder.events.value.find((e) => e.id === goal.id)
      expect(goalRow).toBeDefined()
      expect(goalRow?.linkedEventId).toBeUndefined()
    })
    scope.stop()
  })

  it('REC-008: 交代の SUB_IN 失敗時は SUB_OUT を巻き戻す', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      let calls = 0
      const sender = vi.fn(async (body: MatchEventRequest): Promise<MatchEventResponse> => {
        calls += 1
        if (calls === 2) throw new Error('SUB_IN failed')
        return { ...body, id: `ev-${calls}` } as MatchEventResponse
      })
      const deleted: string[] = []
      const deleter = vi.fn(async (id: string) => { deleted.push(id) })
      const recorder = useMatchLiveRecorder({ sender, deleter, reload: vi.fn(async () => []) })
      await expect(
        recorder.recordSubstitution(CTX, { playerName: 'OUT' }, { playerName: 'IN' }),
      ).rejects.toThrow('SUB_IN failed')
      // SUB_OUT（ev-1）が DELETE で巻き戻され、events に残らない。
      expect(deleted).toContain('ev-1')
      expect(recorder.events.value.length).toBe(0)
      expect(recorder.canUndo.value).toBe(false)
    })
    scope.stop()
  })
})
