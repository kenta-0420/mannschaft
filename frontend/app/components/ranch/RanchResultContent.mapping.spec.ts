// @vitest-environment nuxt
// 保存結果画面と実API composableを通し、外部HTTP境界だけを差し替える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '~/stores/useAuthStore'
import type { DiagnosisResult, RanchState } from '~/types/ranch'
import RanchResultContent from './RanchResultContent.vue'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async original => {
  const module = await original<typeof import('ofetch')>()
  return { ...module, ofetch: module.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useRoute', () => () => ({ params: { resultId: 'saved-result' } }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const wrappers: VueWrapper[] = []
let result: DiagnosisResult, state: RanchState, failState: boolean
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  auth.$reset()
  await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
  auth.setTokens('synthetic-access', 'synthetic-refresh')
  result = { id: 'saved-result', method: 'DIAGNOSIS', completedAt: '2026-11-08T00:00:00Z', resultSchemaVersion: '2',
    mappingVersion: 'saved-mapping-version', speciesKey: 'allosaurus', variantKey: 'V1', catalogVersion: '20261108',
    descriptionSnapshot: { ja: '保存された診断説明' }, mappingDescriptionSnapshot: { ja: '当時のアロサウルス V1 の説明' } }
  state = { approvedVisualsAvailable: false, featureStatus: 'UNAVAILABLE', deliveryPaused: true, rewardsStatus: 'DISABLED', shopAvailable: false,
    owner: { id: 'owner', status: 'PAUSED', balance: '0', version: '1' },
    dinosaur: { id: '11111111-1111-4111-8111-111111111111', speciesKey: null, variantKey: null, habitat: 'LAND', stage: 'EGG', name: null,
      xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: null },
    settings: null, roomSlots: [], serverTime: '2026-11-08T00:00:00Z', policyVersion: null, careBudget: null, weekBudget: null,
    assignment: { availableMethods: ['DIAGNOSIS'], selectionConfirmed: false, confirmedMethod: null } }
  failState = false
  external.fetch.mockReset().mockImplementation(async request => {
    const pathname = new URL(String(request)).pathname
    if (pathname === '/api/v1/me/diagnoses/results/saved-result') return json(result)
    if (pathname === '/api/v1/me/ranch') return json(state, failState ? 503 : 200)
    throw new Error('UNEXPECTED_RESULT_TRANSPORT ' + pathname)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  useAuthStore().$reset()
  vi.restoreAllMocks()
})
async function mountResult() {
  const wrapper = await mountSuspended(RanchResultContent)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
const hasUseButton = (wrapper: VueWrapper) => wrapper.findAll('button').some(button => button.text() === useNuxtApp().$i18n.t('ranch.assignment.useResult'))
describe('結果に保存した正式対応の閲覧と新割当', () => {
  it('公開停止・休止・残高0でも当時の保存説明を閲覧できる', async () => {
    const wrapper = await mountResult()
    expect(wrapper.text()).toContain('当時のアロサウルス V1 の説明')
    expect(wrapper.text()).toContain('保存された診断説明')
    expect(hasUseButton(wrapper)).toBe(false)
  })
  it('legacyは閲覧を保ち新割当ボタンを出さず新結果作成へ案内する', async () => {
    result.mappingVersion = null
    state.approvedVisualsAvailable = true
    const wrapper = await mountResult()
    expect(wrapper.text()).toContain('保存された診断説明')
    expect(hasUseButton(wrapper)).toBe(false)
    expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.diagnosisResults.legacyMapping'))
    expect(wrapper.find('a[href="/my/ranch/diagnosis"]').exists()).toBe(true)
  })
  it('正式identity・明示公開・割当可用性が揃ったときだけ新割当を表示する', async () => {
    state.approvedVisualsAvailable = true
    const wrapper = await mountResult()
    expect(hasUseButton(wrapper)).toBe(true)
  })
  it('選定が確定済みの卵では新しい結果から再割当の導線を出さない', async () => {
    state.approvedVisualsAvailable = true
    state.assignment!.selectionConfirmed = true
    const wrapper = await mountResult()
    expect(wrapper.text()).toContain('当時のアロサウルス V1 の説明')
    expect(hasUseButton(wrapper)).toBe(false)
    expect(wrapper.find('a[href="/my/ranch/birth-profile"]').exists()).toBe(false)
  })
  it('state取得失敗でも保存結果は残り新割当を有効にしない', async () => {
    state.approvedVisualsAvailable = true
    failState = true
    const wrapper = await mountResult()
    expect(wrapper.text()).toContain('当時のアロサウルス V1 の説明')
    expect(hasUseButton(wrapper)).toBe(false)
  })
})
