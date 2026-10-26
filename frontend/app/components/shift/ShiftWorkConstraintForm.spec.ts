import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftWorkConstraintForm from './ShiftWorkConstraintForm.vue'
import type { MemberWorkConstraintResponse } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: 勤務制約フォームの AC。
 *   AC-N4 削除は確認ダイアログで承認した場合だけ DELETE が飛ぶ。キャンセルでは飛ばない
 *   AC-C1 削除・保存ボタンに common. の生キーが出ない
 */
const mocks = vi.hoisted(() => ({
  deleteConstraint: vi.fn(),
  deleteTeamDefault: vi.fn(),
  require: vi.fn(),
}))

mockNuxtImport('useMemberWorkConstraintApi', () => () => ({
  upsertConstraint: vi.fn(),
  upsertTeamDefault: vi.fn(),
  deleteConstraint: mocks.deleteConstraint,
  deleteTeamDefault: mocks.deleteTeamDefault,
}))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.require }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

const existing: MemberWorkConstraintResponse = {
  id: 1, teamId: 12, userId: 5,
  maxMonthlyHours: null, maxMonthlyDays: 20, maxConsecutiveDays: null,
  maxNightShiftsPerMonth: null, minRestHoursBetweenShifts: null, note: null,
}

async function mountForm() {
  return mountSuspended(ShiftWorkConstraintForm, {
    props: { constraint: existing, teamId: '12', userId: 5 },
  })
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.deleteConstraint.mockResolvedValue(undefined)
})

describe('ShiftWorkConstraintForm 削除の確認（AC-N4）', () => {
  it('AC-N4: 削除ボタンは確認ダイアログを開くだけで、承認するまで DELETE を送らない', async () => {
    const wrapper = await mountForm()
    await wrapper.find('[data-testid="work-constraint-delete"]').trigger('click')

    expect(mocks.require).toHaveBeenCalledTimes(1)
    expect(mocks.deleteConstraint).not.toHaveBeenCalled()
  })

  it('AC-N4: 確認で承認すると DELETE を1回送り、deleted を発火する', async () => {
    const wrapper = await mountForm()
    await wrapper.find('[data-testid="work-constraint-delete"]').trigger('click')
    const options = mocks.require.mock.calls[0]![0] as { accept: () => Promise<void> }

    await options.accept()
    await flushPromises()

    expect(mocks.deleteConstraint).toHaveBeenCalledTimes(1)
    expect(mocks.deleteConstraint).toHaveBeenCalledWith('12', 5)
    expect(wrapper.emitted('deleted')).toHaveLength(1)
  })

  it('AC-N4: キャンセル（reject）では DELETE を送らない', async () => {
    const wrapper = await mountForm()
    await wrapper.find('[data-testid="work-constraint-delete"]').trigger('click')
    const options = mocks.require.mock.calls[0]![0] as { reject?: () => void }

    options.reject?.()
    await flushPromises()

    expect(mocks.deleteConstraint).not.toHaveBeenCalled()
    expect(wrapper.emitted('deleted')).toBeUndefined()
  })
})

describe('ShiftWorkConstraintForm ラベル（AC-C1）', () => {
  it('AC-C1: 削除ボタンのラベルが button.delete で解決され、common. の生キーが出ない', async () => {
    const wrapper = await mountForm()
    expect(wrapper.find('[data-testid="work-constraint-delete"]').text()).toBe('Delete')
    expect(wrapper.text()).not.toMatch(/\bcommon\./)
  })
})
