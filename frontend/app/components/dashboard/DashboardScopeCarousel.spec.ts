import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { mockComponent, mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { reactive, nextTick } from 'vue'
import type { ActivePanel } from '~/stores/useScopeDashboardStore'
import Carousel from './DashboardScopeCarousel.vue'

const { state, mounts } = vi.hoisted(() => ({
  state: { store: {
    activePanel: 'PERSONAL' as ActivePanel,
    teamTabPage: 0,
    orgTabPage: 0,
    setActivePanel(panel: ActivePanel) { this.activePanel = panel },
    loadTabs: vi.fn().mockResolvedValue(undefined),
  } },
  mounts: { PERSONAL: vi.fn(), TEAM: vi.fn(), ORGANIZATION: vi.fn() },
}))
mockNuxtImport('useScopeDashboardStore', () => () => state.store)
mockNuxtImport('useAuthStore', () => () => ({ isAuthenticated: true }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// 親の生成境界を観測する。実panelのmountedで始まる取得をstubのmount回数で保護する。
mockComponent('DashboardPersonalPanel', {
  name: 'DashboardPersonalPanel',
  props: { active: Boolean },
  data: () => ({ draft: '' }),
  mounted: () => mounts.PERSONAL(),
  template: '<div data-testid="body-PERSONAL"><input v-model="draft" /></div>',
})
mockComponent('DashboardTeamPanel', {
  name: 'DashboardTeamPanel',
  data: () => ({ draft: '' }),
  mounted: () => mounts.TEAM(),
  template: '<div data-testid="body-TEAM"><input v-model="draft" /></div>',
})
mockComponent('DashboardOrgPanel', {
  name: 'DashboardOrgPanel',
  data: () => ({ draft: '' }),
  mounted: () => mounts.ORGANIZATION(),
  template: '<div data-testid="body-ORGANIZATION"><input v-model="draft" /></div>',
})
mockComponent('DashboardStorageSummary', { template: '<div />' })
mockComponent('PageLoading', { template: '<div data-testid="unvisited-loading" />' })

const panels: ActivePanel[] = ['PERSONAL', 'TEAM', 'ORGANIZATION']
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks()
  state.store = reactive({
    activePanel: 'PERSONAL' as ActivePanel,
    teamTabPage: 0,
    orgTabPage: 0,
    setActivePanel(panel: ActivePanel) { this.activePanel = panel },
    loadTabs: vi.fn().mockResolvedValue(undefined),
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
  vi.restoreAllMocks()
})

async function render(initial: ActivePanel = 'PERSONAL', reducedMotion = false) {
  state.store.activePanel = initial
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: reducedMotion,
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(() => true),
  })
  wrapper = await mountSuspended(Carousel, {
    global: {
      mocks: { $t: (key: string) => key },
      stubs: { Button: { template: '<button type="button"><slot /></button>' } },
    },
  })
  await vi.waitFor(() => expect(wrapper!.find(`[data-testid="body-${initial}"]`).exists()).toBe(true))
  return wrapper
}

function expectMountedOnce(visited: ActivePanel[]) {
  for (const panel of panels) expect(mounts[panel]).toHaveBeenCalledTimes(visited.includes(panel) ? 1 : 0)
}
async function select(view: VueWrapper, panel: ActivePanel) {
  await view.get(`[data-testid="scope-segment-${panel}"]`).trigger('click')
  await vi.waitFor(() => expect(view.find(`[data-testid="body-${panel}"]`).exists()).toBe(true))
}
function fakeAnimationClock() {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
}
async function beginDrag(view: VueWrapper, dx: number, dy = 0) {
  const section = view.get('[role="tabpanel"]').element
  Object.defineProperty(section.parentElement!, 'clientWidth', { configurable: true, value: 1000 })
  const viewport = view.get('.overflow-hidden')
  await viewport.trigger('touchstart', { touches: [{ clientX: 800, clientY: 100 }] })
  await viewport.trigger('touchmove', { touches: [{ clientX: 800 + dx, clientY: 100 + dy }] })
  return viewport
}

describe('スコープbodyの初回選択と常設カルーセル', () => {
  it.each(panels)('復元された%sだけを生成し、未訪問bodyのmount/取得を始めない', async (initial) => {
    const view = await render(initial)
    expect(view.findAll('section[role="tabpanel"]')).toHaveLength(3)
    expect(view.findAll('[role="tab"]')).toHaveLength(3)
    expect(view.get(`#scope-panel-${initial}`).attributes('aria-hidden')).toBe('false')
    expectMountedOnce([initial])
    for (const other of panels.filter(panel => panel !== initial)) {
      expect(view.find(`[data-testid="body-${other}"]`).exists()).toBe(false)
      expect(view.get(`#scope-panel-${other}`).find('[data-testid="unvisited-loading"]').exists()).toBe(true)
    }
    expect(state.store.loadTabs).toHaveBeenCalledWith('TEAM', 0)
    expect(state.store.loadTabs).toHaveBeenCalledWith('ORGANIZATION', 0)
  })

  it('クリックで初回に一度生成し、並べ替え後に戻ってもinstanceと下書きを保持する', async () => {
    const view = await render()
    await select(view, 'TEAM')
    const body = view.get('[data-testid="body-TEAM"]')
    await body.get('input').setValue('入力途中')
    const element = body.element
    await select(view, 'ORGANIZATION')
    await select(view, 'TEAM')
    expect(view.get('[data-testid="body-TEAM"]').element).toBe(element)
    expect((view.get('[data-testid="body-TEAM"] input').element as HTMLInputElement).value).toBe('入力途中')
    expectMountedOnce(panels)
    expect(view.findAll('section[role="tabpanel"]')).toHaveLength(3)
    expect(view.findComponent({ name: 'DashboardPersonalPanel' }).props('active')).toBe(false)
    await select(view, 'PERSONAL')
    expect(view.findComponent({ name: 'DashboardPersonalPanel' }).props('active')).toBe(true)
  })

  it('矢印連打の選択先を即生成し、queue消化後も再mountしない', async () => {
    const view = await render()
    fakeAnimationClock()
    for (const panel of ['TEAM', 'ORGANIZATION', 'PERSONAL'] as const) {
      await view.get('[data-testid="scope-next"]').trigger('click')
      await flushPromises()
      expect(state.store.activePanel).toBe(panel)
      expect(view.find(`[data-testid="body-${panel}"]`).exists()).toBe(true)
    }
    await vi.advanceTimersByTimeAsync(2000)
    expectMountedOnce(panels)
    expect(view.get('#scope-panel-PERSONAL').attributes('aria-hidden')).toBe('false')
    expect(view.findAll('section[role="tabpanel"]')).toHaveLength(3)
  })

  it('外部store変更でも未訪問bodyを生成し、keyboardで戻っても保持する', async () => {
    const view = await render()
    state.store.setActivePanel('ORGANIZATION')
    await nextTick()
    await vi.waitFor(() => expect(view.find('[data-testid="body-ORGANIZATION"]').exists()).toBe(true))
    expectMountedOnce(['PERSONAL', 'ORGANIZATION'])
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    await nextTick()
    expect(state.store.activePanel).toBe('PERSONAL')
    expectMountedOnce(['PERSONAL', 'ORGANIZATION'])
  })

  it('reduced-motionでも選択時に生成し、dotで戻った状態を保持する', async () => {
    const view = await render('TEAM', true)
    const team = view.get('[data-testid="body-TEAM"]').element
    await select(view, 'ORGANIZATION')
    const dots = view.findAll('button[aria-current]')
    expect(dots).toHaveLength(1)
    await view.get('button[aria-label="scopeDashboard.tabs.team"]').trigger('click')
    await flushPromises()
    expect(state.store.activePanel).toBe('TEAM')
    expect(view.get('[data-testid="body-TEAM"]').element).toBe(team)
    expectMountedOnce(['TEAM', 'ORGANIZATION'])
  })

  it('未確定dragと閾値超過後のtouchcancelでは選択も未訪問bodyも変えない', async () => {
    const view = await render()
    const viewport = await beginDrag(view, -300)
    expect(state.store.activePanel).toBe('PERSONAL')
    expectMountedOnce(['PERSONAL'])
    await viewport.trigger('touchcancel')
    expect(state.store.activePanel).toBe('PERSONAL')
    expectMountedOnce(['PERSONAL'])
  })

  it.each([{ dx: -250, elapsed: 1000 }, { dx: -80, elapsed: 100 }])('touchendは閾値または慣性で初回選択を確定する（$dx px/$elapsed ms）', async ({ dx, elapsed }) => {
    const view = await render()
    fakeAnimationClock()
    const viewport = await beginDrag(view, dx)
    await vi.advanceTimersByTimeAsync(elapsed)
    await viewport.trigger('touchend')
    await flushPromises()
    expect(state.store.activePanel).toBe('TEAM')
    expectMountedOnce(['PERSONAL', 'TEAM'])
  })

  it('閾値/慣性未満と縦dragは初回選択を確定しない', async () => {
    const view = await render()
    fakeAnimationClock()
    const viewport = await beginDrag(view, -30)
    await vi.advanceTimersByTimeAsync(1000)
    await viewport.trigger('touchend')
    const vertical = await beginDrag(view, -20, 200)
    await vertical.trigger('touchend')
    expect(state.store.activePanel).toBe('PERSONAL')
    expectMountedOnce(['PERSONAL'])
  })
})
