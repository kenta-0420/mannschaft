// @vitest-environment nuxt
// 実ページとcommand memoryを使い、外部HTTP transportだけを置き換える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { useAuthStore } from '~/stores/useAuthStore'
import type { DiagnosisSession } from '~/types/ranch'
import DiagnosisPage from './diagnosis.vue'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn(), navigate: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useRoute', () => () => ({ query: { session: 'synthetic-session' } }))
mockNuxtImport('navigateTo', () => external.navigate)
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))

const wrappers: { unmount: () => void }[] = []
const mutations: { path: string; body: Record<string, unknown>; key: string | null }[] = []
let savedSession: DiagnosisSession
let completionTies: DiagnosisSession['tieQuestions']
let saveStatus: number
const base = '/api/v1/me/diagnoses/sessions/synthetic-session'
function session(): DiagnosisSession {
  return {
    id: 'synthetic-session', status: 'STARTED', version: '1', answerRevision: '0',
    questionnaireVersion: 'synthetic-v1', scoringVersion: 'synthetic-v1', resultId: null,
    questions: Array.from({ length: 24 }, (_, index) => ({
      id: `question-${index}`, axis: `axis-${Math.floor(index / 4)}`, polarity: 1,
      text: { ja: `質問${index + 1}` },
    })), answers: [], tieQuestions: [],
  }
}
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), {
  status, headers: { 'Content-Type': 'application/json' },
})
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  auth.$reset()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
  auth.setTokens('synthetic-access', 'synthetic-refresh')
  mutations.splice(0)
  external.fetch.mockReset()
  external.report.mockReset()
  external.navigate.mockReset().mockResolvedValue(undefined)
  savedSession = { ...session(), version: '2', answerRevision: '1' }
  completionTies = []
  saveStatus = 200
  external.fetch.mockImplementation(async (request, options) => {
    const path = new URL(String(request)).pathname
    if ((options?.method ?? 'GET') === 'GET' && path === base) return json(session())
    const body = JSON.parse(String(options?.body)) as Record<string, unknown>
    mutations.push({ path, body, key: new Headers(options?.headers).get('Idempotency-Key') })
    if (path === `${base}/answers`) {
      if (saveStatus !== 200) return json(null, saveStatus)
      savedSession = { ...savedSession, answers: body.answers as DiagnosisSession['answers'] }
      return json(savedSession)
    }
    if (path === `${base}/complete`) {
      if (completionTies.length && savedSession.status === 'STARTED') {
        savedSession = { ...savedSession, version: '3', status: 'TIE_BREAK_REQUIRED', tieQuestions: completionTies }
        return json(savedSession)
      }
      return json({ ...savedSession, status: 'COMPLETED', resultId: 'saved-result', tieQuestions: [] })
    }
    throw new Error('UNEXPECTED_SYNTHETIC_TRANSPORT')
  })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  useAuthStore().$reset()
  vi.restoreAllMocks()
})
async function answerAll() {
  const wrapper = await mountSuspended(DiagnosisPage)
  wrappers.push(wrapper)
  await flushPromises()
  for (let index = 0; index < 24; index++) {
    await wrapper.get(`input[type="radio"][name="question-${index}"][value="3"]`).setValue(true)
  }
  return wrapper
}
function resultButton(wrapper: Awaited<ReturnType<typeof answerAll>>) {
  const label = useNuxtApp().$i18n.t('ranch.diagnosis.complete')
  const button = wrapper.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('RESULT_BUTTON_MISSING')
  return button
}

describe('24問回答後の診断結果操作', () => {
  it('未保存の24回答を結果ボタン一回で保存し、保存された版で完了する', async () => {
    const wrapper = await answerAll()
    expect(resultButton(wrapper).attributes('disabled')).toBeUndefined()

    await resultButton(wrapper).trigger('click')
    await flushPromises()

    expect(mutations.map(item => item.path)).toEqual([`${base}/answers`, `${base}/complete`])
    expect(mutations[0]?.body.answers).toEqual(session().questions.map(question => ({ questionId: question.id, value: 3 })))
    expect(mutations[1]?.body).toEqual({ version: '2', answerRevision: '1', tieAnswers: [] })
    expect(external.navigate).toHaveBeenLastCalledWith('/my/ranch/results/saved-result')
  })

  it('保存が拒否されたときは完了を送らず、選んだ回答を保つ', async () => {
    saveStatus = 400
    const wrapper = await answerAll()

    await resultButton(wrapper).trigger('click')
    await flushPromises()

    expect(mutations.map(item => item.path)).toEqual([`${base}/answers`])
    expect(wrapper.findAll('input[type="radio"]:checked')).toHaveLength(24)
    expect(external.navigate).not.toHaveBeenCalledWith('/my/ranch/results/saved-result')
  })

  it('最初の完了応答で同点質問を案内し、選択後だけ最新の版で結果を完成する', async () => {
    const focus = vi.spyOn(HTMLElement.prototype, 'focus')
    const scroll = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    completionTies = [
      { axisId: 'axis-0', zero: { ja: '左の傾向' }, one: { ja: '右の傾向' } },
      { axisId: 'axis-1', zero: { ja: '別の左' }, one: { ja: '別の右' } },
    ]
    const wrapper = await answerAll()

    await resultButton(wrapper).trigger('click')
    await flushPromises()

    expect(mutations.map(item => item.path)).toEqual([`${base}/answers`, `${base}/complete`])
    expect(mutations[1]?.body).toEqual({ version: '2', answerRevision: '1', tieAnswers: [] })
    expect(external.navigate).not.toHaveBeenCalledWith('/my/ranch/results/saved-result')
    const notice = wrapper.get('[role="status"]')
    expect(notice.text()).toBe(useNuxtApp().$i18n.t('ranch.diagnosis.tie'))
    expect(notice.attributes('tabindex')).toBe('-1')
    expect(focus.mock.contexts).toContain(notice.element)
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(scroll.mock.contexts).toContain(notice.element)
    expect(scroll).toHaveBeenCalledWith({ behavior: 'instant', block: 'center' })
    expect(resultButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.get('input[name="axis-0"][value="0"]').setValue(true)
    expect(resultButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.get('input[name="axis-1"][value="1"]').setValue(true)
    await resultButton(wrapper).trigger('click')
    await flushPromises()

    expect(mutations.map(item => item.path)).toEqual([`${base}/answers`, `${base}/complete`, `${base}/complete`])
    expect(mutations[2]?.body).toEqual({ version: '3', answerRevision: '1', tieAnswers: [
      { axisId: 'axis-0', value: 0 }, { axisId: 'axis-1', value: 1 },
    ] })
    expect(external.navigate).toHaveBeenLastCalledWith('/my/ranch/results/saved-result')
  })

  it('保存応答が不明なときは同じpendingを残し、完了へ進まない', async () => {
    saveStatus = 500
    const wrapper = await answerAll()

    await resultButton(wrapper).trigger('click')
    await flushPromises()

    expect(mutations).toHaveLength(1)
    const pending = useNuxtApp().$ranchCommandMemory.scopes.diagnosis.pending.value
    expect(pending?.path).toBe(`${base}/answers`)
    expect(pending?.key).toBe(mutations[0]?.key)
    expect(resultButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('input[type="radio"]:checked')).toHaveLength(24)
  })
})
