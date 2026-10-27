// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, onMounted, reactive, ref, type Ref } from 'vue'
import { useTimelineApi } from '~/composables/useTimelineApi'
import Page from './timeline.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn(), route: null as unknown, shell: null as unknown }))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false), isAdminOrDeputy: ref(false), loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
vi.mock('~/composables/useOrgShellContext', () => ({ useOrgShellContext: () => mocks.shell }))
const Feed = defineComponent({
  name: 'TimelineFeed',
  props: ['scopeType', 'scopeId', 'canPin', 'canDeleteOthers'],
  setup(props) {
    onMounted(() => { void useTimelineApi().getFeed({ scopeType: props.scopeType, scopeId: props.scopeId }) })
    return () => null
  },
})
const Form = defineComponent({ name: 'TimelinePostForm', props: ['scopeType', 'scopeId', 'scopeSlug'], template: '<div />' })
interface Scope { id: string; slug: string; numericId?: number }
let entity: Ref<Scope | null>
let route: { params: { slug: string } }
let wrapper: VueWrapper | undefined
beforeEach(() => {
  mocks.api.mockReset().mockResolvedValue({ data: { pinned: [], posts: [] }, meta: { nextCursor: null, hasNext: false } })
  route = reactive({ params: { slug: '12345' } })
  entity = ref({ id: '12345', slug: '12345', numericId: 7 })
  mocks.route = route
  mocks.shell = { org: entity, isAdmin: ref(false), isAdminOrDeputy: ref(false) }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
function render() {
  wrapper = mount(Page, { global: { stubs: { TimelineFeed: Feed, TimelinePostForm: Form, PageLoading: true, PageHeader: true } } })
  return wrapper
}

describe('ORGANIZATION timelineの公開slugと内部ID', () => {
  it.each(['12345', '00012345', '9223372036854775808', 'scope-a'])(
    'FEED-SCOPE-4: slug=%sでも既取得の内部IDをfeedと投稿へ渡し追加取得しない', async slug => {
      route.params.slug = slug
      entity.value = { id: slug, slug, numericId: 7 }
      const view = render()
      await flushPromises()
      expect(mocks.api.mock.calls.map(call => call[0])).toEqual(['/api/v1/timeline/feed?scopeType=ORGANIZATION&scopeId=7'])
      expect(view.findComponent(Form).props()).toMatchObject({ scopeType: 'ORGANIZATION', scopeId: 7, scopeSlug: slug })
    },
  )
  it.each([null, { id: '12345', slug: '12345' }, { id: '12345', slug: '12345', numericId: 0 }])(
    'FEED-SCOPE-5: 内部ID未readyではfeedも投稿もmountしない(%j)', async value => {
      entity.value = value
      const view = render()
      await flushPromises()
      expect(view.findComponent(Feed).exists()).toBe(false)
      expect(view.findComponent(Form).exists()).toBe(false)
      expect(mocks.api).not.toHaveBeenCalled()
    },
  )
  it('FEED-SCOPE-6: 旧shellと新slugの組を送らず、衝突slugの新scopeへ作り直す', async () => {
    const view = render()
    await flushPromises()
    const oldForm = view.findComponent(Form).vm
    route.params.slug = '7'
    await flushPromises()
    expect(view.findComponent(Feed).exists()).toBe(false)
    expect(view.findComponent(Form).exists()).toBe(false)
    expect(mocks.api).toHaveBeenCalledTimes(1)
    entity.value = { id: '7', slug: '7', numericId: 21 }
    await flushPromises()
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/timeline/feed?scopeType=ORGANIZATION&scopeId=21')
    expect(view.findComponent(Form).props()).toMatchObject({ scopeId: 21, scopeSlug: '7' })
    expect(view.findComponent(Form).vm).not.toBe(oldForm)
  })
})
