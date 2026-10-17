// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Toast from 'primevue/toast'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import settings from '~/locales/en/settings.json'
import common from '~/locales/en/common.json'
import SettingsDataExportSection from './SettingsDataExportSection.vue'

dayjs.extend(utc)
dayjs.extend(timezone)
const state = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => state.api)
const STATUS = '/api/v1/account/data-export/status'
const REQUEST = '/api/v1/account/data-export'
const DOWNLOAD = '/api/v1/account/data-export/download'
const completed = {
  exportId: 1, status: 'COMPLETED', progressPercent: 100, currentStep: 'completed',
  fileSizeBytes: 0, expiresAt: '2026-10-18T12:00:00', createdAt: '2026-10-17T12:00:00',
}
const processing = { ...completed, status: 'PROCESSING', progressPercent: 20, currentStep: null, fileSizeBytes: null, expiresAt: null }
const businessError = (code: string, statusCode = 400) => ({ statusCode, data: { error: { code } } })

async function mountExport() {
  const wrapper = mount(defineComponent({
    components: { SettingsDataExportSection, Toast },
    template: '<SettingsDataExportSection /><Toast />',
  }), {
    attachTo: document.body,
    global: { plugins: [createPinia(), PrimeVue, ToastService, createI18n({
      legacy: false, locale: 'en', messages: { ja: {}, en: { ...common, ...settings }, zh: {}, ko: {}, es: {}, de: {} },
    })] },
  })
  await flushPromises()
  return wrapper
}

type ExportWrapper = Awaited<ReturnType<typeof mountExport>>
function button(wrapper: ExportWrapper, label: string) {
  const found = wrapper.findAll('button').find(item => item.text() === label)
  expect(found, label).toBeDefined()
  return found!
}
function calls(url: string) { return state.api.mock.calls.filter(([path]) => path === url) }

describe('個人データ出力の復帰と操作別案内', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    state.api.mockReset().mockResolvedValue({ data: completed })
  })
  afterEach(() => { vi.useRealTimers() })

  it('初期表示と再mountで本人latestをGET1回だけ取得し、完了と0KBを復元する', async () => {
    const wrapper = await mountExport()
    expect(calls(STATUS)).toHaveLength(1)
    expect(calls(REQUEST)).toHaveLength(0)
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(wrapper.text()).toContain('0.0 KB')
    wrapper.unmount()
    state.api.mockClear()
    const restored = await mountExport()
    expect(button(restored, 'Download').exists()).toBe(true)
    expect(calls(STATUS)).toHaveLength(1)
    expect(calls(REQUEST)).toHaveLength(0)
  })

  it('GDPR_003だけを正常な未作成として要求可能にする', async () => {
    state.api.mockRejectedValue(businessError('GDPR_003', 404))
    const wrapper = await mountExport()
    expect(calls(STATUS)).toHaveLength(1)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(button(wrapper, 'Export Personal Data').attributes('disabled')).toBeUndefined()
    expect(calls(REQUEST)).toHaveLength(0)
  })

  it('完了済みでもnullファイルサイズと期限は未設定として表示する', async () => {
    state.api.mockResolvedValue({ data: { ...completed, fileSizeBytes: null, expiresAt: null } })
    const wrapper = await mountExport()
    expect(wrapper.text()).toContain('Download expires: -')
    expect(wrapper.text()).not.toContain('0.0 KB')
    expect(button(wrapper, 'Download').exists()).toBe(true)
  })

  it('正常な手動要求はPOST一度だけ送り本人latestからpollし、downloadは既存URLを開く', async () => {
    state.api.mockRejectedValueOnce(businessError('GDPR_003', 404))
    const wrapper = await mountExport()
    let finishPost!: () => void
    state.api.mockImplementationOnce(() => new Promise<void>(resolve => { finishPost = resolve }))
      .mockResolvedValueOnce({ data: processing })
    await button(wrapper, 'Export Personal Data').trigger('click')
    await button(wrapper, 'Export Personal Data').trigger('click')
    expect(calls(REQUEST)).toHaveLength(1)
    expect(calls(REQUEST)[0]?.[1]).toEqual({ method: 'POST', body: {} })
    finishPost()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    state.api.mockResolvedValueOnce({ data: { url: 'https://example.test/export.zip' } })
    await button(wrapper, 'Download').trigger('click')
    await flushPromises()
    expect(open).toHaveBeenCalledWith('https://example.test/export.zip', '_blank')
    open.mockRestore()
  })

  it.each([
    ['403', businessError('FORBIDDEN', 403)], ['503', { statusCode: 503 }],
    ['通信断', new TypeError('Failed to fetch')], ['null payload', null],
  ])('%sを未作成と混同せず同じGETのretryで復元する', async (_name, error) => {
    if (error === null) state.api.mockResolvedValueOnce({ data: null })
    else state.api.mockRejectedValueOnce(error)
    const wrapper = await mountExport()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(calls(REQUEST)).toHaveLength(0)
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(calls(STATUS)).toHaveLength(2)
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
  })

  it.each(['PENDING', 'PROCESSING'])('%sのpollを再開し完了後は停止する', async (status) => {
    state.api.mockResolvedValueOnce({ data: { ...processing, status } })
    const wrapper = await mountExport()
    expect(button(wrapper, 'Export Personal Data').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    expect(button(wrapper, 'Download').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(30000)
    expect(calls(STATUS)).toHaveLength(2)
    expect(calls(REQUEST)).toHaveLength(0)
  })

  it('遅いpollを重複させずunmount後の完了応答でもtimerを再開しない', async () => {
    let resolvePoll!: (value: { data: typeof processing }) => void
    state.api.mockResolvedValueOnce({ data: processing }).mockImplementationOnce(() => new Promise(resolve => { resolvePoll = resolve }))
    const wrapper = await mountExport()
    await vi.advanceTimersByTimeAsync(30000)
    expect(calls(STATUS)).toHaveLength(2)
    wrapper.unmount()
    resolvePoll({ data: processing })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30000)
    expect(calls(STATUS)).toHaveLength(2)
  })

  it('初回GETの遅延応答がunmount後に返ってもpollを開始しない', async () => {
    let resolveGet!: (value: { data: typeof processing }) => void
    state.api.mockImplementationOnce(() => new Promise(resolve => { resolveGet = resolve }))
    const wrapper = await mountExport()
    expect(calls(STATUS)).toHaveLength(1)
    wrapper.unmount()
    resolveGet({ data: processing })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30000)
    expect(calls(STATUS)).toHaveLength(1)
  })

  it('正式FAILEDをprogress100とcompleted stepより優先しDownloadもpollも出さない', async () => {
    state.api.mockResolvedValue({ data: { ...completed, status: 'FAILED', fileSizeBytes: null } })
    const wrapper = await mountExport()
    expect(wrapper.text()).toContain('Export failed')
    expect(wrapper.findAll('button').some(item => item.text() === 'Download')).toBe(false)
    await vi.advanceTimersByTimeAsync(30000)
    expect(calls(STATUS)).toHaveLength(1)
  })

  it.each([400, 429])('24時間制限HTTP%sでもcodeに合う案内と既存Downloadを保つ', async (http) => {
    const wrapper = await mountExport()
    state.api.mockRejectedValueOnce(businessError('GDPR_001', http))
    await button(wrapper, 'Export Personal Data').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('once every 24 hours')
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(calls(REQUEST)).toHaveLength(1)
    expect(calls(STATUS)).toHaveLength(1)
  })

  it('処理中制限は本人latestを再同期し二重POSTせずpollを再開する', async () => {
    const wrapper = await mountExport()
    state.api.mockRejectedValueOnce(businessError('GDPR_002', 409)).mockResolvedValueOnce({ data: processing })
    await button(wrapper, 'Export Personal Data').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('already in progress')
    expect(calls(STATUS)).toHaveLength(2)
    expect(button(wrapper, 'Export Personal Data').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    expect(calls(STATUS)).toHaveLength(3)
    expect(calls(REQUEST)).toHaveLength(1)
  })

  it('POST失敗も次のGET失敗も既存statusを消さず、要求用案内と取得retryを区別する', async () => {
    const wrapper = await mountExport()
    state.api.mockRejectedValueOnce({ statusCode: 503 })
    await button(wrapper, 'Export Personal Data').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Failed to request data export')
    expect(button(wrapper, 'Download').exists()).toBe(true)
    state.api.mockResolvedValueOnce({}).mockRejectedValueOnce({ statusCode: 503 })
    await button(wrapper, 'Export Personal Data').trigger('click')
    await flushPromises()
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(wrapper.find('[data-testid="load-error-state-retry"]').exists()).toBe(true)
  })

  it('処理中制限の再同期が失敗しても取得成功と案内せず既存状態とretryを保つ', async () => {
    const wrapper = await mountExport()
    state.api.mockRejectedValueOnce(businessError('GDPR_002', 409)).mockRejectedValueOnce({ statusCode: 503 })
    await button(wrapper, 'Export Personal Data').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Please wait for it to complete')
    expect(document.body.textContent).not.toContain('has been refreshed')
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(wrapper.find('[data-testid="load-error-state-retry"]').exists()).toBe(true)
    expect(calls(REQUEST)).toHaveLength(1)
    expect(calls(STATUS)).toHaveLength(2)
  })

  it.each([
    ['GDPR_009', 'not ready for download'], ['GDPR_010', 'download has expired'],
    ['OTHER', 'Failed to download export'],
  ])('download %sに操作に合う案内を出して既存statusを保つ', async (code, message) => {
    const wrapper = await mountExport()
    state.api.mockRejectedValueOnce(businessError(code, code === 'GDPR_010' ? 410 : 409))
    await button(wrapper, 'Download').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(message)
    expect(button(wrapper, 'Download').exists()).toBe(true)
    expect(calls(DOWNLOAD)).toHaveLength(1)
    expect(calls(REQUEST)).toHaveLength(0)
  })
})
