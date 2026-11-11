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
  confirmRequire: vi.fn(),
}))
// ネイティブ confirm() ではなく PrimeVue の useConfirm() を使う（FRONTEND_CODING_CONVENTION）
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: mocks.confirmRequire }) }))
vi.stubGlobal('useConfirm', () => ({ require: mocks.confirmRequire }))
// CIのNuxt autoimport先と、局所SFCのglobal参照を同じfixtureへ揃える。
vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => mocks }))
vi.mock('~/composables/useInviteTokenApi', () => ({ useInviteTokenApi: () => mocks }))
// 実際の整形関数と同じ粒度（formatDate=日付のみ / formatDateTime=時刻まで）を再現する。
const datetimeFormatters = {
  formatDate: (value: string) => value.slice(0, 10),
  formatDateTime: (value: string) => value.slice(0, 16),
  formatDateTimeSeconds: (value: string) => value.slice(0, 19),
}
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({
    formatDate: (value: string) => value.slice(0, 10),
    formatDateTime: (value: string) => value.slice(0, 16),
    formatDateTimeSeconds: (value: string) => value.slice(0, 19),
  }),
}))
vi.stubGlobal('useApi', () => mocks.api)
vi.stubGlobal('useNotification', () => mocks)
vi.stubGlobal('useInviteTokenApi', () => mocks)
vi.stubGlobal('useDatetime', () => datetimeFormatters)
vi.stubGlobal('useI18n', useI18n)
vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('onMounted', onMounted)
// 一覧 API の行は平文 token を持たない（サーバーにはハッシュしか残らない）
const token = {
  id: 99,
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
  table: typeof Table = Table,
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
        DataTable: table,
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
const expectedHint = createI18n({ legacy: false, locale: 'en', messages }).global.t(
  'secret_token.list.not_shown_hint',
)

describe('スコープ招待の英語表示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockReset().mockResolvedValue({ data: [] })
  })
  for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const) {
    it(`${locale}の一覧とフォームが辞書の表示文言を使用する`, async () => {
      const wrapper = await mountList('team', locale)
      // Nuxtの辞書importはASTになるため、期待する表示文字列もi18nのtで解決する。
      const expected = createI18n({ legacy: false, locale, messages }).global
      const expectedText = (key: string) => {
        const text = expected.t(key)
        expect(text).not.toBe('')
        expect(text).not.toBe(key)
        return text
      }
      expect(wrapper.text()).toContain(expectedText('inviteToken.title'))
      expect(wrapper.text()).toContain(expectedText('inviteToken.empty'))
      await openForm(wrapper)
      expect(wrapper.text()).toContain(expectedText('inviteToken.createTitle'))
      expect(wrapper.text()).toContain(expectedText('inviteToken.maxUses'))
      expect(wrapper.text()).toContain(expectedText('inviteToken.never'))
      expect(wrapper.text()).toContain(expectedText('role.MEMBER'))
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
  it('期限なし・操作名・再表示しない旨の案内・失効確認と成功通知を英語で表示する', async () => {
    mocks.api.mockResolvedValue({ data: [token] })
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('Never')
    for (const text of ['Role', 'Expires after', 'Uses', 'Actions'])
      expect(wrapper.text()).toContain(text)
    // 一覧からは URL のコピーも QR-PDF もできない（発行直後のダイアログでだけ見せる）
    expect(wrapper.find('button[aria-label="Copy invite URL"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(expectedHint)
    await wrapper.get('button[aria-label="Revoke invite link"]').trigger('click')
    await flushPromises()
    expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
    expect(mocks.confirmRequire.mock.calls[0]![0].message).toBe('Revoke this invite link?')
    mocks.confirmRequire.mock.calls[0]![0].accept()
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/private-scope/invite-tokens/99', {
      method: 'DELETE',
    })
    expect(mocks.success).toHaveBeenCalledWith('Invite link revoked')
  })
  it('失効操作の失敗も英語で通知する', async () => {
    mocks.api.mockResolvedValue({ data: [token] })
    const wrapper = await mountList()
    mocks.api.mockRejectedValueOnce(new Error('offline'))
    await wrapper
      .findAll('button')
      .find((button) => button.attributes('icon') === 'pi pi-times')!
      .trigger('click')
    await flushPromises()
    mocks.confirmRequire.mock.calls[0]![0].accept()
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('Failed to revoke invite link')
  })
})

// CMP-261110-0426 V3/V5: 行の識別（作成日時・案内）とモバイルでの列重なり防止
describe('招待リンク一覧の識別性とモバイル表示', () => {
  const columnProps: Array<Record<string, unknown>> = []
  const RecordingTable = defineComponent({
    props: { value: { type: Array, default: () => [] } },
    setup(props, { slots }) {
      return () => {
        const columns = slots.default?.() ?? []
        columnProps.length = 0
        for (const column of columns) columnProps.push({ ...(column.props ?? {}) })
        return h(
          'div',
          props.value.length
            ? columns.flatMap((column) => [
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
      }
    },
  })
  const t = createI18n({ legacy: false, locale: 'ja', messages }).global.t

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockReset().mockResolvedValue({ data: [token] })
  })

  it('各行に作成日時を表示する', async () => {
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    expect(wrapper.text()).toContain(t('secret_token.list.created_at'))
    expect(wrapper.text()).toContain('2026-10-16T00:00')
  })

  it('同日・異時刻に作った2行は作成日時が異なる表示になる（古いリンクの識別）', async () => {
    mocks.api.mockReset().mockResolvedValue({
      data: [
        { ...token, id: 1, createdAt: '2026-10-16T09:15:00' },
        { ...token, id: 2, createdAt: '2026-10-16T18:40:00' },
      ],
    })
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    const cells = wrapper.findAll('span.whitespace-nowrap').map((el) => el.text())
    expect(cells).toHaveLength(2)
    expect(new Set(cells).size).toBe(2)
    expect(cells[0]).toContain('09:15')
    expect(cells[1]).toContain('18:40')
  })

  it('再発行の案内（links_hint）を一覧付近に出し、誤った旧キーは使わない', async () => {
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    expect(wrapper.text()).toContain(t('secret_token.list.links_hint'))
    for (const locale of Object.keys(messages) as Array<keyof typeof messages>) {
      const i18n = createI18n({ legacy: false, locale, messages }).global
      expect(i18n.te('secret_token.list.reissue_hint')).toBe(false)
      expect(i18n.te('secret_token.list.links_hint')).toBe(true)
    }
  })

  it('操作列を sticky（frozen）にしない（390px で他列に重ならない）', async () => {
    await mountList('team', 'ja', RecordingTable as never)
    expect(columnProps.length).toBeGreaterThan(0)
    for (const column of columnProps) {
      expect(column.frozen === undefined || column.frozen === false).toBe(true)
      expect(column['align-frozen']).toBeUndefined()
    }
  })
})

// CMP-261111-1429 ALIC-1〜5: アリシゼーション確定不具合の再発防止
describe('招待リンク一覧のアリシゼーション確定不具合', () => {
  const columnProps: Array<Record<string, unknown>> = []
  const RecordingTable = defineComponent({
    props: { value: { type: Array, default: () => [] }, loading: Boolean },
    setup(props, { slots }) {
      return () => {
        const columns = slots.default?.() ?? []
        columnProps.length = 0
        for (const column of columns) columnProps.push({ ...(column.props ?? {}) })
        return h(
          'div',
          props.value.length
            ? columns.flatMap((column) =>
                props.value.map((data) => {
                  const children = column.children as {
                    body?: (scope: { data: unknown }) => ReturnType<typeof h>[]
                  }
                  return children?.body?.({ data })
                }),
              )
            : slots.empty?.(),
        )
      }
    },
  })
  const t = createI18n({ legacy: false, locale: 'ja', messages }).global.t

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockReset().mockResolvedValue({ data: [token] })
  })

  it('ALIC-1: 同じ分に発行した2行も秒まで表示して見分けられる', async () => {
    mocks.api.mockReset().mockResolvedValue({
      data: [
        { ...token, id: 1, createdAt: '2026-10-16T09:15:05' },
        { ...token, id: 2, createdAt: '2026-10-16T09:15:42' },
      ],
    })
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    const cells = wrapper.findAll('span.whitespace-nowrap').map((el) => el.text())
    expect(cells).toHaveLength(2)
    expect(new Set(cells).size).toBe(2)
    expect(cells[0]).toContain('09:15:05')
    expect(cells[1]).toContain('09:15:42')
  })

  it('ALIC-2: 無効化の操作列が先頭にあり、初期表示で横スクロールの外側に出ない（frozen にはしない）', async () => {
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    expect(columnProps[0]!.header).toBe(t('label.actions'))
    for (const column of columnProps) expect(column.frozen === undefined || column.frozen === false).toBe(true)
    expect(wrapper.find('button[aria-label="' + t('inviteToken.revoke') + '"]').exists()).toBe(true)
  })

  it('ALIC-3: 見出し行は折り返し可能で、新規作成ボタンは単語途中で折れない', async () => {
    const wrapper = await mountList('team', 'de', RecordingTable as never)
    const header = wrapper.get('[data-testid="invite-token-header"]')
    expect(header.classes()).toContain('flex-wrap')
    expect(header.classes().some((c) => c.startsWith('gap-'))).toBe(true)
    const create = header.findAll('button').find((b) => b.text().includes('Neuer Einladungslink'))!
    expect(create.classes()).toContain('shrink-0')
    expect(create.classes()).toContain('whitespace-nowrap')
  })

  it('ALIC-4: 読込中は空文言を出さず、取得後に0件なら出す', async () => {
    let resolveApi: (v: unknown) => void = () => {}
    mocks.api.mockReset().mockReturnValue(new Promise((resolve) => (resolveApi = resolve)))
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    expect(wrapper.text()).not.toContain(t('inviteToken.empty'))
    resolveApi({ data: [] })
    await flushPromises()
    expect(wrapper.text()).toContain(t('inviteToken.empty'))
  })

  it('ALIC-5: 無効化はネイティブ confirm を使わず useConfirm で確認し、accept でのみ DELETE する', async () => {
    const nativeConfirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', nativeConfirm)
    const wrapper = await mountList('team', 'ja', RecordingTable as never)
    mocks.api.mockClear()
    await wrapper.get('button[aria-label="' + t('inviteToken.revoke') + '"]').trigger('click')
    await flushPromises()
    expect(nativeConfirm).not.toHaveBeenCalled()
    expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
    const options = mocks.confirmRequire.mock.calls[0]![0]
    expect(options.message).toBe(t('inviteToken.revokeConfirm'))
    expect(mocks.api).not.toHaveBeenCalled() // 確認前は DELETE しない
    options.reject?.()
    await flushPromises()
    expect(mocks.api).not.toHaveBeenCalled()
    options.accept()
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/private-scope/invite-tokens/99', {
      method: 'DELETE',
    })
  })
  it('ALIC-6: 無効化まわりの用語が言語ごとに統一されている（ボタン名と links_hint が同じ語幹）', () => {
    const stems: Record<keyof typeof messages, RegExp> = {
      ja: /無効/,
      en: /[Rr]evoke/,
      zh: /撤销/,
      ko: /비활성화/,
      es: /[Rr]evo[cq]/,
      de: /[Dd]eaktivier/,
    }
    for (const locale of Object.keys(stems) as Array<keyof typeof messages>) {
      const i18n = createI18n({ legacy: false, locale, messages }).global
      for (const key of [
        'inviteToken.revoke',
        'inviteToken.revokeConfirm',
        'inviteToken.revokeSuccess',
        'inviteToken.revokeError',
        'secret_token.list.links_hint',
      ])
        expect(i18n.t(key), `${locale}:${key}`).toMatch(stems[locale]!)
    }
  })
})

afterAll(() => vi.unstubAllGlobals())
