import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockComponent, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamTimeline from '~/pages/teams/[slug]/timeline.vue'
import OrgTimeline from '~/pages/organizations/[slug]/timeline.vue'

const { route } = vi.hoisted(() => ({ route: { params: { slug: 'scope-a' } } }))
const reactiveRoute = reactive(route)
mockNuxtImport('useRoute', () => () => reactiveRoute)
mockNuxtImport('useRoleAccess', () => () => ({ isAdmin: ref(false), isAdminOrDeputy: ref(false), loadPermissions: vi.fn().mockResolvedValue({ ok: true }) }))
mockComponent('TimelineFeed', async () => {
  const { defineComponent, h } = await import('vue')
  return defineComponent({ name: 'TimelineFeedStub', props: { scopeType: String, scopeId: [String, Number] }, emits: ['read-access'], setup(_, { expose }) { expose({ refresh: () => {} }); return () => h('div', { 'data-testid': 'feed-stub' }) } })
})
mockComponent('TimelinePostForm', async () => {
  const { defineComponent, h, ref } = await import('vue')
  return defineComponent({ name: 'TimelinePostFormStub', props: { scopeType: String, scopeId: [String, Number] }, setup() { const draft = ref(''); return () => h('input', { 'data-testid': 'composer-stub', value: draft.value, onInput: (event: Event) => { draft.value = (event.target as HTMLInputElement).value } }) } })
})
const wrappers: Array<Awaited<ReturnType<typeof mountSuspended>>> = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
beforeEach(() => { reactiveRoute.params.slug = 'scope-a' })

describe.each([{ name: 'TEAM', component: TeamTimeline }, { name: 'ORGANIZATION', component: OrgTimeline }])('$name timeline の composer 境界', ({ name, component }) => {
  async function mountPage() { const wrapper = await mountSuspended(component); wrappers.push(wrapper); await flushPromises(); return wrapper }
  it('初回read未成立ではformなし、同scope空200後にだけ既存formを表示する', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="composer-stub"]').exists()).toBe(false)
    wrapper.findComponent({ name: 'TimelineFeedStub' }).vm.$emit('read-access', true, name, 'scope-a'); await flushPromises()
    expect(wrapper.find('[data-testid="composer-stub"]').exists()).toBe(true)
  })
  it('200後のrefreshで入力を保持し、403通知時にはformを撤去する', async () => {
    const wrapper = await mountPage(); const feed = wrapper.findComponent({ name: 'TimelineFeedStub' })
    feed.vm.$emit('read-access', true, name, 'scope-a'); await flushPromises()
    const input = wrapper.get('[data-testid="composer-stub"]'); await input.setValue('未送信の下書き')
    feed.vm.$emit('read-access', true, name, 'scope-a'); await flushPromises()
    expect(wrapper.get('[data-testid="composer-stub"]').element).toBe(input.element)
    expect((input.element as HTMLInputElement).value).toBe('未送信の下書き')
    feed.vm.$emit('read-access', false, name, 'scope-a'); await flushPromises()
    expect(wrapper.find('[data-testid="composer-stub"]').exists()).toBe(false)
  })
  it('scope切替時に前scopeの遅延200や下書きを持ち込まない', async () => {
    const wrapper = await mountPage(); const oldFeed = wrapper.findComponent({ name: 'TimelineFeedStub' })
    oldFeed.vm.$emit('read-access', true, name, 'scope-a'); await flushPromises()
    await wrapper.get('[data-testid="composer-stub"]').setValue('前scopeの入力')
    reactiveRoute.params.slug = 'scope-b'; await flushPromises()
    expect(wrapper.find('[data-testid="composer-stub"]').exists()).toBe(false)
    wrapper.findComponent({ name: 'TimelineFeedStub' }).vm.$emit('read-access', true, name, 'scope-a'); await flushPromises()
    expect(wrapper.find('[data-testid="composer-stub"]').exists()).toBe(false)
    wrapper.findComponent({ name: 'TimelineFeedStub' }).vm.$emit('read-access', true, name, 'scope-b'); await flushPromises()
    expect((wrapper.get('[data-testid="composer-stub"]').element as HTMLInputElement).value).toBe('')
  })
})
