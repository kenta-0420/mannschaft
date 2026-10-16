// @vitest-environment happy-dom
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, onMounted, ref } from 'vue'
import { createI18n, useI18n } from 'vue-i18n'
import InviteTokenList from './InviteTokenList.vue'
import common from '~/locales/en/common.json'
import ja from '~/locales/ja/common.json'
import zh from '~/locales/zh/common.json'
import ko from '~/locales/ko/common.json'
import es from '~/locales/es/common.json'
import de from '~/locales/de/common.json'

const messages = { ja, en: common, zh, ko, es, de }

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  downloadInviteTokenPdf: vi.fn(),
}))
// CIのNuxt autoimport先と、局所SFCのglobal参照を同じfixtureへ揃える。
vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => mocks }))
vi.mock('~/composables/useInviteTokenApi', () => ({ useInviteTokenApi: () => mocks }))
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({ formatDate: (value: string) => value }),
}))
vi.stubGlobal('useApi', () => mocks.api)
vi.stubGlobal('useNotification', () => mocks)
vi.stubGlobal('useInviteTokenApi', () => mocks)
vi.stubGlobal('useDatetime', () => ({ formatDate: (value: string) => value }))
vi.stubGlobal('useI18n', useI18n)
vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('onMounted', onMounted)
const token = {
  id: 99,
  token: 'invite-fixture',
  roleName: 'MEMBER',
  expiresAt: null,
  maxUses: null,
  usedCount: 0,
  revokedAt: null,
  createdAt: '2026-10-16T00:00:00',
}
const Table = defineComponent({
  props: { value: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () =>
      h(
        'div',
        props.value.length
          ? slots.default?.().flatMap((column) => [
              h('span', String(column.props?.header ?? '')),
              ...props.value.map((data) => {
                const children = column.children as {
                  body?: (scope: { data: unknown }) => ReturnType<typeof h>[]
                }
                return children?.body?.({ data })
              }),
            ])
          : slots.empty?.(),
      )
  },
})
async function mountList(
  scopeType: 'team' | 'organization' = 'team',
  locale: keyof typeof messages = 'en',
) {
  const wrapper = mount(InviteTokenList, {
    props: { scopeType, scopeId: 'private-scope' },
    global: {
      plugins: [
        createI18n({
          legacy: false,
          locale,
          messages,
        }),
      ],
      directives: { tooltip: () => {} },
      stubs: {
        DataTable: Table,
        Column: true,
        RoleBadge: { props: ['role'], template: '<span>{{ role }}</span>' },
        Dialog: {
          props: ['visible', 'header'],
          template:
            '<section v-if="visible"><h2>{{ header }}</h2><slot /><slot name="footer" /></section>',
        },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button @click="$emit(\'click\')">{{ label }}</button>',
        },
        Select: {
          props: ['options', 'modelValue'],
          emits: ['update:modelValue'],
          template:
            '<select><option v-for="option in options" :key="String(option.value)">{{ option.label }}</option></select>',
        },
        InputNumber: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}
async function openForm(wrapper: Awaited<ReturnType<typeof mountList>>) {
  await wrapper
    .findAll('button')
    .find(
      (button) => button.find('.pi-plus').exists() || button.attributes('icon') === 'pi pi-plus',
    )!
    .trigger('click')
}
describe('スコープ招待の英語表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockReset().mockResolvedValue({ data: [] })
  })
  for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const) {
    it(`${locale}の一覧とフォームが辞書の表示文言を使用する`, async () => {
      const wrapper = await mountList('team', locale)
      expect(wrapper.text()).toContain(messages[locale].inviteToken.title)
      expect(wrapper.text()).toContain(messages[locale].inviteToken.empty)
      await openForm(wrapper)
      expect(wrapper.text()).toContain(messages[locale].inviteToken.createTitle)
      expect(wrapper.text()).toContain(messages[locale].inviteToken.maxUses)
      expect(wrapper.text()).toContain(messages[locale].inviteToken.never)
      expect(wrapper.text()).toContain(messages[locale].role.MEMBER)
      expect(wrapper.text()).not.toContain('inviteToken.')
    })
  }
  it('空状態・作成フォーム・全選択肢を英語で表示し、開くだけでは発行しない', async () => {
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('Invite links')
    expect(wrapper.text()).toContain('No invite links yet')
    await openForm(wrapper)
    for (const text of [
      'Create invite link',
      'Invite role',
      'Expires after',
      'Maximum uses (leave blank for unlimited)',
      'Member',
      'Supporter',
      'Guest',
      '1 day',
      '7 days',
      '30 days',
      '90 days',
      'Never',
      'Cancel',
      'Create',
    ])
      expect(wrapper.text()).toContain(text)
    expect(wrapper.text()).not.toMatch(/[ぁ-んァ-ヶ一-龯]/)
    expect(mocks.api).toHaveBeenCalledTimes(1)
  })
  for (const scopeType of ['team', 'organization'] as const) {
    it(`${scopeType}の作成既定値を変えず英語の成功通知を表示する`, async () => {
      const wrapper = await mountList(scopeType)
      await openForm(wrapper)
      await wrapper
        .findAll('button')
        .find((button) => button.attributes('icon') === 'pi pi-check')!
        .trigger('click')
      await flushPromises()
      expect(mocks.api).toHaveBeenCalledWith(
        `/api/v1/${scopeType === 'team' ? 'teams' : 'organizations'}/private-scope/invite-tokens`,
        { method: 'POST', body: { roleId: 4, expiresIn: '7d', maxUses: null } },
      )
      expect(mocks.success).toHaveBeenCalledWith('Invite link created')
    })
  }
  it('作成失敗を英語で通知し入力フォームを維持する', async () => {
    const wrapper = await mountList()
    await openForm(wrapper)
    mocks.api.mockRejectedValueOnce(new Error('offline'))
    await wrapper
      .findAll('button')
      .find((button) => button.attributes('icon') === 'pi pi-check')!
      .trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('Failed to create invite link')
    expect(wrapper.find('section').exists()).toBe(true)
  })
  it('期限なし・操作名・コピー通知・失効確認と成功通知を英語で表示する', async () => {
    mocks.api.mockResolvedValue({ data: [token] })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const confirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', confirm)
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('Never')
    for (const text of ['Role', 'Expires after', 'Uses', 'Actions'])
      expect(wrapper.text()).toContain(text)
    await wrapper.get('button[aria-label="Copy invite URL"]').trigger('click')
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/invite/invite-fixture`)
    expect(mocks.success).toHaveBeenCalledWith('Invite URL copied')
    await wrapper.get('button[aria-label="Revoke invite link"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledWith('Revoke this invite link?')
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/private-scope/invite-tokens/99', {
      method: 'DELETE',
    })
    expect(mocks.success).toHaveBeenCalledWith('Invite link revoked')
  })
  it('失効操作の失敗も英語で通知する', async () => {
    mocks.api.mockResolvedValue({ data: [token] })
    const confirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', confirm)
    const wrapper = await mountList()
    mocks.api.mockRejectedValueOnce(new Error('offline'))
    await wrapper
      .findAll('button')
      .find((button) => button.attributes('icon') === 'pi pi-times')!
      .trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('Failed to revoke invite link')
  })
})

afterAll(() => vi.unstubAllGlobals())
