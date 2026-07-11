import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import LineManager from '~/components/reservation/LineManager.vue'

/**
 * LineManager.vue ユニットテスト — 番人（予約v2 W2-3-FE 呼称UI隊）
 *
 * 観点（受け入れ条件対応）:
 *   AC-N1: 見出し・追加ボタンに resourceName（呼称）が動的に差し込まれる
 *   AC-N5: 並び順編集（上下移動ボタン）で隣接2件の displayOrder を入れ替えて PATCH する
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockGetReservationSettings = vi.fn()
const mockGetLines = vi.fn()
const mockUpdateLine = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getReservationSettings: mockGetReservationSettings,
    getLines: mockGetLines,
    updateLine: mockUpdateLine,
    createLine: vi.fn(),
    deleteLine: vi.fn(),
  }),
}))

mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true),
  isAdminOrDeputy: ref(true),
  isMember: ref(true),
  roleName: ref('ADMIN'),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))

mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn(), close: vi.fn() }))

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

const lineA = { id: 1, meta: { name: 'ライン A', isActive: true, displayOrder: 1 } }
const lineB = { id: 2, meta: { name: 'ライン B', isActive: true, displayOrder: 2 } }

beforeEach(() => {
  mockGetReservationSettings.mockReset()
  mockGetLines.mockReset()
  mockUpdateLine.mockReset()

  mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'SEAT' } })
  mockGetLines.mockResolvedValue({ data: [lineA, lineB] })
  mockUpdateLine.mockResolvedValue({ data: {} })
})

describe('LineManager.vue', () => {
  it('AC-N1: resourceNameType=SEAT のとき見出し・追加ボタンに「Seat」が差し込まれる', async () => {
    const wrapper = await mountSuspended(LineManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    expect(wrapper.text()).toContain('Seat Management')
    expect(wrapper.text()).toContain('Add Seat')
  })

  it('AC-N5: 1件目の「下へ」を押すと displayOrder が入れ替わり updateLine が2回呼ばれる', async () => {
    const wrapper = await mountSuspended(LineManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const moveDownButtons = wrapper.findAll('[data-testid="line-move-down"]')
    expect(moveDownButtons.length).toBe(2)
    // 再読込後に同じ並びで再取得されるため、入れ替え後も 2件のまま返す
    mockGetLines.mockResolvedValue({ data: [lineB, lineA] })

    await moveDownButtons[0]!.trigger('click')
    await flush()

    expect(mockUpdateLine).toHaveBeenCalledTimes(2)
    const calls = mockUpdateLine.mock.calls as Array<[string, number, Record<string, unknown>]>
    const lineACall = calls.find(c => c[1] === 1)
    const lineBCall = calls.find(c => c[1] === 2)
    expect(lineACall?.[2]).toEqual({ displayOrder: 2 })
    expect(lineBCall?.[2]).toEqual({ displayOrder: 1 })
  })

  it('AC-N5: 最後尾の「下へ」ボタンは disabled', async () => {
    const wrapper = await mountSuspended(LineManager, {
      props: { teamId: 'team-slug' },
    })
    await flush()

    const moveDownButtons = wrapper.findAll('[data-testid="line-move-down"]')
    expect(moveDownButtons[1]!.attributes('disabled')).toBeDefined()

    const moveUpButtons = wrapper.findAll('[data-testid="line-move-up"]')
    expect(moveUpButtons[0]!.attributes('disabled')).toBeDefined()
  })
})
