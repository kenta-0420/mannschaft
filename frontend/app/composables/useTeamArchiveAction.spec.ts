// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount } from '@vue/test-utils'
import { useTeamArchiveAction } from './useTeamArchiveAction'
import type { ConfirmationOptions } from 'primevue/confirmationoptions'

const mocks = vi.hoisted(() => ({ api: vi.fn(), archive: vi.fn(), unarchive: vi.fn(),
  require: vi.fn(), close: vi.fn(), success: vi.fn(), error: vi.fn(), handleError: vi.fn(), fetchMyTeams: vi.fn() }))
vi.mock('primevue/useconfirm', async (original) => ({
  ...await original<typeof import('primevue/useconfirm')>(),
  useConfirm: () => ({ require: mocks.require, close: mocks.close }),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useTeamApi', () => () => ({ archiveTeam: mocks.archive, unarchiveTeam: mocks.unarchive }))
mockNuxtImport('useTeamStore', () => () => ({ fetchMyTeamsWithResult: mocks.fetchMyTeams }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, error: mocks.error }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleError }))

const slug = ref('team-a')
const archived = ref(false)
const refreshed = vi.fn()
let action: ReturnType<typeof useTeamArchiveAction>
const Host = defineComponent({ setup() { action = useTeamArchiveAction(slug, archived, refreshed); return () => null } })
function target(role = 'ADMIN', capability?: boolean) {
  return { slug: 'team-a', role, isArchived: archived.value, canManageArchive: capability }
}
async function setup(role = 'ADMIN', capability?: boolean) {
  mocks.api.mockResolvedValue({ data: [target(role, capability)] })
  const wrapper = mount(Host)
  await flushPromises()
  return wrapper
}
function dialog(): ConfirmationOptions { return mocks.require.mock.calls.at(-1)![0] }

beforeEach(() => {
  vi.clearAllMocks()
  slug.value = 'team-a'
  archived.value = false
  refreshed.mockResolvedValue(undefined)
  mocks.fetchMyTeams.mockResolvedValue({ ok: true })
})

describe('チームアーカイブ操作', () => {
  it.each([['ADMIN', undefined, true], ['DEPUTY_ADMIN', undefined, true], ['ADMIN', false, false], ['MEMBER', undefined, false],
    ['SYSTEM_ADMIN', undefined, false], ['SYSTEM_ADMIN', true, true], ['SYSTEM_ADMIN', false, false]])(
    '表示ロール%sと実操作フラグ%sで入口を判定する', async (role, capability, visible) => {
      const wrapper = await setup(role as string, capability as boolean | undefined)
      expect(action.available.value).toBe(visible)
      wrapper.unmount()
    })

  it('確認前とキャンセル後は更新しない', async () => {
    const wrapper = await setup()
    action.request()
    expect(mocks.close).toHaveBeenCalledOnce()
    expect(mocks.close.mock.invocationCallOrder[0]).toBeLessThan(mocks.require.mock.invocationCallOrder[0]!)
    expect(dialog().group).toBe('team-archive')
    expect(mocks.archive).not.toHaveBeenCalled()
    dialog().reject?.()
    expect(mocks.archive).not.toHaveBeenCalled()
    expect(action.busy.value).toBe(false)
    wrapper.unmount()
  })
  it('成功後に正規データを再取得する処理を待って通知する', async () => {
    const wrapper = await setup()
    action.request()
    await dialog().accept?.()
    expect(mocks.archive).toHaveBeenCalledExactlyOnceWith('team-a')
    expect(refreshed).toHaveBeenCalledOnce()
    expect(mocks.fetchMyTeams).toHaveBeenCalledOnce()
    expect(mocks.success).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it('解除はunarchive APIを呼ぶ', async () => {
    archived.value = true
    const wrapper = await setup()
    action.request()
    await dialog().accept?.()
    expect(mocks.unarchive).toHaveBeenCalledExactlyOnceWith('team-a')
    expect(mocks.archive).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('解除のキャンセルは更新しない', async () => {
    archived.value = true
    const wrapper = await setup()
    action.request()
    dialog().reject?.()
    expect(mocks.unarchive).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('失敗時は状態を変えず共通エラーを表示する', async () => {
    const wrapper = await setup()
    mocks.archive.mockRejectedValueOnce(new Error('403'))
    action.request()
    await dialog().accept?.()
    expect(archived.value).toBe(false)
    expect(refreshed).not.toHaveBeenCalled()
    expect(mocks.handleError).toHaveBeenCalledOnce()
    expect(mocks.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('二重確認・二重accept・ダイアログ非表示でも送信中はロックする', async () => {
    const wrapper = await setup()
    let complete!: () => void
    mocks.archive.mockImplementationOnce(() => new Promise<void>(resolve => { complete = resolve }))
    action.request()
    action.request()
    expect(mocks.require).toHaveBeenCalledOnce()
    const first = dialog().accept?.()
    await flushPromises()
    dialog().onHide?.()
    expect(action.busy.value).toBe(true)
    await dialog().accept?.()
    expect(mocks.archive).toHaveBeenCalledOnce()
    complete()
    await first
    wrapper.unmount()
  })
  it('確認中のslug切替は旧チームを更新しない', async () => {
    const wrapper = await setup()
    action.request()
    slug.value = 'team-b'
    await flushPromises()
    await dialog().accept?.()
    expect(mocks.archive).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('確認中の権限剥奪は更新しない', async () => {
    const wrapper = await setup()
    action.request()
    mocks.api.mockResolvedValueOnce({ data: [target('MEMBER', false)] })
    await dialog().accept?.()
    expect(mocks.archive).not.toHaveBeenCalled()
    expect(mocks.error).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it.each([false, true])('更新成立後のunmountでも共通所属一覧を同期する（解除=%s）', async (isArchived) => {
    archived.value = isArchived
    const wrapper = await setup()
    let complete!: () => void
    const mutation = isArchived ? mocks.unarchive : mocks.archive
    mutation.mockImplementationOnce(() => new Promise<void>(resolve => { complete = resolve }))
    action.request()
    const accepted = dialog().accept?.()
    await flushPromises()
    wrapper.unmount()
    complete()
    await accepted
    expect(mocks.fetchMyTeams).toHaveBeenCalledOnce()
    expect(refreshed).not.toHaveBeenCalled()
  })
  it('更新後の一覧同期失敗は更新を再送せず再取得だけ行う', async () => {
    const wrapper = await setup()
    mocks.fetchMyTeams.mockResolvedValueOnce({ ok: false, error: new Error('offline') })
    action.request()
    await dialog().accept?.()
    expect(action.syncError.value).toBe(true)
    expect(refreshed).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
    action.request()
    expect(mocks.archive).toHaveBeenCalledOnce()
    await action.retrySync()
    expect(mocks.archive).toHaveBeenCalledOnce()
    expect(action.syncError.value).toBe(false)
    wrapper.unmount()
  })

  it('同期再取得中にslugが変わったら旧画面callbackを呼ばない', async () => {
    const wrapper = await setup()
    mocks.fetchMyTeams.mockResolvedValueOnce({ ok: false, error: new Error('offline') })
    action.request()
    await dialog().accept?.()
    let complete!: () => void
    mocks.fetchMyTeams.mockImplementationOnce(() => new Promise<{ ok: true }>(resolve => { complete = () => resolve({ ok: true }) }))
    const retry = action.retrySync()
    slug.value = 'team-b'
    await flushPromises()
    expect(action.syncError.value).toBe(false)
    complete()
    await retry
    expect(refreshed).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('前の確認ダイアログの遅いhideやacceptが次の確認を解除しない', async () => {
    const wrapper = await setup()
    action.request()
    const previous = dialog()
    previous.reject?.()
    action.request()
    previous.onHide?.()
    expect(action.busy.value).toBe(true)
    await previous.accept?.()
    expect(mocks.archive).not.toHaveBeenCalled()
    dialog().reject?.()
    wrapper.unmount()
  })

})
