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
const orgs = { data: { id: 'oak-org', slug: 'oak-org', numericId: 42 } }
const nextOrg = { data: { id: 'next-org', slug: 'next-org', numericId: 84 } }
const teams = { data: { id: 'oak-team', slug: 'oak-team', numericId: 63 } }

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
    path === '/api/v1/teams/oak-team' ? teams : path === '/api/v1/organizations/next-org' ? nextOrg : orgs,
  ))
})

describe('サイネージのroute slugとAPI数値scopeIdの境界', () => {
  it.each([
    { scope: 'ORGANIZATION', slug: 'oak-org', id: 42, page: OrganizationSignagePage, endpoint: '/api/v1/organizations/oak-org' },
    { scope: 'TEAM', slug: 'oak-team', id: 63, page: TeamSignagePage, endpoint: '/api/v1/teams/oak-team' },
  ])('$scope: 非数値slugを解決しManagerへ数値を渡す', async ({ slug, id, page, endpoint, scope }) => {
    mocks.route.params.slug = slug
    const wrapper = await mountPage(page)
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(id)
    expect(wrapper.findComponent(SignageManagerStub).props('scopeType')).toBe(scope)
    expect(mocks.api).toHaveBeenCalledWith(endpoint)
  })

  it('解決不能時はManagerを出さず、同じ解決処理の再試行で復旧する', async () => {
    mocks.api.mockRejectedValue(new Error('scope unavailable'))
    const wrapper = await mountPage()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(false)
    expect(wrapper.find('[data-testid="scope-error"]').exists()).toBe(true)
    mocks.api.mockResolvedValue(orgs)
    await wrapper.get('[data-testid="scope-error"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(42)
  })

  it('所属一覧にいないSYSTEM_ADMINも、既存の正規detailから数値IDを解決できる', async () => {
    mocks.route.params.slug = 'unjoined-org'
    mocks.api.mockImplementation((path: string) => Promise.resolve(
      path.startsWith('/api/v1/me/')
        ? { data: [] }
        : { data: { id: 'unjoined-org', slug: 'unjoined-org', numericId: 77 } },
    ))
    const wrapper = await mountPage()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(true)
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(77)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/organizations/unjoined-org')
  })

  it('解決済みAからBへの遷移時は旧Managerを即外し、BのIDだけで再生成する', async () => {
    const wrapper = await mountPage()
    const pending = deferred<typeof orgs>()
    mocks.api.mockReturnValue(pending.promise)
    mocks.route.params.slug = 'next-org'
    await nextTick()
    expect(wrapper.findComponent(SignageManagerStub).exists()).toBe(false)
    pending.resolve(nextOrg)
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
    second.resolve(nextOrg)
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(84)
    first.resolve(orgs)
    await flushPromises()
    expect(wrapper.findComponent(SignageManagerStub).props('scopeId')).toBe(84)
  })
})
