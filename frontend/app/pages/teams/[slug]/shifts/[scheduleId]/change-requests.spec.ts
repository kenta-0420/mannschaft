// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import PrimeVue from 'primevue/config'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Page from './change-requests.vue'
import ShiftChangeRequestList from '~/components/shift/ShiftChangeRequestList.vue'
import type { ChangeRequest, ChangeRequestStatus } from '~/types/shift'

// ページ・権限composable・変更依頼API・子一覧は実物。通信境界だけ応答を差し替える。
const mocks = vi.hoisted(() => ({ api: vi.fn(), error: vi.fn(), success: vi.fn() }))
const params = reactive({ slug: 'team-a', scheduleId: '425' })
const auth = reactive({ user: { id: 1, systemRole: 'MEMBER' }, get currentUser() { return this.user } })
mockNuxtImport('useRoute', () => () => ({ params }))
mockNuxtImport('useAuthStore', () => () => auth)
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ error: mocks.error, success: mocks.success }))

function request(status: ChangeRequestStatus = 'OPEN', id = 7): ChangeRequest {
  return {
    id, scheduleId: 425, slotId: null,
    requestInfo: { requestedBy: 2, requestType: 'PRE_CONFIRM_EDIT', reason: 'reason' },
    reviewInfo: { status, reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { createdAt: '2026-10-27T12:00:00', expiresAt: null },
  }
}
function permissions(roleName: string | null, scopeAdmin = false) {
  return { data: { roleName, scopeAdmin, permissions: [] } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
let role: string | null
let rows: ChangeRequest[]
let permissionError: boolean
let listError: boolean
let wrapper: VueWrapper | undefined
function respond(url: string, options?: { body?: { decision: ChangeRequestStatus } }) {
  if (url.endsWith('/me/permissions')) {
    return permissionError ? Promise.reject(new Error('permissions unavailable')) : Promise.resolve(permissions(role, true))
  }
  if (url.includes('/review')) return Promise.resolve({ data: request(options!.body!.decision) })
  if (url.includes('/change-requests?')) {
    return listError ? Promise.reject(new Error('list unavailable')) : Promise.resolve({ data: rows })
  }
  throw new Error(`Unexpected API: ${url}`)
}
function renderPage() {
  wrapper = mount(Page, { global: {
    plugins: [[PrimeVue, { unstyled: true }]],
    components: { ShiftChangeRequestList, DataTable, Column, Button },
    mocks: { $t: (key: string) => key },
    stubs: {
      ShiftChangeRequestForm: true,
      SectionCard: { template: '<section><slot /></section>' },
      PageHeader: true, NuxtLink: { template: '<a><slot /></a>' },
      Tag: true, Textarea: true, PageLoading: true,
      Dialog: { props: ['visible'], template: '<div v-if="visible" data-testid="review-dialog"><slot /><slot name="footer" /></div>' },
    },
  } })
  return wrapper
}
function reviewButtons() {
  return wrapper!.findAll('button').filter((button) =>
    ['shift.changeRequest.approve', 'shift.changeRequest.reject'].includes(button.text()))
}

beforeEach(() => {
  vi.clearAllMocks()
  params.slug = 'team-a'
  params.scheduleId = '425'
  auth.user = { id: 1, systemRole: 'MEMBER' }
  role = 'ADMIN'
  rows = [request()]
  permissionError = false
  listError = false
  mocks.api.mockImplementation(respond)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('変更依頼のチーム権限による審査UI（CMP1432-A）', () => {
  it.each(['ACCEPTED', 'REJECTED'] as const)('AC1 純チームADMINが%sを画面から送信できる', async (decision) => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    await reviewButtons()[decision === 'ACCEPTED' ? 0 : 1]!.trigger('click')
    const confirm = wrapper!.get('[data-testid="review-dialog"]').findAll('button').at(-1)!
    await confirm.trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/shifts/change-requests/7/review', {
      method: 'PATCH', body: { decision, reviewComment: undefined, version: 0 },
    })
  })

  it('AC2 DEPUTY_ADMINも審査ボタンを表示する', async () => {
    role = 'DEPUTY_ADMIN'
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
  })

  it.each(['MEMBER', 'SUPPORTER', 'GUEST', null, 'SYSTEM_ADMIN'])('AC2 %sはscopeAdmin=trueでも表示しない（既存UI契約）', async (currentRole) => {
    role = currentRole
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC3 チーム切替で旧ADMINを即時破棄し、他チームMEMBERには表示しない', async () => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    const next = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? next.promise : respond(url))
    params.slug = 'team-b'
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/team-b/me/permissions')
    next.resolve(permissions('MEMBER'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC3 チーム切替後に旧ADMIN応答が遅れても復活しない', async () => {
    const old = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.includes('/team-a/me/permissions') ? old.promise : respond(url))
    renderPage()
    await flushPromises()
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    old.resolve(permissions('ADMIN'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC4 本人切替で旧ADMINを破棄し、新本人のMEMBER応答に従う', async () => {
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(2)
    const next = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? next.promise : respond(url))
    auth.user = { id: 3, systemRole: 'MEMBER' }
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    next.resolve(permissions('MEMBER'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC5 権限未解決では表示せず、取得失敗でも表示しない（既存契約）', async () => {
    const pending = deferred<ReturnType<typeof permissions>>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? pending.promise : respond(url))
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    wrapper!.unmount()
    permissionError = true
    mocks.api.mockImplementation(respond)
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
    pending.resolve(permissions('ADMIN'))
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it.each(['ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'] as const)('AC5 %s済み依頼に表示しない（既存契約）', async (status) => {
    rows = [request(status)]
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC5 空一覧では表示しない（既存契約）', async () => {
    rows = []
    renderPage()
    await flushPromises()
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC6 一覧GET失敗は既存のエラー通知を保ち、審査操作を出さない', async () => {
    listError = true
    renderPage()
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('shift.changeRequest.fetchError')
    expect(reviewButtons()).toHaveLength(0)
  })

  it('AC7 一覧の件数によらず初回の権限取得は1本', async () => {
    rows = [request('OPEN', 7), request('OPEN', 8), request('OPEN', 9)]
    renderPage()
    await flushPromises()
    expect(mocks.api.mock.calls.filter(([url]) => String(url).endsWith('/me/permissions')))
      .toEqual([['/api/v1/teams/team-a/me/permissions']])
  })
})