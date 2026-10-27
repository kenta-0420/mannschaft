import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Entry from './FriendRequestEntry.vue'
const mocks = vi.hoisted(() => ({ load: vi.fn(), navigate: vi.fn(), api: vi.fn(), auth: { isAuthenticated: false } }))
mockNuxtImport('useAuthStore', () => () => ({ loadFromStorage: mocks.load, get isAuthenticated() { return mocks.auth.isAuthenticated } }))
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useLocalePath', () => () => (path: string) => path)
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
const DialogStub = defineComponent({ props: ['modelValue', 'targetTeam'], template: '<div data-testid="entry-dialog" />' })
describe('FR-14 プロフィール申請入口', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.auth.isAuthenticated = false })
  it('初期描画では認証復元・本人候補API・ダイアログを実行しない', async () => {
    const w = await mountSuspended(Entry, { props: { targetTeam: { teamSlug: 'target-team', teamName: 'Target' }, profileKind: 'public' } })
    expect(mocks.load).not.toHaveBeenCalled()
    expect(mocks.api).not.toHaveBeenCalled()
    expect(w.findComponent({ name: 'FriendFollowDialog' }).exists()).toBe(false)
    w.unmount()
  })
  it.each(['public', 'authorized'] as const)('未認証%s入口は既知プロフィールパスだけでログイン復帰先を作る', async kind => {
    const w = await mountSuspended(Entry, { props: { targetTeam: { teamSlug: 'target-team', teamName: 'Target' }, profileKind: kind } })
    await w.get('button').trigger('click')
    expect(mocks.load).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).toHaveBeenCalledWith({ path: '/login', query: { redirect: kind === 'public' ? '/public/teams/target-team' : '/teams/target-team/info' } })
    expect(mocks.api).not.toHaveBeenCalled()
    w.unmount()
  })
  it('認証済みクリックだけで先固定の選択ダイアログを開く', async () => {
    mocks.auth.isAuthenticated = true
    const w = await mountSuspended(Entry, { props: { targetTeam: { teamSlug: 'target-team', teamName: 'Target' }, profileKind: 'public' }, global: { stubs: { FriendFollowDialog: DialogStub } } })
    await w.get('button').trigger('click')
    expect(w.find('[data-testid="entry-dialog"]').exists()).toBe(true)
    expect(mocks.navigate).not.toHaveBeenCalled()
    expect(mocks.api).not.toHaveBeenCalled()
    w.unmount()
  })
})
