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
 * ルータは実物を使う（Nuxt のルータプラグインが useRouter のフルセットを要求するため useRouter は
 * モックしない）。遷移は実ルータの push の spy で観測する。URL（slug・org クエリ）は
 * mountSuspended の route オプションがページのルートに反映されないため、useRoute だけをモックして与える。
 */

const mockResolveContext = vi.fn()
const mockCreateMatch = vi.fn()
const route: { params: { slug: string }; query: Record<string, string> } = {
  params: { slug: 'team-a' },
  query: {},
}

mockNuxtImport('useRoute', () => () => route)

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
    route.query = {}
  })

  it('MNO-001: org が無効なら作成を止め、警告とセレクタを出す', async () => {
    mockResolveContext.mockResolvedValue({ orgId: null, orgInvalid: true, teamId: 42, organizations: ORGS })

    route.query = { org: 'abc' }
    const wrapper = await mountSuspended(NewMatchPage)
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

    route.query = { org: '22' }
    const wrapper = await mountSuspended(NewMatchPage)
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
