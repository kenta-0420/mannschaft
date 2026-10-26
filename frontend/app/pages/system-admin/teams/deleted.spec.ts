// @vitest-environment nuxt
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ConfirmationService from 'primevue/confirmationservice'
import DeletedTeamsPage from './deleted.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { DeletedTeamSummary } from '~/composables/system-admin/useSystemAdminDashboard'

const external = vi.hoisted(() => ({
  fetch: vi.fn<typeof fetch>(),
  report: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
vi.mock('ofetch', async (importOriginal) => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({
  resolveApiBaseUrl: () => 'http://synthetic.invalid',
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: external.success,
    error: external.error,
    warn: vi.fn(),
    info: vi.fn(),
  }),
}))
mockNuxtImport('useErrorReport', () => () => ({
  capture: external.report,
  captureQuiet: external.report,
}))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))

const alpha: DeletedTeamSummary = {
  name: 'Alpha',
  slug: 'restore-alpha',
  deletedAt: '2036-01-01T00:00:00Z',
  lifecycleStatus: 'ACTIVE',
}
const beta: DeletedTeamSummary = {
  ...alpha,
  name: 'Beta',
  slug: 'restore-beta',
  lifecycleStatus: 'PROVISIONED',
}
const wrappers: { unmount: () => void }[] = []
const pending: ((response: Response) => void)[] = []
let listed: DeletedTeamSummary[]
let getFails: boolean
let holdGet: boolean
let holdPatch: boolean
let patchStatus: number
let patchReject: boolean
let total: number
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
const t = (key: string) => useNuxtApp().$i18n.t(key)
function pageData(page = 0) {
  return { data: listed, meta: { total, page, size: 20, totalPages: Math.ceil(total / 20) } }
}
beforeEach(async () => {
  await useNuxtApp().$i18n.setLocale('ja')
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  auth.$reset()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser({
    id: 1,
    email: 'synthetic@example.invalid',
    fullName: 'Synthetic',
    profileImageUrl: null,
    systemRole: 'SYSTEM_ADMIN',
  })
  auth.setTokens('synthetic-access', 'synthetic-refresh')
  external.fetch.mockReset()
  external.success.mockClear()
  external.error.mockClear()
  listed = [{ ...alpha }]
  total = 1
  getFails = holdGet = holdPatch = false
  patchStatus = 204
  patchReject = false
  external.fetch.mockImplementation(async (request, options) => {
    const url = new URL(String(request))
    if (url.pathname.endsWith('/dashboard/teams/deleted')) {
      if (holdGet) return new Promise<Response>((resolve) => pending.push(resolve))
      if (getFails)
        return json({ error: { code: 'COMMON_001', message: 'Synthetic GET failure' } }, 500)
      return json(pageData(Number(url.searchParams.get('page') ?? '0')))
    }
    if (url.pathname.endsWith('/restore') && options?.method === 'PATCH') {
      if (patchReject) throw new TypeError('Synthetic PATCH network failure')
      if (holdPatch) return new Promise<Response>((resolve) => pending.push(resolve))
      if (patchStatus !== 204)
        return json(
          {
            error: {
              code: patchStatus === 409 ? 'TEAM_006' : 'TEAM_001',
              message:
                patchStatus === 409
                  ? 'このチームは削除されていないため、復元できません'
                  : 'チームが見つかりません',
            },
          },
          patchStatus,
        )
      listed = []
      total = 0
      return new Response(null, { status: 204 })
    }
    throw new Error('UNEXPECTED_SYNTHETIC_TRANSPORT')
  })
})
afterEach(async () => {
  for (const resolve of pending.splice(0)) resolve(json(pageData()))
  await flushPromises()
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  useAuthStore().$reset()
  vi.restoreAllMocks()
})
async function mountPage() {
  const wrapper = await mountSuspended(DeletedTeamsPage, {
    attachTo: document.body,
    // Dialogはenter hookでEscape listenerを登録するため、transition stubでhookを省略しない。
    global: { plugins: [ConfirmationService], stubs: { transition: false } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function dialogButton(key: string): HTMLButtonElement {
  const dialog = document.querySelector('[role="alertdialog"]')
  const button = [...(dialog?.querySelectorAll('button') ?? [])].find(
    (el) => el.textContent?.trim() === t(key),
  )
  if (!button) throw new Error('CONFIRM_BUTTON_MISSING')
  return button as HTMLButtonElement
}
async function openConfirm(wrapper: Awaited<ReturnType<typeof mountPage>>, slug = alpha.slug) {
  await wrapper.get(`[data-testid="restore-${slug}"]`).trigger('click')
  await flushPromises()
}
async function accept() {
  dialogButton('deletedTeams.restore').click()
  await flushPromises()
}
const patchCalls = () =>
  external.fetch.mock.calls.filter(([, options]) => options?.method === 'PATCH')

describe('削除済チーム復元の実ページと外部HTTP境界', () => {
  it('SYS以外は一覧も復元UIも取得しない', async () => {
    useAuthStore().user!.systemRole = 'MEMBER'
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="restore-restore-alpha"]').exists()).toBe(false)
    expect(external.fetch).not.toHaveBeenCalled()
  })
  it('loadingと取得成功0件を区別する', async () => {
    holdGet = true
    const wrapper = await mountPage()
    expect(wrapper.text()).not.toContain(t('deletedTeams.empty'))
    holdGet = false
    listed = []
    total = 0
    pending.shift()!(json(pageData()))
    await flushPromises()
    expect(wrapper.text()).toContain(t('deletedTeams.empty'))
    expect(wrapper.find('[data-testid="deleted-teams-error"]').exists()).toBe(false)
  })
  it('GET失敗は空表示にせず同じ取得関数で再試行する', async () => {
    getFails = true
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="deleted-teams-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain(t('deletedTeams.empty'))
    getFails = false
    await wrapper.get('[data-testid="deleted-teams-error-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="restore-restore-alpha"]').exists()).toBe(true)
  })
  it('確認にはnameとslugを表示しキャンセルではPATCHしない', async () => {
    const wrapper = await mountPage()
    await openConfirm(wrapper)
    const dialog = document.querySelector('[role="alertdialog"]')!
    expect(dialog.textContent).toContain(alpha.name)
    expect(dialog.textContent).toContain(alpha.slug)
    dialogButton('deletedTeams.cancel').click()
    await flushPromises()
    expect(patchCalls()).toHaveLength(0)
  })
  it('選択slugへ一回だけPATCHし処理中は別行も無効化する', async () => {
    listed = [{ ...alpha }, { ...beta }]
    total = 2
    holdPatch = true
    const wrapper = await mountPage()
    await openConfirm(wrapper)
    await accept()
    expect(wrapper.get('[data-testid="restore-restore-beta"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="restore-restore-beta"]').trigger('click')
    expect(patchCalls()).toHaveLength(1)
    expect(String(patchCalls()[0]![0])).toContain(`/teams/${alpha.slug}/restore`)
    pending.shift()!(new Response(null, { status: 204 }))
    await flushPromises()
  })
  it.each(['Escape', 'close'])('%sで確認を閉じてもPATCHしない', async (action) => {
    const wrapper = await mountPage()
    await openConfirm(wrapper)
    const dialog = document.querySelector('[role="alertdialog"]')!
    if (action === 'Escape')
      document.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }),
      )
    else (dialog.querySelector('button[aria-label]') as HTMLButtonElement).click()
    await flushPromises()
    expect(patchCalls()).toHaveLength(0)
    await vi.waitFor(() => expect(document.querySelector('[role="alertdialog"]')).toBeNull())
  })
  it('204後は成功とACTIVEの導線を表示し再取得で行を除く', async () => {
    const wrapper = await mountPage()
    await openConfirm(wrapper)
    await accept()
    expect(external.success).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="restore-restore-alpha"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="restored-team-link"]').attributes('href')).toContain(
      alpha.slug,
    )
  })
  it('PROVISIONED成功には通常チームへの導線を出さない', async () => {
    listed = [{ ...beta }]
    const wrapper = await mountPage()
    await openConfirm(wrapper, beta.slug)
    await accept()
    expect(wrapper.find('[data-testid="restore-success"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="restored-team-link"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(t('deletedTeams.provisionedHint'))
  })
  it.each([409, 404])('%sは成功表示せず共通error後に一覧を再同期する', async (status) => {
    patchStatus = status
    const wrapper = await mountPage()
    const before = external.fetch.mock.calls.length
    await openConfirm(wrapper)
    await accept()
    expect(external.success).not.toHaveBeenCalled()
    expect(external.error).toHaveBeenCalledWith(
      t('dialog.error'),
      status === 409
        ? 'このチームは削除されていないため、復元できません'
        : 'チームが見つかりません',
    )
    expect(external.fetch.mock.calls.length).toBeGreaterThan(before + 1)
    expect(
      wrapper.get('[data-testid="restore-restore-alpha"]').attributes('disabled'),
    ).toBeUndefined()
  })
  it.each([
    { status: 409, message: 'This team cannot be restored because it has not been deleted.' },
    { status: 404, message: 'Team not found.' },
  ])('英語画面の$statusは英語で表示する', async ({ status, message }) => {
    await useNuxtApp().$i18n.setLocale('en')
    patchStatus = status
    const wrapper = await mountPage()
    await openConfirm(wrapper)
    await accept()
    expect(external.error).toHaveBeenLastCalledWith(t('dialog.error'), message)
    expect(external.success).not.toHaveBeenCalled()
  })
  it('204後のGET失敗は成功を維持しGETだけを再試行する', async () => {
    const wrapper = await mountPage()
    getFails = true
    await openConfirm(wrapper)
    await accept()
    expect(wrapper.find('[data-testid="restore-success"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="deleted-teams-error"]').exists()).toBe(true)
    expect(external.success).toHaveBeenCalledTimes(1)
    getFails = false
    await wrapper.get('[data-testid="deleted-teams-error-retry"]').trigger('click')
    await flushPromises()
    expect(patchCalls()).toHaveLength(1)
    expect(wrapper.text()).toContain(t('deletedTeams.empty'))
  })
  it.each(['server', 'network'])(
    'PATCHの%s失敗は成功にせず再確認して復元できる',
    async (failure) => {
      patchStatus = 500
      patchReject = failure === 'network'
      const wrapper = await mountPage()
      await openConfirm(wrapper)
      await accept()
      expect(external.success).not.toHaveBeenCalled()
      expect(external.error).toHaveBeenCalled()
      expect(
        wrapper.get('[data-testid="restore-restore-alpha"]').attributes('disabled'),
      ).toBeUndefined()
      patchStatus = 204
      patchReject = false
      await openConfirm(wrapper)
      await accept()
      expect(external.success).toHaveBeenCalledTimes(1)
      expect(wrapper.find('[data-testid="restore-success"]').exists()).toBe(true)
      expect(patchCalls()).toHaveLength(2)
    },
  )
  it('最終頁の最後の行を復元したら有効頁へ補正して取得する', async () => {
    total = 21
    const wrapper = await mountPage()
    // PrimeVueのページ番号を操作し、画面のページイベントをそのまま通す。
    const paginator = wrapper.findComponent({ name: 'Paginator' })
    paginator.vm.$emit('page', { page: 1, rows: 20 })
    await flushPromises()
    await openConfirm(wrapper)
    await accept()
    const requests = external.fetch.mock.calls.map(([request]) => String(request))
    expect(requests.at(-1)).toContain('page=0')
    expect(requests.at(-2)).toContain('page=1')
  })
})
