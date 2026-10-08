import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useProxyAdmin } from './useProxyAdmin'
import type { ProxyInputConsent } from '~/types/proxy-input'

const { api, proxyApi, auth, handleApiError } = vi.hoisted(() => ({
  api: vi.fn(),
  proxyApi: { getConsentsByOrg: vi.fn(), getRecords: vi.fn() },
  auth: { isSystemAdmin: false, user: { id: 3 } },
  handleApiError: vi.fn(),
}))
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useProxyInputApi', () => () => proxyApi)
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

const consent: ProxyInputConsent = {
  id: 7,
  organizationId: 10,
  subjectUserId: 1,
  proxyUserId: 2,
  consentMethod: 'PAPER_SIGNED',
  effectiveFrom: '2026-01-01',
  effectiveUntil: '2026-12-31',
  status: 'PENDING_APPROVAL',
  approvedAt: null,
  approvedByUserId: null,
  revokedAt: null,
  revokeMethod: null,
  revokeReason: null,
  scopes: ['SURVEY'],
}

async function start(mode: 'consents' | 'records' = 'consents') {
  let state!: ReturnType<typeof useProxyAdmin>
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useProxyAdmin(mode)
        return () => h('div')
      },
    }),
  )
  await flushPromises()
  return { state, wrapper }
}

describe('useProxyAdmin', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.isSystemAdmin = false
    api.mockImplementation(async (path: string) =>
      path === '/api/v1/me/organizations'
        ? {
            data: [
              { id: 10, slug: 'my-org', name: '自組合', role: 'ADMIN' },
              { id: 20, slug: 'member-org', name: '一般所属', role: 'MEMBER' },
            ],
          }
        : { data: { roleName: 'ADMIN', permissions: ['PROXY_CONSENT_APPROVE'] } },
    )
    proxyApi.getConsentsByOrg.mockResolvedValue({
      data: [consent],
      meta: { total: 21, page: 0, size: 20, totalPages: 2 },
    })
    proxyApi.getRecords.mockResolvedValue({
      data: [],
      meta: { total: 0, page: 0, size: 20, totalPages: 0 },
    })
  })

  it('管理組合の内部IDとmeta.totalを使い自己承認と越境操作を拒む', async () => {
    const { state, wrapper } = await start()

    expect(state.organizations.value.map((org) => org.slug)).toEqual(['my-org'])
    expect(proxyApi.getConsentsByOrg).toHaveBeenCalledWith('10', { page: 0, size: 20 })
    expect(state.pagination.totalRecords.value).toBe(21)
    expect(state.mayApprove(consent)).toBe(true)
    expect(state.mayApprove({ ...consent, proxyUserId: 3 })).toBe(false)
    expect(state.mayApprove({ ...consent, organizationId: 20 })).toBe(false)
    expect(state.mayRevoke({ ...consent, revokedAt: '2026-01-02T10:00:00' })).toBe(false)
    wrapper.unmount()
  })

  it('SYSTEM_ADMINの直接URL利用でも管理組合がなければ組合業務一覧を取得しない', async () => {
    auth.isSystemAdmin = true
    api.mockResolvedValue({ data: [] })
    const { state, wrapper } = await start()

    expect(api).toHaveBeenCalledWith('/api/v1/me/organizations')
    expect(proxyApi.getConsentsByOrg).not.toHaveBeenCalled()
    expect(state.error.value).toMatchObject({ statusCode: 403 })
    expect(handleApiError).not.toHaveBeenCalled()
    expect(state.loading.value).toBe(false)
    wrapper.unmount()
  })

  it('権限取得失敗を空状態とせず再試行時に依存一覧まで取得する', async () => {
    api.mockRejectedValueOnce({ statusCode: 503 })
    const { state, wrapper } = await start()
    expect(state.error.value).toMatchObject({ statusCode: 503 })
    expect(proxyApi.getConsentsByOrg).not.toHaveBeenCalled()

    await state.loadPage()

    expect(state.error.value).toBeUndefined()
    expect(state.consents.value).toEqual([consent])
    wrapper.unmount()
  })

  it('SYSTEM_ADMINフラグで実在する自組合ADMINの直接URL操作を一律拒否しない', async () => {
    auth.isSystemAdmin = true
    const { state, wrapper } = await start()

    expect(state.error.value).toBeUndefined()
    expect(proxyApi.getConsentsByOrg).toHaveBeenCalledWith('10', { page: 0, size: 20 })
    wrapper.unmount()
  })

  it('DEPUTY_ADMINは一覧を読めても承認権限がなければ承認不可', async () => {
    api.mockImplementation(async (path: string) =>
      path === '/api/v1/me/organizations'
        ? { data: [{ id: 10, slug: 'my-org', name: '自組合', role: 'DEPUTY_ADMIN' }] }
        : { data: { roleName: 'DEPUTY_ADMIN', permissions: [] } },
    )
    const { state, wrapper } = await start()

    expect(state.error.value).toBeUndefined()
    expect(state.mayApprove(consent)).toBe(false)
    expect(state.mayRevoke(consent)).toBe(true)
    wrapper.unmount()
  })

  it('一覧表示前の最新権限がMEMBERなら取得を止める', async () => {
    api.mockImplementation(async (path: string) =>
      path === '/api/v1/me/organizations'
        ? { data: [{ id: 10, slug: 'my-org', name: '自組合', role: 'ADMIN' }] }
        : { data: { roleName: 'MEMBER', permissions: [] } },
    )
    const { state, wrapper } = await start()

    expect(state.error.value).toMatchObject({ statusCode: 403 })
    expect(proxyApi.getConsentsByOrg).not.toHaveBeenCalled()
    expect(handleApiError).not.toHaveBeenCalled()
    expect(state.loading.value).toBe(false)
    wrapper.unmount()
  })

  it('権限APIが返す403はローカル拒否と区別してAPIエラー処理を続ける', async () => {
    const cause = { statusCode: 403, message: '権限APIの取得に失敗しました' }
    api.mockImplementation(async (path: string) => {
      if (path === '/api/v1/me/organizations') {
        return { data: [{ id: 10, slug: 'my-org', name: '自組合', role: 'ADMIN' }] }
      }
      throw cause
    })

    const { state, wrapper } = await start()

    expect(state.error.value).toBe(cause)
    expect(proxyApi.getConsentsByOrg).not.toHaveBeenCalled()
    expect(handleApiError).toHaveBeenCalledExactlyOnceWith(cause, '代理入力管理一覧取得')
    expect(state.loading.value).toBe(false)
    wrapper.unmount()
  })

  it('監査一覧は自組合を指定しページ切替後もスコープを維持する', async () => {
    const { state, wrapper } = await start('records')

    await state.changePage({ page: 1, rows: 50 })

    expect(proxyApi.getRecords).toHaveBeenLastCalledWith({ organizationId: 10, page: 1, size: 50 })
    expect(state.records.value).toEqual([])
    expect(state.error.value).toBeUndefined()
    wrapper.unmount()
  })

  it('離脱後は操作権限と再取得を失効させる', async () => {
    const { state, wrapper } = await start()
    expect(state.mayApprove(consent)).toBe(true)
    expect(state.mayRevoke(consent)).toBe(true)
    wrapper.unmount()
    vi.clearAllMocks()

    await state.loadPage()
    await state.changePage({ page: 1, rows: 50 })
    await state.changeOrganization('member-org')

    expect(state.mayApprove(consent)).toBe(false)
    expect(state.mayRevoke(consent)).toBe(false)
    expect(api).not.toHaveBeenCalled()
    expect(proxyApi.getConsentsByOrg).not.toHaveBeenCalled()
    expect(state.pagination.page.value).toBe(0)
    expect(state.organizationSlug.value).toBe('my-org')
  })
})
