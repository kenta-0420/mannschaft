import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import TeamApplicationRejectDialog from '~/components/organization/member-teams/TeamApplicationRejectDialog.vue'
import { primeStubs } from './stubs'

/**
 * F01.2.1 8-B — 加盟申請の拒否ダイアログ（TeamApplicationRejectDialog）の FE-UT。
 *
 * 検証観点（AC-D01 の UI 側）:
 *   RJ-01 理由とブロックのチェックが送信値に入る
 *   RJ-02 何も入力しなければ reason なし・block=false（冷却のみ）
 *   RJ-03 理由は 500 文字（コードポイント）まで。501 文字は送信不可
 *   RJ-04 拒否成功で rejected を発火して閉じる
 *   RJ-05 拒否失敗はエラー表示で閉じない
 */

const rejectApplication = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({ rejectApplication }),
}))

const application: TeamOrgAffiliation = {
  id: 202,
  direction: 'TEAM_APPLICATION',
  status: 'PENDING',
  team: { slug: 'team-b', name: 'チームB' },
}

async function mountDialog() {
  const wrapper = await mountSuspended(TeamApplicationRejectDialog, {
    props: { visible: true, orgSlug: 'org-1', application },
    global: { stubs: primeStubs },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountDialog>>
const submit = (w: Wrapper) => w.find('[data-testid="reject-submit"]')

describe('TeamApplicationRejectDialog', () => {
  beforeEach(() => {
    rejectApplication.mockReset()
    rejectApplication.mockResolvedValue(undefined)
  })

  it('RJ-01: 理由とブロックのチェックが送信値に入る', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('[data-testid="reject-reason"]').setValue('活動地域が異なります')
    await wrapper.find('[data-testid="reject-block"]').setValue(true)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(rejectApplication).toHaveBeenCalledWith('org-1', 202, { reason: '活動地域が異なります', block: true })
  })

  it('RJ-02: 何も入力しなければ reason なし・block=false', async () => {
    const wrapper = await mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(rejectApplication).toHaveBeenCalledWith('org-1', 202, { reason: undefined, block: false })
  })

  it('RJ-03: 理由は 500 文字（コードポイント）まで。501 文字は送信できない', async () => {
    const wrapper = await mountDialog()
    const reason = wrapper.find('[data-testid="reject-reason"]')
    await reason.setValue('😀'.repeat(500))
    expect(wrapper.find('[data-testid="reject-reason-count"]').text()).toContain('500 / 500')
    expect(submit(wrapper).attributes('disabled')).toBeUndefined()
    await reason.setValue('😀'.repeat(501))
    expect(submit(wrapper).attributes('disabled')).toBeDefined()
    await submit(wrapper).trigger('click')
    expect(rejectApplication).not.toHaveBeenCalled()
  })

  it('RJ-04: 拒否成功で rejected を発火し、visible を false にする', async () => {
    const wrapper = await mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('rejected')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('RJ-05: 拒否失敗はエラー表示になり、閉じず、rejected も発火しない', async () => {
    rejectApplication.mockRejectedValue(new Error('TEAM_070'))
    const wrapper = await mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="reject-error"]').exists()).toBe(true)
    expect(wrapper.emitted('rejected')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})
