import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import pageSource from '~/pages/organizations/[slug]/settings/team-affiliation.vue?raw'
import TeamAffiliationSettingsPage from '~/pages/organizations/[slug]/settings/team-affiliation.vue'

/**
 * F01.2.1 8-A — 組織の「チーム加盟の設定」画面の FE-UT。
 *
 * 検証観点:
 *   TS-01 ガード: definePageMeta が auth + org-role-guard を宣言している（AC-K01 の入口。
 *         追い返しの挙動そのものは middleware/org-role-guard.ts のテストと実機で確認する）
 *   TS-02 設定を読み込んで各欄に反映する
 *   TS-03 受付を off にして保存するときは確認ダイアログを挟み、確認するまで PUT しない（受付済み件数を表示）
 *   TS-04 確認ダイアログで承諾すると PUT する
 *   TS-05 受付を on のまま保存するときは確認なしで PUT する
 *   TS-06 REQUIRED の設定が実効 OPTIONAL に格下げされているとき警告を出す（AC-G112 の画面側）
 *   TS-07 格下げされていなければ警告を出さない
 *   TS-08 保存失敗はエラー表示し、成功トーストにしない
 *   TS-10 案内文は BE と同じ 500 文字まで（maxlength と文字数表示）
 *   TS-09 PageHeader(help) と SectionCard が付き、使い方モーダルが開く（AC-G143）
 */

const getSettings = vi.fn()
const updateSettings = vi.fn()

vi.mock('~/composables/useTeamAffiliationApi', () => ({
  useTeamAffiliationApi: () => ({ getSettings, updateSettings }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' }, query: {} }))

const stubs = {
  PageHeader: {
    props: { title: String, help: Boolean },
    emits: ['help'],
    template: '<div data-testid="stub-page-header" :data-help="String(help)"><button data-testid="page-header-help" @click="$emit(\'help\')" /><slot /></div>',
  },
  SectionCard: { template: '<section data-testid="stub-section-card"><slot /></section>' },
  TeamAffiliationGuideModal: {
    props: ['visible'],
    template: '<div data-testid="stub-guide-modal" :data-visible="String(visible)" />',
  },
  Dialog: {
    props: ['visible', 'header'],
    template: '<div v-if="visible" data-testid="stub-dialog"><slot /><slot name="footer" /></div>',
  },
  ToggleSwitch: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  Select: {
    props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
    emits: ['update:modelValue'],
    template: `<select :value="modelValue" @change="$emit('update:modelValue', $event.target.value)">
      <option v-for="o in options" :key="o[optionValue]" :value="o[optionValue]">{{ o[optionLabel] }}</option>
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
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
}

function makeSettings(over: Record<string, unknown> = {}) {
  return {
    teamApplicationEnabled: true,
    teamGroupsEnabled: true,
    applicationGroupMode: 'OPTIONAL',
    effectiveApplicationGroupMode: 'OPTIONAL',
    applicationGuidance: '案内文です',
    pendingApplicationCount: 0,
    ...over,
  }
}

async function mountPage(settings = makeSettings()) {
  getSettings.mockResolvedValue(settings)
  updateSettings.mockResolvedValue(settings)
  const wrapper = await mountSuspended(TeamAffiliationSettingsPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('pages/organizations/[slug]/settings/team-affiliation.vue', () => {
  beforeEach(() => {
    getSettings.mockReset()
    updateSettings.mockReset()
  })

  it('TS-01: definePageMeta が auth と org-role-guard を宣言している', () => {
    const meta = /definePageMeta\(\{[\s\S]*?\}\)/.exec(pageSource)?.[0] ?? ''
    expect(meta).toMatch(/layout:\s*'organization'/)
    expect(meta).toMatch(/middleware:\s*\[[^\]]*'auth'[^\]]*\]/)
    expect(meta).toMatch(/middleware:\s*\[[^\]]*'org-role-guard'[^\]]*\]/)
  })

  it('TS-02: 設定を読み込んで各欄に反映する', async () => {
    const wrapper = await mountPage(makeSettings({ applicationGuidance: '申請前にご確認ください' }))
    expect(getSettings).toHaveBeenCalledWith('org-1')
    expect((wrapper.find('[data-testid="settings-guidance"]').element as HTMLTextAreaElement).value).toBe('申請前にご確認ください')
    expect((wrapper.find('[data-testid="settings-application-enabled"]').element as HTMLInputElement).checked).toBe(true)
  })

  it('TS-03: 受付を off にして保存するときは確認を挟み、確認前に PUT しない（受付済み件数を表示）', async () => {
    const wrapper = await mountPage(makeSettings({ pendingApplicationCount: 3 }))
    await wrapper.find('[data-testid="settings-application-enabled"]').setValue(false)
    await wrapper.find('[data-testid="settings-save"]').trigger('click')
    await flushPromises()
    expect(updateSettings).not.toHaveBeenCalled()
    const confirm = wrapper.find('[data-testid="turn-off-confirm"]')
    expect(confirm.exists()).toBe(true)
    expect(confirm.text()).toContain('3')
  })

  it('TS-04: 確認ダイアログで承諾すると PUT する', async () => {
    const wrapper = await mountPage(makeSettings({ pendingApplicationCount: 1 }))
    await wrapper.find('[data-testid="settings-application-enabled"]').setValue(false)
    await wrapper.find('[data-testid="settings-save"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="turn-off-confirm-ok"]').trigger('click')
    await flushPromises()
    expect(updateSettings).toHaveBeenCalledWith('org-1', {
      teamApplicationEnabled: false,
      teamGroupsEnabled: true,
      applicationGroupMode: 'OPTIONAL',
      applicationGuidance: '案内文です',
    })
  })

  it('TS-05: 受付を on のまま保存するときは確認なしで PUT する', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="settings-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="turn-off-confirm"]').exists()).toBe(false)
    expect(updateSettings).toHaveBeenCalledTimes(1)
  })

  it('TS-06: REQUIRED が実効 OPTIONAL に格下げされているとき警告を出す', async () => {
    const wrapper = await mountPage(
      makeSettings({ applicationGroupMode: 'REQUIRED', effectiveApplicationGroupMode: 'OPTIONAL' }),
    )
    expect(wrapper.find('[data-testid="required-mode-degraded"]').exists()).toBe(true)
  })

  it('TS-07: 格下げされていなければ警告を出さない', async () => {
    const wrapper = await mountPage(
      makeSettings({ applicationGroupMode: 'REQUIRED', effectiveApplicationGroupMode: 'REQUIRED' }),
    )
    expect(wrapper.find('[data-testid="required-mode-degraded"]').exists()).toBe(false)
  })

  it('TS-08: 保存失敗はエラー表示になる', async () => {
    const wrapper = await mountPage()
    updateSettings.mockRejectedValue(new Error('boom'))
    await wrapper.find('[data-testid="settings-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="settings-save-error"]').exists()).toBe(true)
  })

  it('TS-09: PageHeader(help) と SectionCard が付き、使い方モーダルが開く', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="stub-page-header"]').attributes('data-help')).toBe('true')
    expect(wrapper.find('[data-testid="stub-section-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="stub-guide-modal"]').attributes('data-visible')).toBe('false')
    await wrapper.find('[data-testid="page-header-help"]').trigger('click')
    expect(wrapper.find('[data-testid="stub-guide-modal"]').attributes('data-visible')).toBe('true')
  })

  it('TS-10: 案内文は BE（@Size max=500）と同じ 500 文字までで、文字数を表示する', async () => {
    const wrapper = await mountPage(makeSettings({ applicationGuidance: 'あ'.repeat(500) }))
    expect(wrapper.find('[data-testid="settings-guidance"]').attributes('maxlength')).toBe('500')
    expect(wrapper.find('[data-testid="settings-guidance-count"]').text()).toContain('500 / 500')
  })
})
