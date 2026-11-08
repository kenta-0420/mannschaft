import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, nextTick, reactive, ref } from 'vue'
import ShiftsPage from './shifts.vue'

const route = reactive({ params: { slug: 'dev-team' }, query: {} as Record<string, unknown> })
const loadPermissions = vi.fn(async () => ({ ok: true }))

mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useTeamShellContext', () => () => ({ team: ref({ numericId: 10 }) }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true), isAdminOrDeputy: ref(true), roleName: ref('ADMIN'), loadPermissions,
}))
mockNuxtImport('useShiftApi', () => () => ({ createSchedule: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

// APIや一覧の挙動は既存試練が担当する。本試練はページの公開query→タブ選択を検査する。
const TabsProbe = defineComponent({
  props: { value: Number },
  template: '<div data-testid="selected-tab" :data-value="value"><slot /></div>',
})

async function mountPage() {
  return mountSuspended(ShiftsPage, {
    global: { stubs: {
      Tabs: TabsProbe, TabList: true, Tab: true, TabPanels: true, TabPanel: true,
      ShiftScheduleList: true, ShiftSwapList: true, ShiftPositionManager: true,
      ShiftRequestDialog: true, Dialog: true,
    } },
  })
}

beforeEach(() => {
  route.query = {}
  loadPermissions.mockClear()
})

describe('シフト依頼のダッシュボード遷移先', () => {
  it('tab=swapsで既存シフト交換タブを初期選択する', async () => {
    route.query = { tab: 'swaps' }
    const wrapper = await mountPage()
    expect(wrapper.get('[data-testid="selected-tab"]').attributes('data-value')).toBe('1')
  })

  it('同じページへのquery変更もタブへ反映する', async () => {
    const wrapper = await mountPage()
    expect(wrapper.get('[data-testid="selected-tab"]').attributes('data-value')).toBe('0')
    route.query = { tab: 'swaps' }
    await nextTick()
    expect(wrapper.get('[data-testid="selected-tab"]').attributes('data-value')).toBe('1')
    route.query = {}
    await nextTick()
    expect(wrapper.get('[data-testid="selected-tab"]').attributes('data-value')).toBe('0')
  })

  it.each(['unknown', ['swaps', 'other']])('未知値や複数値は既定のシフト表を選ぶ: %s', async tab => {
    route.query = { tab }
    const wrapper = await mountPage()
    expect(wrapper.get('[data-testid="selected-tab"]').attributes('data-value')).toBe('0')
  })
})
