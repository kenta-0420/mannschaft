import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamAffiliationApplyButton from './TeamAffiliationApplyButton.vue'

/**
 * F01.2.1 8-A — 加盟申請ボタン（組織シェル・公開ページ共通）の FE-UT。
 *
 * ボタンを出すか否かは BE の eligibility（canApply）に従う。FE で権限を推測しない。
 *
 * 検証観点:
 *   AB-01 canApply=true のとき申請ボタンが出る（AC-A11: TA・TG）
 *   AB-02 canApply=false のときボタンが出ない（AC-A08: 受付 off / AC-A09・A11: N・TM・TD）
 *   AB-03 未ログインでは eligibility を呼ばずボタンも出さない（AC-A11）
 *   AB-04 ボタンを押すと申請ダイアログが開く
 *   AB-05 canApply=false（正常な不可）ではエラー表示を出さない
 *   AB-06 eligibility の取得に失敗したら、無言で消さずエラーと再試行を出す（申請ボタンは出さない）
 *   AB-07 再試行で成功すればエラーが消えて申請ボタンが出る
 */

const canApply = vi.fn()
let loggedIn = true

vi.mock('~/composables/useTeamAffiliationApi', () => ({
  useTeamAffiliationApi: () => ({ canApply }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    get isAuthenticated() {
      return loggedIn
    },
  }),
}))

const stubs = {
  TeamAffiliationApplyDialog: {
    props: ['visible', 'orgSlug'],
    template: '<div data-testid="stub-apply-dialog" :data-visible="String(visible)" :data-org="orgSlug" />',
  },
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
}

async function mountButton() {
  const wrapper = await mountSuspended(TeamAffiliationApplyButton, {
    props: { orgSlug: 'org-1' },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

describe('TeamAffiliationApplyButton', () => {
  beforeEach(() => {
    canApply.mockReset()
    loggedIn = true
  })

  it('AB-01: canApply=true のとき申請ボタンが出る', async () => {
    canApply.mockResolvedValue(true)
    const wrapper = await mountButton()
    expect(canApply).toHaveBeenCalledWith('org-1')
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(true)
  })

  it('AB-02: canApply=false のときボタンが出ない', async () => {
    canApply.mockResolvedValue(false)
    const wrapper = await mountButton()
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(false)
  })

  it('AB-03: 未ログインでは eligibility を呼ばず、ボタンも出さない', async () => {
    loggedIn = false
    canApply.mockResolvedValue(true)
    const wrapper = await mountButton()
    expect(canApply).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(false)
  })

  it('AB-04: ボタンを押すと申請ダイアログが開く', async () => {
    canApply.mockResolvedValue(true)
    const wrapper = await mountButton()
    const dialog = () => wrapper.find('[data-testid="stub-apply-dialog"]')
    expect(dialog().attributes('data-visible')).toBe('false')
    await wrapper.find('[data-testid="team-affiliation-apply-button"]').trigger('click')
    expect(dialog().attributes('data-visible')).toBe('true')
    expect(dialog().attributes('data-org')).toBe('org-1')
  })

  it('AB-05: canApply=false（正常な不可）ではエラー表示を出さない', async () => {
    canApply.mockResolvedValue(false)
    const wrapper = await mountButton()
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="team-affiliation-apply-error"]').exists()).toBe(false)
  })

  it('AB-06: eligibility の取得に失敗したらエラーと再試行を出す（申請ボタンは出さない）', async () => {
    canApply.mockRejectedValue(new Error('network'))
    const wrapper = await mountButton()
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="team-affiliation-apply-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="team-affiliation-apply-retry"]').exists()).toBe(true)
  })

  it('AB-07: 再試行で成功すればエラーが消えて申請ボタンが出る', async () => {
    canApply.mockRejectedValueOnce(new Error('network'))
    canApply.mockResolvedValueOnce(true)
    const wrapper = await mountButton()
    await wrapper.find('[data-testid="team-affiliation-apply-retry"]').trigger('click')
    await flushPromises()
    expect(canApply).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="team-affiliation-apply-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="team-affiliation-apply-button"]').exists()).toBe(true)
  })
})
