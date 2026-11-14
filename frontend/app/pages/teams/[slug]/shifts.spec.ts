import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed, defineComponent, nextTick, reactive, ref } from 'vue'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import ShiftsPage from './shifts.vue'

const route = reactive({ params: { slug: 'dev-team' }, query: {} as Record<string, unknown> })
const loadPermissions = vi.fn(async () => ({ ok: true }))
const roleName = ref<string | null>('ADMIN')

mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useTeamShellContext', () => () => ({ team: ref({ numericId: 10 }) }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  // useRoleAccessの既存判定と同じ結果境界。実BE認可の試験ではない。
  isAdmin: computed(() => roleName.value === 'ADMIN' || roleName.value === 'SYSTEM_ADMIN'),
  isAdminOrDeputy: computed(() => ['ADMIN', 'SYSTEM_ADMIN', 'DEPUTY_ADMIN'].includes(roleName.value ?? '')),
  roleName, loadPermissions,
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
  roleName.value = 'ADMIN'
  loadPermissions.mockReset().mockResolvedValue({ ok: true })
})

// CMP-260910-1603: Tabs/Tab/TabPanelは実PrimeVueを通し、別タブの業務子だけを除外する。
async function mountPositionPage() {
  const wrapper = await mountSuspended(ShiftsPage, {
    global: {
      components: { Tabs, TabList, Tab, TabPanels, TabPanel },
      stubs: {
        ShiftScheduleList: true, ShiftSwapList: true, ShiftPositionManager: true,
        ShiftRequestForm: true, Dialog: true,
      },
    },
  })
  await flushPromises()
  await nextTick()
  expect(wrapper.findAllComponents(Tab).filter(tab => [0, 1].includes(Number(tab.props('value'))))).toHaveLength(2)
  return wrapper
}

type PositionPage = Awaited<ReturnType<typeof mountPositionPage>>
const positionHeaders = (page: PositionPage) => page.findAllComponents(Tab).filter(tab => tab.props('value') === 2)
const positionPanels = (page: PositionPage) => page.findAllComponents(TabPanel).filter(panel => panel.props('value') === 2)

describe('ポジション管理タブの見出しと本文の権限整合', () => {
  it('AC1 ADMINは既存ラベルのポジション管理を選んで本文へ到達できる', async () => {
    const page = await mountPositionPage()
    expect(positionHeaders(page)).toHaveLength(1)
    expect(positionHeaders(page)[0]!.text()).toBe('ポジション管理')
    await positionHeaders(page)[0]!.trigger('click')
    await nextTick()
    expect(page.getComponent(Tabs).props('value')).toBe(2)
    expect(positionPanels(page)).toHaveLength(1)
    expect(page.find('shift-position-manager-stub').exists()).toBe(true)
  })

  it('AC1 SYSTEM_ADMINは既存isAdminに従って見出しと本文を保持する', async () => {
    roleName.value = 'SYSTEM_ADMIN'
    const page = await mountPositionPage()
    expect(positionHeaders(page)).toHaveLength(1)
    expect(positionPanels(page)).toHaveLength(1)
  })

  it('AC2 MEMBERは本文だけでなく空白へ進むポジション管理見出しも表示しない', async () => {
    roleName.value = 'MEMBER'
    const page = await mountPositionPage()
    expect(positionPanels(page)).toHaveLength(0)
    expect(positionHeaders(page)).toHaveLength(0)
  })

  it('AC2 DEPUTY_ADMINは本文だけでなくポジション管理見出しも表示しない', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    const page = await mountPositionPage()
    expect(positionPanels(page)).toHaveLength(0)
    expect(positionHeaders(page)).toHaveLength(0)
  })

  it('AC2 ロール未解決nullでは本文もポジション管理見出しも表示しない', async () => {
    roleName.value = null
    const page = await mountPositionPage()
    expect(positionPanels(page)).toHaveLength(0)
    expect(positionHeaders(page)).toHaveLength(0)
  })

  it('AC3 ADMINからMEMBERへ変わると既存ページの本文と見出しを除く', async () => {
    const page = await mountPositionPage()
    expect(positionPanels(page)).toHaveLength(1)
    roleName.value = 'MEMBER'
    await nextTick()
    expect(positionPanels(page)).toHaveLength(0)
    expect(positionHeaders(page)).toHaveLength(0)
  })

  it('AC3 非同期ロール解決でADMINになると同じページに見出しと本文を表示する', async () => {
    roleName.value = null
    let resolve!: () => void
    const pending = new Promise<void>(done => { resolve = done })
    loadPermissions.mockImplementationOnce(async () => {
      await pending
      roleName.value = 'ADMIN'
      return { ok: true }
    })
    const page = await mountPositionPage()
    expect(positionPanels(page)).toHaveLength(0)
    expect(loadPermissions).toHaveBeenCalledTimes(1)
    resolve()
    await flushPromises()
    await nextTick()
    expect(positionHeaders(page)).toHaveLength(1)
    expect(positionPanels(page)).toHaveLength(1)
  })
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
