import { describe, expect, it } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { ShiftSlotResponse } from '~/types/shift'
import ShiftBoardMatrix from './ShiftBoardMatrix.vue'
import ShiftBoardCell from './ShiftBoardCell.vue'
import ShiftMemberChip from './ShiftMemberChip.vue'

mockNuxtImport('useDatetime', () => () => ({ formatDate: (value: string) => value }))

const sourceSlotType = 'application/x-mannschaft-shift-slot-id'

function slot(id: number, assignedUserIds: number[]): ShiftSlotResponse {
  return {
    id, scheduleId: 1, version: id,
    time: { slotDate: '2026-10-10', startTime: '10:00', endTime: '11:00', endsNextDay: false },
    position: { positionId: 1, positionName: '受付', requiredCount: 1 },
    assignedUserIds, assignmentMasked: false, note: null,
  }
}

function transfer(userId = '', fromSlotId?: string) {
  const values = new Map<string, string>([['text/plain', userId]])
  if (fromSlotId !== undefined) values.set(sourceSlotType, fromSlotId)
  return {
    effectAllowed: 'none',
    get types() { return [...values.keys()] },
    getData: (type: string) => values.get(type) ?? '',
    setData: (type: string, value: string) => { values.set(type, value) },
  }
}

async function mountBoard() {
  return mountSuspended(ShiftBoardMatrix, {
    props: {
      slots: [slot(1, [4]), slot(2, [])],
      positions: [{ id: 1, teamId: 1, name: '受付', color: null, displayOrder: 0, isActive: true, createdAt: '' }],
      localAssignments: {},
      memberMap: { 4: { userId: 4, displayName: '担当者', avatarUrl: null } },
      pendingRun: null,
    },
    global: {
      components: { ShiftBoardCell, ShiftMemberChip },
      stubs: { ShiftAutoAssignResultBanner: true },
      mocks: { $t: (key: string) => key },
    },
  })
}

describe('ShiftBoardMatrix 枠間ドラッグ', () => {
  it('割当済みchipから移動元と利用者を渡し、別枠への移動を通知する', async () => {
    const wrapper = await mountBoard()
    const dataTransfer = transfer()
    const chip = wrapper.findComponent(ShiftMemberChip)
    expect(chip.attributes('draggable')).toBe('true')
    await chip.trigger('dragstart', { dataTransfer })
    expect(dataTransfer.getData('text/plain')).toBe('4')
    expect(dataTransfer.getData(sourceSlotType)).toBe('1')
    expect(dataTransfer.effectAllowed).toBe('move')
    await wrapper.findAllComponents(ShiftBoardCell)[1]!.trigger('drop', { dataTransfer })
    expect(wrapper.emitted('dropUser')).toEqual([[{ fromSlotId: 1, toSlotId: 2, userId: 4 }]])
  })

  it('プールからの追加は移動元をnullに保つ', async () => {
    const wrapper = await mountBoard()
    await wrapper.findAllComponents(ShiftBoardCell)[1]!.trigger('drop', { dataTransfer: transfer('4') })
    expect(wrapper.emitted('dropUser')).toEqual([[{ fromSlotId: null, toSlotId: 2, userId: 4 }]])
  })

  it('同じ枠へのdropは操作を通知しない', async () => {
    const wrapper = await mountBoard()
    await wrapper.findAllComponents(ShiftBoardCell)[0]!.trigger('drop', { dataTransfer: transfer('4', '1') })
    expect(wrapper.emitted('dropUser')).toBeUndefined()
  })

  it.each(['', '0', '-1', '1.5', 'Infinity', '9007199254740992', 'abc'])('不正な利用者ID %s は操作を通知しない', async (userId) => {
    const wrapper = await mountBoard()
    await wrapper.findAllComponents(ShiftBoardCell)[1]!.trigger('drop', { dataTransfer: transfer(userId) })
    expect(wrapper.emitted('dropUser')).toBeUndefined()
  })

  it.each(['', '0', '-1', '1.5', 'Infinity', '9007199254740992', 'abc'])('指定された不正な移動元 %s をプール追加へ変換しない', async (fromSlotId) => {
    const wrapper = await mountBoard()
    await wrapper.findAllComponents(ShiftBoardCell)[1]!.trigger('drop', { dataTransfer: transfer('4', fromSlotId) })
    expect(wrapper.emitted('dropUser')).toBeUndefined()
  })
})
