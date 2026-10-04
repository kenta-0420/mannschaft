// @vitest-environment nuxt
// 実Pinia/ofetch/管理API/本人世代を通し、外部HTTPだけ合成。HTTP管理資格や実機の証明ではない。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '~/stores/useAuthStore'
import { useRanchAdminApi } from '~/composables/useRanchAdminApi'
import type { RanchCareRulePublicationRequest, RanchPolicyPublicationRequest } from '~/types/ranch-admin'
import AdminPage from '~/pages/system-admin/ranch.vue'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
 const original = await importOriginal<typeof import('ofetch')>()
 return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const eventId = '11111111-1111-4111-8111-111111111111'
const posts: { key: string; body: string; token: string | null }[] = []
const scopes: ReturnType<typeof effectScope>[] = []
const wrappers: VueWrapper[] = []
let mode: 'success' | 'lost' | 'reject' | 'preflight-reject' | 'unknown-503' | 'old-error' | 'health-delay' = 'success'
let releaseA: (() => void) | null = null
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
const rejected = () => new Response(JSON.stringify({ error: { code: 'RANCH_001', message: 'Synthetic rejection' } }), { status: 404, headers: { 'Content-Type': 'application/json' } })
async function account(id: number) {
 const auth = useAuthStore()
 await auth.setUser({ id, email: `synthetic${id}@example.invalid`, fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens(id === 1 ? 'A-access' : 'B-access', 'synthetic-refresh')
}
async function currentApi() {
 const scope = effectScope(); scopes.push(scope)
 const api = await useNuxtApp().runWithContext(() => scope.run(() => useRanchAdminApi()))
 if (!api) throw new Error('SCOPE_NOT_ACTIVE')
 return api
}
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 auth.$reset(); await account(1)
 posts.length = 0; mode = 'success'; releaseA = null
 external.fetch.mockReset(); external.report.mockReset()
 external.fetch.mockImplementation(async (request, options) => {
  const path = new URL(String(request)).pathname
  const token = new Headers(options?.headers).get('Authorization')
  if (options?.method === 'POST' && (path.endsWith('/care-rules') || path.endsWith('/policies'))) {
   posts.push({ key: new Headers(options.headers).get('Idempotency-Key') ?? '', body: String(options.body), token })
   const body = JSON.parse(String(options.body)) as RanchCareRulePublicationRequest | RanchPolicyPublicationRequest
   return json({ id: eventId, version: '1', contentHash: 'a'.repeat(64), effectiveAt: body.effectiveAt, settings: body, publishedAt: '2026-10-05T00:00:00Z', publishedBy: '1' })
  }
  if (options?.method === 'POST' && path.endsWith('/retry')) {
   posts.push({ key: new Headers(options.headers).get('Idempotency-Key') ?? '', body: String(options.body), token })
   if (mode === 'lost' && posts.length === 1) throw new TypeError('SYNTHETIC_ACK_LOST')
   if (mode === 'reject') return rejected()
   if (mode === 'preflight-reject' || mode === 'unknown-503') return new Response(JSON.stringify({ error: { code: mode === 'preflight-reject' ? 'RANCH_004' : 'SERVICE_UNAVAILABLE', message: 'Synthetic unavailable' } }), { status: 503, headers: { 'Content-Type': 'application/json' } })
   if (mode === 'old-error') {
    if (token === 'Bearer A-access') { await new Promise<void>(resolve => { releaseA = resolve }); return rejected() }
    throw new TypeError('SYNTHETIC_B_ACK_UNKNOWN')
   }
   return json({ commandId: '22222222-2222-4222-8222-222222222222', sourceType: 'ATTENDANCE_RESPONSE', eventId, disposition: 'RETRY_SCHEDULED', completedAt: '2026-10-05T00:00:00Z' })
  }
  if (path.endsWith('/operational-controls')) return json({ version: '0', isCareEnabled: false, isShopEnabled: false, isDeliveryPaused: true, isRewardsPaused: false, updatedAt: '2026-10-05T00:00:00Z' })
  if (path.endsWith('/outbox-health')) {
   if (mode === 'health-delay') { await new Promise<void>(resolve => { releaseA = resolve }); return rejected() }
   return json({ sources: ['ATTENDANCE_RESPONSE', 'TIMELINE_ORIGINAL', 'BLOG_FIRST_PUBLISH', 'PERSONAL_RECALL_COMPLETE'].map(sourceType => ({ sourceType, pendingCount: '0', deadCount: '0', oldestAgeSeconds: null })), observedAt: '2026-10-05T00:00:00Z' })
  }
  throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
 })
})
afterEach(async () => {
 releaseA?.(); await flushPromises()
 for (const wrapper of wrappers.splice(0)) wrapper.unmount()
 for (const scope of scopes.splice(0)) scope.stop()
 vi.restoreAllMocks()
})
describe('管理命令とhealthの有限・本人境界（未実測）', () => {
 it('応答不明だけ同key/bodyを保持し再送する', async () => {
  mode = 'lost'
  const api = await currentApi()
  await expect(api.retrySource('ATTENDANCE_RESPONSE', eventId, 'MANUAL_RETRY')).rejects.toThrow()
  expect(api.command.pending.value).not.toBeNull()
  await api.retryPending()
  expect(posts).toHaveLength(2)
  expect(posts[1]).toEqual(posts[0])
  expect(api.command.pending.value).toBeNull()
 })
 it('確定404は解除し、新しい操作は別keyで受け付ける', async () => {
  mode = 'reject'
  const api = await currentApi()
  await expect(api.retrySource('ATTENDANCE_RESPONSE', eventId, 'MANUAL_RETRY')).rejects.toThrow()
  expect(api.command.pending.value).toBeNull()
  mode = 'success'
  await api.retrySource('ATTENDANCE_RESPONSE', eventId, 'AFTER_CHECK')
  expect(posts[1]?.key).not.toBe(posts[0]?.key)
 })
 it('保存前の構造化RANCH_004/503だけ解除し、修正した新操作を受け付ける', async () => {
  mode = 'preflight-reject'
  const api = await currentApi()
  await expect(api.retrySource('ATTENDANCE_RESPONSE', eventId, 'MANUAL_RETRY')).rejects.toThrow()
  expect(api.command.pending.value).toBeNull()
  mode = 'success'
  await api.retrySource('ATTENDANCE_RESPONSE', eventId, 'AFTER_CHECK')
  expect(posts[1]?.key).not.toBe(posts[0]?.key)
 })
 it('一般503は保存結果不明のまま同key/bodyで再送する', async () => {
  mode = 'unknown-503'
  const api = await currentApi()
  await expect(api.retrySource('ATTENDANCE_RESPONSE', eventId, 'MANUAL_RETRY')).rejects.toThrow()
  expect(api.command.pending.value).not.toBeNull()
  mode = 'success'
  await api.retryPending()
  expect(posts[1]).toEqual(posts[0])
 })
 it('旧Aの確定error/finallyはBの不明命令を捨てず旧画面はB資格で再送しない', async () => {
  mode = 'old-error'
  const a = await currentApi()
  const old = a.retrySource('ATTENDANCE_RESPONSE', eventId, 'MANUAL_RETRY').catch((error: unknown) => error)
  await flushPromises(); expect(releaseA).not.toBeNull()
  await account(2)
  const b = await currentApi()
  await expect(b.retrySource('ATTENDANCE_RESPONSE', eventId, 'B_RETRY')).rejects.toThrow()
  const pending = b.command.pending.value
  expect(pending).not.toBeNull()
  releaseA?.()
  const oldResult = await old
  if (!(oldResult instanceof Error)) throw new Error('OLD_OPERATION_WAS_NOT_REJECTED')
  expect(oldResult.message).toBe('COMMAND_ACCOUNT_CHANGED')
  expect(b.command.pending.value).toBe(pending)
  await expect(a.retryPending()).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  expect(posts).toHaveLength(2)
  expect(posts.map(post => post.token)).toEqual(['Bearer A-access', 'Bearer B-access'])
 })
 it('health未取得や失敗を件数0の成功へ変換しない', async () => {
  mode = 'health-delay'
  const wrapper = await mountSuspended(AdminPage); wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.admin.pending'))
  releaseA?.(); await flushPromises()
  expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.admin.pending'))
 })
 it('お世話の成長境界を確認しBIGINT入力を文字列のまま明示公開する', async () => {
  const wrapper = await mountSuspended(AdminPage); wrappers.push(wrapper)
  await flushPromises()
  const form = wrapper.get('form[aria-labelledby="ranch-admin-care-publication-heading"]')
  for (const [name, value] of Object.entries({ effectiveAt: '2031-01-06T00:00:00Z', amountXp: '20', weeklyCapXp: '100', juvenileXp: '9007199254740993', adultXp: '9007199254740993', reasonCode: 'CARE_NEXT_WEEK' })) await form.get(`input[name="${name}"]`).setValue(value)
  await form.trigger('submit'); await flushPromises()
  expect(posts).toHaveLength(0)
  await form.get('input[name="adultXp"]').setValue('9007199254740994')
  await form.trigger('submit')
  await vi.waitFor(() => expect(posts).toHaveLength(1))
  const posted = posts[0]
  if (!posted) throw new Error('CARE_PUBLICATION_NOT_SENT')
  const saved = JSON.parse(posted.body) as RanchCareRulePublicationRequest
  expect(saved.juvenileXp).toBe('9007199254740993')
  expect(saved.adultXp).toBe('9007199254740994')
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.admin.publicationSaved', { version: '1', effectiveAt: saved.effectiveAt }))
 })
 it('停止policyも正の値を要求しPERSONAL OFFとBIGINT上限を正準送信する', async () => {
  const wrapper = await mountSuspended(AdminPage); wrappers.push(wrapper)
  await flushPromises()
  const form = wrapper.get('form[aria-labelledby="ranch-admin-policy-publication-heading"]')
  const values = { effectiveAt: '2031-01-06T00:00:00Z', globalWeeklyCap: '0', batchSize: '1', leaseSeconds: '5', maxAttempts: '2', initialBackoffSeconds: '1', maxBackoffSeconds: '3', reasonCode: 'DISABLED_NEXT_WEEK' }
  for (const [name, value] of Object.entries(values)) await form.get(`input[name="${name}"]`).setValue(value)
  for (const source of ['ATTENDANCE_RESPONSE', 'TIMELINE_ORIGINAL', 'BLOG_FIRST_PUBLISH', 'PERSONAL_RECALL_COMPLETE']) {
   await form.get(`input[name="${source}.amountPoints"]`).setValue('4')
   await form.get(`input[name="${source}.countLimit"]`).setValue('25')
  }
  await form.trigger('submit'); await flushPromises()
  expect(posts).toHaveLength(0)
  await form.get('input[name="globalWeeklyCap"]').setValue('9223372036854775807')
  await form.trigger('submit')
  await vi.waitFor(() => expect(posts).toHaveLength(1))
  const posted = posts[0]
  if (!posted) throw new Error('POLICY_PUBLICATION_NOT_SENT')
  const saved = JSON.parse(posted.body) as RanchPolicyPublicationRequest
  expect(saved.globalWeeklyCap).toBe('9223372036854775807')
  expect(saved.enabled).toBe(false)
  expect(saved.sources).toHaveLength(4)
  expect(saved.sources.find(source => source.sourceType === 'PERSONAL_RECALL_COMPLETE')).toEqual({ sourceType: 'PERSONAL_RECALL_COMPLETE', enabled: false, amountPoints: '4', countLimit: 25 })
  expect(saved.delivery).toEqual({ batchSize: 1, leaseSeconds: 5, maxAttempts: 2, initialBackoffSeconds: 1, maxBackoffSeconds: 3 })
 })
})
