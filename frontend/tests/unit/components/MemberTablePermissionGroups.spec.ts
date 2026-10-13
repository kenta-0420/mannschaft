import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import MemberTable from '~/components/MemberTable.vue'

/**
 * F01.2.1 8-D — メンバー一覧の操作メニュー「権限グループ」（§3.2・§13）。
 *
 *   MTP-01 canAssignPermissionGroups のとき、各メンバーに「権限グループ」ボタンが出る
 *   MTP-02 押すと assignPermissionGroups をそのメンバーで発火する
 *   MTP-03 既定（false）では出さない（組織側・権限のない者の既存表示を壊さない）
 */

const apiMock = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
vi.mock('~/composables/returnStayPlan/useReturnStayPlanApi', () => ({
  useReturnStayPlanTeamApi: () => ({ fetchForMembers: vi.fn().mockResolvedValue([]) }),
}))

const members = [
  { userId: 11, displayName: '田中', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01T00:00:00Z' },
  { userId: 12, displayName: '佐藤', avatarUrl: null, roleName: 'DEPUTY_ADMIN', joinedAt: '2026-01-02T00:00:00Z' },
]

async function mountTable(extra: Record<string, unknown> = {}) {
  const w = await mountSuspended(MemberTable, {
    props: { scopeType: 'team', scopeId: 'team-1', canChangeRole: true, canRemove: true, ...extra },
  })
  await flushPromises()
  return w
}

describe('MemberTable — 権限グループ', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockResolvedValue({ data: members, meta: { page: 0, size: 20, total: 2, totalPages: 1 } })
  })

  it('MTP-01/02: ボタンが出て、押すとそのメンバーで発火', async () => {
    const w = await mountTable({ canAssignPermissionGroups: true })
    expect(w.find('[data-testid="member-permission-groups-11"]').exists()).toBe(true)
    expect(w.find('[data-testid="member-permission-groups-12"]').exists()).toBe(true)
    await w.find('[data-testid="member-permission-groups-12"]').trigger('click')
    expect(w.emitted('assignPermissionGroups')?.[0]).toEqual([
      expect.objectContaining({ userId: 12, displayName: '佐藤', roleName: 'DEPUTY_ADMIN' }),
    ])
  })

  it('MTP-03: 既定では出さない', async () => {
    const w = await mountTable()
    expect(w.find('[data-testid="member-permission-groups-11"]').exists()).toBe(false)
  })
})
