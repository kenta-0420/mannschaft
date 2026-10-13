import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import MemberTeamsViewSwitcher from '~/components/organization/member-teams/MemberTeamsViewSwitcher.vue'

/**
 * F01.2.1 8-B — view 切替（MemberTeamsViewSwitcher）の FE-UT。
 *
 *   SW-01 一覧・加盟申請・送った招待・申請の制限の4つを出す（groups は 8-C まで出さない）
 *   SW-02 現在の view が aria-current になる
 *   SW-03 未処理の申請件数をバッジに出す（0 件・未取得では出さない）
 *   SW-04 件数の取得に失敗したら、バッジの代わりにエラー印を出す
 *   SW-05 クリックで select を発火する
 */

async function mountSwitcher(props: Record<string, unknown> = {}) {
  return await mountSuspended(MemberTeamsViewSwitcher, {
    props: { current: 'teams', pendingCount: null, countError: false, ...props },
  })
}

describe('MemberTeamsViewSwitcher', () => {
  it('SW-01: 一覧・加盟申請・送った招待・申請の制限の4つを出す', async () => {
    const wrapper = await mountSwitcher()
    for (const view of ['teams', 'applications', 'invites', 'restrictions']) {
      expect(wrapper.find(`[data-testid="view-tab-${view}"]`).exists()).toBe(true)
    }
    expect(wrapper.find('[data-testid="view-tab-groups"]').exists()).toBe(false)
  })

  it('SW-02: 現在の view が aria-current になる', async () => {
    const wrapper = await mountSwitcher({ current: 'invites' })
    expect(wrapper.find('[data-testid="view-tab-invites"]').attributes('aria-current')).toBe('page')
    expect(wrapper.find('[data-testid="view-tab-teams"]').attributes('aria-current')).toBeUndefined()
  })

  it('SW-03: 未処理の申請件数をバッジに出す（0 件・未取得では出さない）', async () => {
    const withCount = await mountSwitcher({ pendingCount: 3 })
    expect(withCount.find('[data-testid="view-tab-badge"]').text()).toBe('3')
    const zero = await mountSwitcher({ pendingCount: 0 })
    expect(zero.find('[data-testid="view-tab-badge"]').exists()).toBe(false)
    const unknown = await mountSwitcher({ pendingCount: null })
    expect(unknown.find('[data-testid="view-tab-badge"]').exists()).toBe(false)
  })

  it('SW-04: 件数の取得に失敗したら、エラー印を出す', async () => {
    const wrapper = await mountSwitcher({ countError: true })
    expect(wrapper.find('[data-testid="view-tab-badge-error"]').exists()).toBe(true)
  })

  it('SW-05: クリックで select を発火する', async () => {
    const wrapper = await mountSwitcher()
    await wrapper.find('[data-testid="view-tab-restrictions"]').trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['restrictions'])
    await wrapper.find('[data-testid="view-tab-teams"]').trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['teams'])
  })
})
