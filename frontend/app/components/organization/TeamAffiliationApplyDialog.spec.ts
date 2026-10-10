import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { TeamApplicationForm } from '~/composables/useTeamAffiliationApi'
import TeamAffiliationApplyDialog from './TeamAffiliationApplyDialog.vue'

/**
 * F01.2.1 8-A — 加盟申請ダイアログ（TeamAffiliationApplyDialog）の FE-UT。
 *
 * 検証観点:
 *   AD-01 REQUIRED でグループ未選択なら送信ボタンが押せない（AC-B10 の UI 側）
 *   AD-02 REQUIRED でグループを選ぶと押せて、送信値に groupId が入る
 *   AD-03 OPTIONAL はグループ未選択でも送れ、groupId は送らない
 *   AD-04 OFF はグループ選択欄を出さず、groupId は送らない
 *   AD-05 affiliationStatus が NONE 以外のチームは選べない（申請できるチームが無ければ送信不可）
 *   AD-06 添え書きは送信値の message に入る
 *   AD-07 送信成功で applied を発火し、ダイアログを閉じる
 *   AD-08 送信失敗はエラーとして表示し、ダイアログを閉じない（握りつぶさない）
 */

const getApplicationForm = vi.fn()
const applyToOrganization = vi.fn()

vi.mock('~/composables/useTeamAffiliationApi', () => ({
  useTeamAffiliationApi: () => ({ getApplicationForm, applyToOrganization }),
}))

// PrimeVue の Dialog は Teleport で body に描画されるため、スロットだけ描画する軽量スタブにする。
const stubs = {
  Dialog: {
    props: ['visible', 'header'],
    template: '<div v-if="visible" data-testid="stub-dialog"><slot /><slot name="footer" /></div>',
  },
  Select: {
    props: ['modelValue', 'options', 'optionLabel', 'optionValue', 'optionDisabled', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<select :value="modelValue ?? ''" @change="$emit('update:modelValue', $event.target.value === '' ? null : $event.target.value)">
      <option value="">-</option>
      <option v-for="o in options" :key="o[optionValue]" :value="o[optionValue]" :disabled="o[optionDisabled]">{{ o[optionLabel] }}</option>
    </select>`,
  },
  Textarea: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Message: { template: '<div><slot /></div>' },
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const GROUP_A = '0192a000-0000-7000-8000-00000000000a'

function makeForm(over: Partial<TeamApplicationForm> = {}): TeamApplicationForm {
  return {
    groupMode: 'OPTIONAL',
    groups: [{ id: GROUP_A, name: '平成20年度', description: '' }],
    guidance: '申請の前に規約をご確認ください',
    myTeams: [{ slug: 'team-a', name: 'チームA', affiliationStatus: 'NONE' }],
    organization: { slug: 'org-1', name: '組織1' },
    ...over,
  }
}

async function mountDialog(form: TeamApplicationForm) {
  getApplicationForm.mockResolvedValue(form)
  const wrapper = await mountSuspended(TeamAffiliationApplyDialog, {
    props: { visible: true, orgSlug: 'org-1' },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

function submitButton(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  return wrapper.find('[data-testid="apply-submit"]')
}

describe('TeamAffiliationApplyDialog', () => {
  beforeEach(() => {
    getApplicationForm.mockReset()
    applyToOrganization.mockReset()
    applyToOrganization.mockResolvedValue(undefined)
  })

  it('AD-01: REQUIRED でグループ未選択なら送信ボタンが押せない', async () => {
    const wrapper = await mountDialog(makeForm({ groupMode: 'REQUIRED' }))
    expect(wrapper.find('[data-testid="apply-group-select"]').exists()).toBe(true)
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    await submitButton(wrapper).trigger('click')
    expect(applyToOrganization).not.toHaveBeenCalled()
  })

  it('AD-02: REQUIRED でグループを選ぶと送信でき、groupId が送信値に入る', async () => {
    const wrapper = await mountDialog(makeForm({ groupMode: 'REQUIRED' }))
    await wrapper.find('[data-testid="apply-group-select"]').setValue(GROUP_A)
    expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(applyToOrganization).toHaveBeenCalledWith('team-a', {
      organizationSlug: 'org-1',
      groupId: GROUP_A,
      message: undefined,
    })
  })

  it('AD-03: OPTIONAL はグループ未選択でも送れ、groupId を送らない', async () => {
    const wrapper = await mountDialog(makeForm({ groupMode: 'OPTIONAL' }))
    expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    const body = applyToOrganization.mock.calls[0]![1]
    expect(body.groupId).toBeUndefined()
    expect(body.organizationSlug).toBe('org-1')
  })

  it('AD-04: OFF はグループ選択欄を出さず、groupId を送らない', async () => {
    const wrapper = await mountDialog(makeForm({ groupMode: 'OFF', groups: [] }))
    expect(wrapper.find('[data-testid="apply-group-select"]').exists()).toBe(false)
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(applyToOrganization.mock.calls[0]![1].groupId).toBeUndefined()
  })

  it('AD-05: NONE 以外のチームは選べず、申請できるチームが無ければ送信不可', async () => {
    const wrapper = await mountDialog(
      makeForm({
        myTeams: [
          { slug: 'team-a', name: 'チームA', affiliationStatus: 'APPLYING' },
          { slug: 'team-b', name: 'チームB', affiliationStatus: 'ACTIVE' },
        ],
      }),
    )
    const options = wrapper.findAll('[data-testid="apply-team-select"] option')
    const disabledNames = options.filter(o => o.attributes('disabled') !== undefined).map(o => o.text())
    expect(disabledNames.length).toBe(2)
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('AD-05b: 申請できるチームが1つだけなら自動で選択される', async () => {
    const wrapper = await mountDialog(
      makeForm({
        myTeams: [
          { slug: 'team-a', name: 'チームA', affiliationStatus: 'ACTIVE' },
          { slug: 'team-b', name: 'チームB', affiliationStatus: 'NONE' },
        ],
      }),
    )
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(applyToOrganization.mock.calls[0]![0]).toBe('team-b')
  })

  it('AD-06: 添え書きが message として送信値に入る', async () => {
    const wrapper = await mountDialog(makeForm())
    await wrapper.find('[data-testid="apply-message"]').setValue('よろしくお願いします')
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(applyToOrganization.mock.calls[0]![1].message).toBe('よろしくお願いします')
  })

  it('AD-07: 送信成功で applied を発火し、visible を false にする', async () => {
    const wrapper = await mountDialog(makeForm())
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('applied')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('AD-08: 送信失敗はエラー表示になり、ダイアログを閉じない', async () => {
    applyToOrganization.mockRejectedValue(new Error('TEAM_067'))
    const wrapper = await mountDialog(makeForm())
    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="apply-error"]').exists()).toBe(true)
    expect(wrapper.emitted('applied')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('AD-09: 再表示でフォーム取得に失敗したら、前回の選択を持ち越さず送信できない', async () => {
    const wrapper = await mountDialog(makeForm({ groupMode: 'REQUIRED' }))
    await wrapper.find('[data-testid="apply-group-select"]').setValue(GROUP_A)
    expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    // いったん閉じて、取得失敗のまま開き直す。
    getApplicationForm.mockReset()
    getApplicationForm.mockRejectedValue(new Error('network'))
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(wrapper.find('[data-testid="apply-load-error"]').exists()).toBe(true)
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
    await submitButton(wrapper).trigger('click')
    expect(applyToOrganization).not.toHaveBeenCalled()
  })

  it('AD-10: 添え書きは BE と同じ 500 文字まで（maxlength と文字数表示。501 文字は送信不可）', async () => {
    const wrapper = await mountDialog(makeForm())
    const textarea = wrapper.find('[data-testid="apply-message"]')
    expect(textarea.attributes('maxlength')).toBe('500')
    await textarea.setValue('あ'.repeat(500))
    expect(wrapper.find('[data-testid="apply-message-count"]').text()).toContain('500 / 500')
    expect(submitButton(wrapper).attributes('disabled')).toBeUndefined()
    await textarea.setValue('あ'.repeat(501))
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined()
  })
})
