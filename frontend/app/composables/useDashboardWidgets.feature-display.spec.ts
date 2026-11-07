// @vitest-environment happy-dom
import { computed, defineComponent, ref, type Ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDashboardWidgets } from './useDashboardWidgets'
import type { ViewerRole } from '~/types/dashboard'

const external = vi.hoisted(() => ({ api: vi.fn(), settings: vi.fn(), auth: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => external.api }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => external.auth() }))
vi.mock('#app/composables/asyncData', () => ({
  useAsyncData: (...args: unknown[]) => external.settings(...args),
}))
vi.mock('#app/nuxt', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }))
let owner: Ref<string>
function render(role: ViewerRole | Ref<ViewerRole> | (() => ViewerRole) = 'MEMBER') {
  let widgets!: ReturnType<typeof useDashboardWidgets>
  const wrapper = mount(
    defineComponent({
      setup() {
        widgets = useDashboardWidgets('team', 'scope-a', role, () => [
          { widget_key: 'TEAM_UNREAD_THREADS', min_role: 'MEMBER', is_default: false },
        ])
        return () => null
      },
    }),
  )
  return { widgets, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  owner = ref('viewer-a')
  external.auth.mockImplementation(() => ({
    get user() {
      return { id: owner.value }
    },
  }))
  external.api.mockResolvedValue({})
  external.settings.mockImplementation(() => ({ data: ref([]), status: ref('success') }))
})

describe('useDashboardWidgets 表示ポリシーとのANDと設定保持', () => {
  it.each(['ref', 'getter'])(
    '%sのロール変更を反映しADMINからSUPPORTER/PUBLICへ昇格させない',
    (kind) => {
      const role = ref<ViewerRole>('ADMIN')
      const view = render(kind === 'ref' ? role : () => role.value)
      expect(view.widgets.visibleWidgets.value.some((widget) => widget.key === 'chat')).toBe(true)
      role.value = 'SUPPORTER'
      expect(view.widgets.visibleWidgets.value.some((widget) => widget.key === 'chat')).toBe(false)
      role.value = 'PUBLIC'
      expect(view.widgets.visibleWidgets.value.some((widget) => widget.key === 'chat')).toBe(false)
      view.wrapper.unmount()
    },
  )

  it('本人別の設定取得キーを使い、再mountで前の本人の非表示/順序を再利用しない', () => {
    external.settings.mockImplementation((key: string) => ({
      data: ref(
        key.includes('viewer-a')
          ? [{ widgetKey: 'TEAM_UNREAD_THREADS', visible: false, sortOrder: 0 }]
          : [{ widgetKey: 'TEAM_LATEST_POSTS', visible: false, sortOrder: 0 }],
      ),
      status: ref('success'),
    }))
    const first = render()
    expect(first.widgets.hiddenKeys.value.has('chat')).toBe(true)
    first.wrapper.unmount()
    owner.value = 'viewer-b'
    const second = render()
    expect(second.widgets.hiddenKeys.value.has('chat')).toBe(false)
    expect(second.widgets.hiddenKeys.value.has('timeline')).toBe(true)
    expect(external.settings.mock.calls.map((call) => call[0])).toEqual([
      'dashboard-widgets:viewer-a:team:scope-a',
      'dashboard-widgets:viewer-b:team:scope-a',
    ])
    second.wrapper.unmount()
  })

  it('policyで候補を絞っても全量PUTは非表示機能の個人hide/orderを保持する', async () => {
    external.settings.mockReturnValue({
      data: ref([
        { widgetKey: 'TEAM_UNREAD_THREADS', visible: false, sortOrder: 0 },
        { widgetKey: 'TEAM_LATEST_POSTS', visible: true, sortOrder: 1 },
        { widgetKey: 'TEAM_BLOG', visible: true, sortOrder: 2 },
      ]),
      status: ref('success'),
    })
    const view = render()
    const candidates = computed(() =>
      view.widgets.sortedWidgets.value.filter((widget) => widget.key !== 'chat'),
    )
    expect(candidates.value[0]?.key).toBe('timeline')
    view.widgets.reorder(2, 1)
    await flushPromises()
    expect(external.api.mock.calls[0]?.[1].body.widgets).toEqual(
      expect.arrayContaining([
        { widgetKey: 'TEAM_UNREAD_THREADS', isVisible: false, sortOrder: 0 },
      ]),
    )
    expect(view.widgets.sortedWidgets.value.slice(0, 3).map((widget) => widget.key)).toEqual([
      'chat',
      'blog',
      'timeline',
    ])
    view.wrapper.unmount()
  })
})
