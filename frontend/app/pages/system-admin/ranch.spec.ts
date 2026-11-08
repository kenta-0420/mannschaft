// @vitest-environment nuxt
// 実ページ・フォーム・Pinia・command memoryを使い、外部HTTP transportだけを置き換える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import RanchPage from './ranch.vue'
import RanchAdminContent from '~/components/ranch/RanchAdminContent.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { RanchCareRulePublicationRequest, RanchPublicationAck } from '~/types/ranch-admin'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))

const wrappers: { unmount: () => void }[] = []
const publications: { resolve: (value: Response) => void; signal?: AbortSignal }[] = []
const careForm = 'form[aria-labelledby="ranch-admin-care-publication-heading"]'
const policyForm = 'form[aria-labelledby="ranch-admin-policy-publication-heading"]'
const json = (data: unknown) => new Response(JSON.stringify({ data }), {
  headers: { 'Content-Type': 'application/json' },
})
function ack(reasonCode: string): RanchPublicationAck<RanchCareRulePublicationRequest> {
  return {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', version: '1', contentHash: 'synthetic-hash',
    effectiveAt: '2032-01-12T00:00:00Z', publishedAt: '2032-01-06T12:00:00Z', publishedBy: '1',
    settings: {
      effectiveAt: '2032-01-12T00:00:00Z', amountXp: '1', weeklyCapXp: '2',
      juvenileXp: '3', adultXp: '4', reasonCode,
    },
  }
}
function setRole(role: string) {
  const user = useAuthStore().user
  if (!user) throw new Error('SYNTHETIC_USER_MISSING')
  user.systemRole = role
}
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  auth.$reset()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic',
    profileImageUrl: null, systemRole: 'SYSTEM_ADMIN' })
  auth.setTokens('synthetic-access', 'synthetic-refresh')
  external.fetch.mockReset()
  external.report.mockReset()
  external.fetch.mockImplementation(async (request, options) => {
    const path = String(request)
    if (path.endsWith('/operational-controls')) return json({ version: '1', isCareEnabled: false,
      isShopEnabled: false, isDeliveryPaused: true, isRewardsPaused: false,
      updatedAt: '2032-01-06T12:00:00Z' })
    if (path.endsWith('/outbox-health')) return json({ sources: [], observedAt: '2032-01-06T12:00:00Z' })
    if (path.endsWith('/care-rules') && options?.method === 'POST') {
      // abortを無視して遅れて届く応答も、旧commandの世代では受理しないことを確かめる。
      return new Promise<Response>(resolve => publications.push({ resolve, signal: options.signal ?? undefined }))
    }
    throw new Error('UNEXPECTED_SYNTHETIC_TRANSPORT')
  })
})
afterEach(async () => {
  for (const publication of publications.splice(0)) publication.resolve(json(ack('CLEANUP')))
  await flushPromises()
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  useAuthStore().$reset()
  vi.restoreAllMocks()
})
async function mountPage() {
  const wrapper = await mountSuspended(RanchPage)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
async function submitCare(wrapper: Awaited<ReturnType<typeof mountPage>>, reasonCode: string) {
  const form = wrapper.get(careForm)
  for (const [name, value] of Object.entries(ack(reasonCode).settings)) {
    await form.get(`input[name="${name}"]`).setValue(value)
  }
  await form.trigger('submit')
  await flushPromises()
}
describe('運営恐竜ページのSYSTEM_ADMIN表示境界', () => {
  it('MEMBERの直打ちでは運営内容と公開フォームをmountせず、権限拒否を表示する', async () => {
    setRole('MEMBER')
    const wrapper = await mountPage()
    expect(wrapper.findComponent(RanchAdminContent).exists()).toBe(false)
    expect(wrapper.find(careForm).exists()).toBe(false)
    expect(wrapper.find(policyForm).exists()).toBe(false)
    expect(external.fetch).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="load-error-state"]').text())
      .toContain(useNuxtApp().$i18n.t('loadErrorState.states.forbidden.title'))
    expect(wrapper.find('[data-testid="load-error-state-retry"]').exists()).toBe(false)
  })
  it('SYSTEM_ADMINは実運営内容と両公開フォームを表示し、制御と配送状態を取得する', async () => {
    const wrapper = await mountPage()
    expect(wrapper.findComponent(RanchAdminContent).exists()).toBe(true)
    expect(wrapper.find(careForm).exists()).toBe(true)
    expect(wrapper.find(policyForm).exists()).toBe(true)
    expect(external.fetch).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
  })
  it('同じIDの降格で内容を除去し、再昇格は入力を持ち越さず再取得する', async () => {
    const wrapper = await mountPage()
    const oldContent = wrapper.getComponent(RanchAdminContent).vm
    await wrapper.get(`${careForm} input[name="reasonCode"]`).setValue('OLD_ADMIN_INPUT')
    setRole('MEMBER')
    await flushPromises()
    expect(useAuthStore().user?.id).toBe(1)
    expect(wrapper.findComponent(RanchAdminContent).exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    setRole('SYSTEM_ADMIN')
    await flushPromises()
    expect(wrapper.getComponent(RanchAdminContent).vm).not.toBe(oldContent)
    expect((wrapper.get(`${careForm} input[name="reasonCode"]`).element as HTMLInputElement).value).toBe('')
    expect(external.fetch).toHaveBeenCalledTimes(4)
  })
  it('降格で旧運営runだけ破棄し、遅延応答は再昇格後の入力と新命令を変更しない', async () => {
    const wrapper = await mountPage()
    const memory = useNuxtApp().$ranchCommandMemory
    const admin = memory.scopes['ranch-admin']
    const other = { path: '/api/v1/me/ranch/feeding', method: 'POST' as const, key: 'other-scope' }
    memory.scopes.ranch.pending.value = other
    const generation = memory.generation.value
    await submitCare(wrapper, 'OLD_ADMIN_COMMAND')
    expect(publications).toHaveLength(1)
    expect(admin.pending.value).not.toBeNull()
    setRole('MEMBER')
    // 同期watchはVueの次回描画前に旧runを失効させる。
    expect(admin.pending.value).toBeNull()
    expect(admin.running.value).toBe(false)
    expect(admin.activeRun).toBeNull()
    expect(publications[0]?.signal?.aborted).toBe(true)
    expect(memory.scopes.ranch.pending.value).toBe(other)
    expect(memory.accountId.value).toBe(1)
    expect(memory.generation.value).toBe(generation)
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    setRole('SYSTEM_ADMIN')
    await flushPromises()
    expect(admin.pending.value).toBeNull()
    await submitCare(wrapper, 'NEW_ADMIN_COMMAND')
    expect(publications).toHaveLength(2)
    const currentPending = admin.pending.value
    expect(currentPending).not.toBeNull()
    publications[0]?.resolve(json(ack('OLD_ADMIN_COMMAND')))
    await flushPromises()
    expect(admin.pending.value).toBe(currentPending)
    expect(admin.running.value).toBe(true)
    expect(publications[1]?.signal?.aborted).toBe(false)
    expect((wrapper.get(`${careForm} input[name="reasonCode"]`).element as HTMLInputElement).value)
      .toBe('NEW_ADMIN_COMMAND')
    expect(wrapper.findAll('[role="status"]').every(item => item.text() === '')).toBe(true)
    publications[1]?.resolve(json(ack('NEW_ADMIN_COMMAND')))
    await flushPromises()
    expect(admin.pending.value).toBeNull()
    expect(admin.running.value).toBe(false)
  })
  it('同じ描画tick内の降格と再昇格でも旧フォームを再利用しない', async () => {
    const wrapper = await mountPage()
    const oldContent = wrapper.getComponent(RanchAdminContent).vm
    await wrapper.get(`${careForm} input[name="reasonCode"]`).setValue('OLD_ADMIN_INPUT')
    setRole('MEMBER')
    setRole('SYSTEM_ADMIN')
    await flushPromises()
    expect(wrapper.getComponent(RanchAdminContent).vm).not.toBe(oldContent)
    expect((wrapper.get(`${careForm} input[name="reasonCode"]`).element as HTMLInputElement).value).toBe('')
    expect(external.fetch).toHaveBeenCalledTimes(4)
  })
})
