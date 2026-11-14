// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useVotingSessions } from './useVotingSessions'

const mocks = vi.hoisted(() => ({ team: vi.fn(), org: vi.fn(), list: vi.fn(), create: vi.fn(), permissions: vi.fn(), error: vi.fn(), success: vi.fn() }))
const role = ref<string | null>('ADMIN')
const resolved = ref(true)
const scopeAdmin = ref(true)
const voteCapability = ref(true)
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.team }))
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization: mocks.org }))
mockNuxtImport('useVotingApi', () => () => ({ getSessions: mocks.list, createSession: mocks.create }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: role,
  roleResolved: resolved,
  roleLoadFailed: ref(false),
  isScopeAdmin: scopeAdmin,
  canCreateVoteSession: computed(() => resolved.value && voteCapability.value),
  loadPermissions: mocks.permissions,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const slug = ref('alpha-slug')
let state: ReturnType<typeof useVotingSessions>
function host(scope: 'TEAM' | 'ORGANIZATION' = 'TEAM') {
  return mount(defineComponent({ setup() { state = useVotingSessions(scope, slug); return () => null } }))
}
beforeEach(() => {
  vi.clearAllMocks()
  slug.value = 'alpha-slug'
  role.value = 'ADMIN'; resolved.value = true; scopeAdmin.value = true
  voteCapability.value = true
  mocks.team.mockResolvedValue({ data: { numericId: 42 } })
  mocks.org.mockResolvedValue({ data: { numericId: 71 } })
  mocks.permissions.mockResolvedValue({ ok: true })
  mocks.list.mockResolvedValue({ data: [], meta: { total: 0 } })
  mocks.create.mockResolvedValue({ data: { id: 8 } })
})

describe('投票一覧のスコープ契約', () => {
  it.each(['TEAM', 'ORGANIZATION'] as const)('%s slugを内部IDへ解決して取得する', async scope => {
    const wrapper = host(scope); await flushPromises()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith(scope === 'TEAM'
      ? { scope_type: 'TEAM', team_id: 42 } : { scope_type: 'ORGANIZATION', organization_id: 71 })
    expect(state.error.value).toBeNull()
    expect(state.sessions.value).toEqual([])
    wrapper.unmount()
  })
  it('実一覧DTOのmotionCountと人数0を保持する', async () => {
    mocks.list.mockResolvedValue({ data: [{ id: 1, title: '例', resolutionMode: 'WRITTEN', motionCount: 0, eligibleCount: 0 }], meta: { total: 1 } })
    const wrapper = host(); await flushPromises()
    expect(state.sessions.value[0]).toMatchObject({ motionCount: 0, eligibleCount: 0 })
    wrapper.unmount()
  })
  it('解決失敗は一覧APIを呼ばず、retryで全取得をやり直す', async () => {
    mocks.team.mockRejectedValueOnce(new Error('404'))
    const wrapper = host(); await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled(); expect(state.error.value).toBeTruthy()
    await state.load(); expect(state.error.value).toBeNull(); expect(mocks.list).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it.each(['403', '500', 'network'])('成功後の%s失敗は旧一覧と作成資格を残さない', async message => {
    const wrapper = host(); await flushPromises()
    mocks.list.mockRejectedValueOnce(new Error(message))
    await state.load()
    expect(state.error.value).toBeTruthy(); expect(state.sessions.value).toEqual([]); expect(state.canCreate.value).toBe(false)
    await state.load(); expect(state.error.value).toBeNull()
    wrapper.unmount()
  })
  it.each(['MEMBER', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN', null])('作成許可がない%sでは作成を送信しない', async value => {
    role.value = value; scopeAdmin.value = false
    voteCapability.value = false
    const wrapper = host(); await flushPromises()
    state.openCreateDialog(); await state.handleCreate()
    expect(state.canCreate.value).toBe(false); expect(state.showCreateDialog.value).toBe(false); expect(mocks.create).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('資格未確定では作成できない', async () => {
    resolved.value = false
    const wrapper = host(); await flushPromises()
    expect(state.canCreate.value).toBe(false); wrapper.unmount()
  })
  it.each(['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'])('作成許可を持つ%sは作成ダイアログを開ける', async value => {
    role.value = value; scopeAdmin.value = value === 'ADMIN'
    const wrapper = host(); await flushPromises()
    state.openCreateDialog()
    expect(state.canCreate.value).toBe(true); expect(state.showCreateDialog.value).toBe(true)
    wrapper.unmount()
  })
  it('scope資格取得失敗を空表示へ倒さない', async () => {
    mocks.permissions.mockResolvedValueOnce({ ok: false, error: new Error('permissions 500') })
    const wrapper = host(); await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled(); expect(state.error.value).toBeTruthy(); expect(state.canCreate.value).toBe(false)
    wrapper.unmount()
  })
  it('再試行も失敗したときエラーを維持する', async () => {
    mocks.list.mockRejectedValue(new Error('500'))
    const wrapper = host(); await flushPromises()
    await state.load()
    expect(state.error.value).toBeTruthy(); expect(state.canCreate.value).toBe(false)
    expect(mocks.team).toHaveBeenCalledTimes(2); expect(mocks.list).toHaveBeenCalledTimes(2)
    mocks.list.mockResolvedValue({ data: [], meta: { total: 0 } })
    wrapper.unmount()
  })
  it('開いた後にscope資格が消えた場合はdialogを閉じ送信しない', async () => {
    const wrapper = host(); await flushPromises(); state.openCreateDialog()
    state.form.title = '書面'; state.form.resolutionMode = 'WRITTEN'
    role.value = 'MEMBER'; scopeAdmin.value = false
    voteCapability.value = false
    await state.handleCreate()
    expect(state.showCreateDialog.value).toBe(false); expect(mocks.create).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it.each([undefined, 0, -1, Number.MAX_SAFE_INTEGER + 1])('内部ID%sは一覧へ渡さない', async numericId => {
    mocks.team.mockResolvedValueOnce({ data: { numericId } })
    const wrapper = host(); await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled(); expect(state.error.value).toBeTruthy(); wrapper.unmount()
  })
  it('別scopeへ切替中は以前の作成資格を使わず、遅い一覧を破棄する', async () => {
    let finish!: (value: { data: { id: number; title: string }[] }) => void
    mocks.list.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = host(); await flushPromises()
    expect(state.canCreate.value).toBe(false)
    slug.value = 'beta-slug'; await flushPromises()
    finish({ data: [{ id: 999, title: '旧スコープ' }] }); await flushPromises()
    expect(state.sessions.value).toEqual([])
    wrapper.unmount()
  })
  it('組織の作成先はorganizationIdのみを送る', async () => {
    const wrapper = host('ORGANIZATION'); await flushPromises(); state.openCreateDialog()
    state.form.title = '組織書面'; state.form.resolutionMode = 'WRITTEN'
    await state.handleCreate()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({ scopeType: 'ORGANIZATION', organizationId: 71, resolutionMode: 'WRITTEN' })
    expect(mocks.create.mock.calls[0]![0]).not.toHaveProperty('teamId')
    wrapper.unmount()
  })
  it('遅い旧scope応答と開いたdialogを新scopeへ持ち越さない', async () => {
    const wrapper = host(); await flushPromises()
    state.openCreateDialog()
    let finish!: (value: { data: { numericId: number } }) => void
    mocks.team.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const old = state.load()
    slug.value = 'beta-slug'; await flushPromises()
    expect(state.showCreateDialog.value).toBe(false)
    finish({ data: { numericId: 999 } }); await old
    expect(mocks.list.mock.calls.every(([query]) => query.team_id !== 999)).toBe(true)
    wrapper.unmount()
  })
  it('タイトル空/201文字・総会日未指定は送信せず200文字と選択暦日を送る', async () => {
    const wrapper = host(); await flushPromises(); state.openCreateDialog()
    await state.handleCreate()
    state.form.title = 'a'.repeat(201); state.form.meetingDate = new Date(2026, 10, 10)
    await state.handleCreate(); expect(mocks.create).not.toHaveBeenCalled()
    state.form.title = 'a'.repeat(200); state.form.meetingDate = null
    await state.handleCreate(); expect(mocks.create).not.toHaveBeenCalled()
    state.form.meetingDate = new Date(2026, 10, 10)
    await state.handleCreate()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({ scopeType: 'TEAM', teamId: 42, resolutionMode: 'MEETING', meetingDate: '2026-11-10', title: 'a'.repeat(200) })
    expect(mocks.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('書面決議でも空白のみのタイトルは送信しない', async () => {
    const wrapper = host(); await flushPromises(); state.openCreateDialog()
    state.form.resolutionMode = 'WRITTEN'; state.form.title = ' \t\n '
    await state.handleCreate()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(state.showCreateDialog.value).toBe(true)
    wrapper.unmount()
  })
  it('書面決議の有効なタイトルは前後の空白を除いて送信する', async () => {
    const wrapper = host(); await flushPromises(); state.openCreateDialog()
    state.form.resolutionMode = 'WRITTEN'; state.form.title = ' \t書面決議\n '
    await state.handleCreate()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0]).toMatchObject({ title: '書面決議', resolutionMode: 'WRITTEN' })
    wrapper.unmount()
  })
  it('書面決議は日付なし・失敗時は入力保持・saving中の二重送信なし', async () => {
    const wrapper = host(); await flushPromises(); state.openCreateDialog()
    state.form.title = '書面'; state.form.resolutionMode = 'WRITTEN'
    let fail!: (error: Error) => void
    mocks.create.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
    const first = state.handleCreate(); await state.handleCreate()
    expect(mocks.create).toHaveBeenCalledOnce()
    expect(mocks.create.mock.calls[0]![0].meetingDate).toBeUndefined()
    fail(new Error('500')); await first
    expect(state.showCreateDialog.value).toBe(true); expect(state.form.title).toBe('書面')
    wrapper.unmount()
  })
})
