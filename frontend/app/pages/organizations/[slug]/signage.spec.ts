import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, nextTick, reactive } from 'vue'
import OrganizationSignagePage from './signage.vue'
import TeamSignagePage from '~/pages/teams/[slug]/signage.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  route: { params: { slug: 'oak-org' } },
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useRoute', () => () => mocks.route)

const SignageManagerStub = defineComponent({
  name: 'SignageManagerStub',
  props: ['scopeType', 'scopeId'],
  template: '<div data-testid="signage-manager" />',
})
const stubs = {
  SignageManager: SignageManagerStub,
  PageLoading: true,
  DashboardErrorState: {
    emits: ['retry'],
    template: '<button data-testid="scope-error" @click="$emit(\'retry\')">retry</button>',
  },
}
const orgs = { data: [{ id: 42, slug: 'oak-org' }, { id: 84, slug: 'next-org' }] }
const teams = { data: [{ id: 63, slug: 'oak-team' }] }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

async function mountPage(page = OrganizationSignagePage) {
  const wrapper = await mountSuspended(page, { global: { stubs } })
  await flushPromises()
  return wrapper
}

beforeAll(async () => {
  mocks.route = reactive(mocks.route)
  mocks.api.mockResolvedValue(orgs)
  const warmup = await mountPage()
  warmup.unmount()
})
beforeEach(() => {
  mocks.route.params.slug = 'oak-org'
  mocks.api.mockReset()
  mocks.api.mockImplementation((path: string) => Promise.resolve(
    path === '/api/v1/me/teams' ? teams : orgs,
  ))
})

describe('サイネージのroute slugとAPI数値scopeIdの境界', () => {
  it.each([
    { scope: 'ORGANIZATION', slug: 'oak-org', id: 42, page: OrganizationSignagePage, endpoint: '/api/v1/me/organizations' },
    { scope: 'TEAM', slug: 'oak-team', id: 63, page: TeamSignagePage, endpoint: '/api/v1/me/teams' },
  ])('$scope: 非数値slugを解決しManagerへ数値を渡す', async ({ slug, id, page, endpoint, scope }) => {
    mocks.route.params.slug = slug
    const wrapper = await mountPage(page)
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(id)
    expect(wrapper.findComponent(SignageManagerStub).props('scopeType')).toBe(scope)
    expect(mocks.api).toHaveBeenCalledWith(endpoint)
  })

  it('解決不能時はManagerを出さず、同じ解決処理の再試行で復旧する', async () => {
    mocks.api.mockResolvedValue({ data: [] })
    const wrapper = await mountPage()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(false)
    expect(wrapper.find('[data-testid="scope-error"]').exists()).toBe(true)
    mocks.api.mockResolvedValue(orgs)
    await wrapper.get('[data-testid="scope-error"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(42)
  })

  it('解決済みAからBへの遷移時は旧Managerを即外し、BのIDだけで再生成する', async () => {
    const wrapper = await mountPage()
    const pending = deferred<typeof orgs>()
    mocks.api.mockReturnValue(pending.promise)
    mocks.route.params.slug = 'next-org'
    await nextTick()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(false)
    pending.resolve(orgs)
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(84)
  })

  it('A解決待ちにBへ移り、遅いA応答がBのIDを上書きしない', async () => {
    const first = deferred<typeof orgs>()
    const second = deferred<typeof orgs>()
    mocks.api.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = await mountPage()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(false)
    mocks.route.params.slug = 'next-org'
    await nextTick()
    second.resolve(orgs)
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(84)
    first.resolve(orgs)
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(84)
  })
})
