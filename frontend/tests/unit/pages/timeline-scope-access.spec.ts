// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive, ref, type Ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamTimeline from '~/pages/teams/[slug]/timeline.vue'
import OrgTimeline from '~/pages/organizations/[slug]/timeline.vue'

const mocks = vi.hoisted(() => ({ route: null as unknown, shell: null as unknown }))
mockNuxtImport('useRoute', () => () => mocks.route)
vi.mock('~/composables/useTeamShellContext', () => ({ useTeamShellContext: () => mocks.shell }))
vi.mock('~/composables/useOrgShellContext', () => ({ useOrgShellContext: () => mocks.shell }))
const Feed = defineComponent({
  name: 'TimelineFeed', props: ['scopeType', 'scopeId'], emits: ['read-access'],
  setup(_, { expose }) { expose({ refresh: () => {} }); return () => h('div', { 'data-testid': 'feed-stub' }) },
})
const Form = defineComponent({
  name: 'TimelinePostForm', props: ['scopeType', 'scopeId', 'scopeSlug'],
  setup() {
    const draft = ref('')
    return () => h('input', { 'data-testid': 'composer-stub', value: draft.value, onInput: (event: Event) => { draft.value = (event.target as HTMLInputElement).value } })
  },
})
interface Scope { id: string; slug: string; numericId?: number }
let entity: Ref<Scope | null>
let route: { params: { slug: string } }
let wrapper: VueWrapper | undefined
beforeEach(() => {
  route = reactive({ params: { slug: 'public-a' } })
  entity = ref({ id: 'public-a', slug: 'public-a', numericId: 101 })
  mocks.route = route
  mocks.shell = { team: entity, org: entity, isAdmin: ref(false), isAdminOrDeputy: ref(false) }
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe.each([{ name: 'TEAM', component: TeamTimeline }, { name: 'ORGANIZATION', component: OrgTimeline }])('$name timeline の composer 境界', ({ name, component }) => {
  function render() {
    wrapper = mount(component, { global: { stubs: { TimelineFeed: Feed, TimelinePostForm: Form, PageLoading: true, PageHeader: true } } })
    return wrapper
  }
  it('初回read未成立ではformなし、内部IDの空200後にだけ既存formを表示する', async () => {
    const view = render(); await flushPromises()
    expect(view.findComponent(Feed).props('scopeId')).toBe(101)
    expect(view.findComponent(Form).exists()).toBe(false)
    view.findComponent(Feed).vm.$emit('read-access', true, name, 101); await flushPromises()
    expect(view.findComponent(Form).props()).toMatchObject({ scopeId: 101, scopeSlug: 'public-a' })
  })
  it('200後のrefreshで入力を保持し、403通知時にはformを撤去する', async () => {
    const view = render(); await flushPromises()
    const feed = view.findComponent(Feed)
    feed.vm.$emit('read-access', true, name, 101); await flushPromises()
    const input = view.get('[data-testid="composer-stub"]'); await input.setValue('未送信の下書き')
    feed.vm.$emit('read-access', true, name, 101); await flushPromises()
    expect(view.get('[data-testid="composer-stub"]').element).toBe(input.element)
    expect((input.element as HTMLInputElement).value).toBe('未送信の下書き')
    feed.vm.$emit('read-access', false, name, 101); await flushPromises()
    expect(view.findComponent(Form).exists()).toBe(false)
  })
  it('scope切替時に前内部IDの遅延200や下書きを持ち込まない', async () => {
    const view = render(); await flushPromises()
    view.findComponent(Feed).vm.$emit('read-access', true, name, 101); await flushPromises()
    await view.get('[data-testid="composer-stub"]').setValue('前scopeの入力')
    route.params.slug = '101'; await flushPromises()
    expect(view.findComponent(Feed).exists()).toBe(false)
    expect(view.findComponent(Form).exists()).toBe(false)
    entity.value = { id: '101', slug: '101', numericId: 202 }; await flushPromises()
    const feed = view.findComponent(Feed)
    expect(feed.props('scopeId')).toBe(202)
    feed.vm.$emit('read-access', true, name, 101); await flushPromises()
    expect(view.findComponent(Form).exists()).toBe(false)
    feed.vm.$emit('read-access', true, name, 202); await flushPromises()
    expect(view.findComponent(Form).props()).toMatchObject({ scopeId: 202, scopeSlug: '101' })
    expect((view.get('[data-testid="composer-stub"]').element as HTMLInputElement).value).toBe('')
  })
})
