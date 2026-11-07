// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { computed, defineComponent, h, nextTick, onMounted, ref, Transition } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InfoPage from './info.vue'
import { useOrgShellContext } from '~/composables/useOrgShellContext'

vi.mock('~/composables/useOrgShellContext', () => ({ useOrgShellContext: vi.fn() }))

// OrgInfoTab は編集ダイアログ等を含む Fragment。遷移だけを調べ、業務 API は呼ばない。
const FragmentInfo = defineComponent({
  name: 'OrgInfoTab',
  props: ['org', 'isAdmin', 'ancestors'],
  setup: () => () => [h('h3', '基本情報 / Basisinformationen'), h('section', '組織の詳細')],
})

afterEach(() => vi.restoreAllMocks())

describe('組織基本情報から所属チームへ戻る out-in 遷移', () => {
  it.each([true, false])('管理者=%s: 戻り先がマウントされ本文と取得が復帰する', async (isAdmin) => {
    vi.mocked(useOrgShellContext).mockReturnValue({
      org: computed(() => ({ id: 'example-org' })),
      isAdmin: computed(() => isAdmin),
      ancestors: ref([]),
      refresh: vi.fn(),
    } as unknown as ReturnType<typeof useOrgShellContext>)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const readTeams = vi.fn()
    const current = ref('info')
    const Teams = defineComponent({
      setup() {
        onMounted(readTeams)
        return () => h('p', { 'data-testid': 'teams-body' }, '所属チーム / Zugehörige Teams')
      },
    })
    const Harness = defineComponent({
      setup: () => () =>
        h(
          Transition,
          {
            mode: 'out-in',
            css: false,
            // 実ブラウザの CSS leave と同じ非同期境界を保つ。
            onLeave: (_element: Element, done: () => void) => queueMicrotask(done),
          },
          {
            default: () => h(current.value === 'info' ? InfoPage : Teams, { key: current.value }),
          },
        ),
    })
    const wrapper = mount(Harness, {
      attachTo: document.body,
      global: {
        components: { OrgInfoTab: FragmentInfo },
        stubs: { transition: false, OrgInfoTab: FragmentInfo },
      },
    })
    expect(wrapper.text()).toContain('Basisinformationen')
    current.value = 'teams'
    await nextTick()
    await flushPromises()
    expect(wrapper.find('[data-testid="teams-body"]').exists()).toBe(true)
    expect(readTeams).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls.filter((args) => String(args[0]).includes('non-element root'))).toEqual(
      [],
    )
    wrapper.unmount()
  })

  it('組織取得前の null でも遷移可能な要素を維持する', () => {
    vi.mocked(useOrgShellContext).mockReturnValue({
      org: computed(() => null),
      isAdmin: computed(() => false),
      ancestors: ref([]),
      refresh: vi.fn(),
    } as unknown as ReturnType<typeof useOrgShellContext>)
    const wrapper = mount(InfoPage, { global: { stubs: { OrgInfoTab: FragmentInfo } } })
    expect(wrapper.element.nodeType).toBe(Node.ELEMENT_NODE)
    expect(wrapper.findComponent(FragmentInfo).exists()).toBe(false)
    wrapper.unmount()
  })
})
