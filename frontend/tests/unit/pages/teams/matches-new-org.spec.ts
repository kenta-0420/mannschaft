import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import NewMatchPage from '~/pages/teams/[slug]/matches/new.vue'

/**
 * F01.2.1 §9.2 F1（3-E・AC-N12）試合作成ページの組織指定。
 *
 *   MNO-001: org が無効（不正・空・親組織に無い）なら、代表親組織へ落とさず作成 API を呼ばない（警告が出る）
 *   MNO-002: org が有効なら、その組織の下に作成し、遷移先に org を引き継ぐ
 *
 * ルータ・ルートは実物を使う（Nuxt のルータプラグインが useRouter のフルセットを要求するため、
 * useRouter をモックしない）。URL クエリは mountSuspended の route オプションで与え、
 * 遷移は router.push の spy で観測する。
 */

const mockResolveContext = vi.fn()
const mockCreateMatch = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ buildOffsetDateTimeStr: () => null }))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext: mockResolveContext }))
mockNuxtImport('useMatchApi', () => () => ({ createMatch: mockCreateMatch }))

const ORGS = [
  { id: 11, slug: 'x', name: '組織X' },
  { id: 22, slug: 'y', name: '組織Y' },
]

interface NewMatchVm {
  form: { kind: string | null; opponentName: string }
  submit: () => Promise<void>
}

describe('matches/new.vue 組織指定', () => {
  beforeEach(() => {
    mockResolveContext.mockReset()
    mockCreateMatch.mockReset()
  })

  it('MNO-001: org が無効なら作成を止め、警告とセレクタを出す', async () => {
    mockResolveContext.mockResolvedValue({ orgId: null, orgInvalid: true, teamId: 42, organizations: ORGS })

    const wrapper = await mountSuspended(NewMatchPage, { route: '/teams/team-a/matches/new?org=abc' })
    await new Promise((r) => setTimeout(r, 0))
    const vm = wrapper.vm as unknown as NewMatchVm
    vm.form.kind = 'PRACTICE'
    vm.form.opponentName = '対 相手FC'
    await vm.submit()

    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="match-org-invalid"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="match-org-select"]').exists()).toBe(true)
  })

  it('MNO-002: org が有効ならその組織で作成し、遷移先に org を引き継ぐ', async () => {
    mockResolveContext.mockResolvedValue({ orgId: 22, orgInvalid: false, teamId: 42, organizations: ORGS })
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(NewMatchPage, { route: '/teams/team-a/matches/new?org=22' })
    await new Promise((r) => setTimeout(r, 0))
    const pushSpy = vi.spyOn(useNuxtApp().$router, 'push').mockResolvedValue(undefined)
    const vm = wrapper.vm as unknown as NewMatchVm
    vm.form.kind = 'PRACTICE'
    vm.form.opponentName = '対 相手FC'
    await vm.submit()

    expect(mockResolveContext).toHaveBeenCalledWith('team-a', { orgId: 22 })
    expect(mockCreateMatch).toHaveBeenCalledWith(22, 42, expect.anything())
    expect(pushSpy).toHaveBeenCalledWith({
      path: '/teams/team-a/matches/m-new/live',
      query: { org: '22' },
    })
    pushSpy.mockRestore()
  })
})
