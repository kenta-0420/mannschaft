/**
 * F06.4.1 PR-2 FE: チームテンプレート編集の「頑張り記録の指標」セクション。
 * 権限出し分け・保存リクエスト形・400 fieldErrors 表示・409 再取得・取得失敗/403。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import EffortTemplateSettingsSection from '~/components/effort/EffortTemplateSettingsSection.vue'
import ActivityTemplateForm from '~/components/activity/ActivityTemplateForm.vue'
import type { ActivityTemplate } from '~/types/activity'

// mountSuspended の初回は Nuxt 環境の冷間コンパイルで既定 5 秒を超えうる。
vi.setConfig({ testTimeout: 120000, hookTimeout: 120000 })

const { getMock, updateMock } = vi.hoisted(() => ({ getMock: vi.fn(), updateMock: vi.fn() }))
mockNuxtImport('useEffortTemplateSettingsApi', () => () => ({ get: getMock, update: updateMock }))

const settings = (version = 3, durationFieldKey: string | undefined = 'f1_aaaaaaaaaa') => ({
  data: {
    version,
    definition: {
      durationFieldKey,
      subjectFieldKey: undefined,
      fields: [
        { fieldKey: 'f1_aaaaaaaaaa', label: '勉強時間', type: 'NUMBER', semantic: 'DURATION', unit: 'MINUTE', aggregation: 'SUM' },
        { fieldKey: 'f2_bbbbbbbbbb', label: '科目', type: 'TEXT', semantic: 'TEXT', unit: 'NONE', aggregation: 'NONE' },
      ],
    },
  },
})

const httpError = (status: number, fieldErrors?: Array<{ field: string; message: string }>) =>
  Object.assign(new Error(`HTTP ${status}`), { status, data: { error: { fieldErrors } } })

async function mountSection() {
  const w = await mountSuspended(EffortTemplateSettingsSection, { props: { teamId: 7, templateId: 11 } })
  await flushPromises()
  return w
}

describe('EffortTemplateSettingsSection', () => {
  beforeEach(() => {
    getMock.mockReset()
    updateMock.mockReset()
  })

  it('読み込んだ設定を項目ごとに表示し、保存は全項目を version 付きで PUT する', async () => {
    getMock.mockResolvedValue(settings())
    updateMock.mockResolvedValue(settings(4))
    const w = await mountSection()
    expect(getMock).toHaveBeenCalledWith(7, 11)
    expect(w.find('[data-testid="effort-setting-row-f1_aaaaaaaaaa"]').exists()).toBe(true)
    expect(w.find('[data-testid="effort-setting-row-f2_bbbbbbbbbb"]').exists()).toBe(true)

    await w.find('[data-testid="effort-settings-save"]').trigger('click')
    await flushPromises()
    expect(updateMock).toHaveBeenCalledWith(7, 11, {
      version: 3,
      fields: [
        { fieldKey: 'f1_aaaaaaaaaa', semantic: 'DURATION', unit: 'MINUTE', aggregation: 'SUM' },
        { fieldKey: 'f2_bbbbbbbbbb', semantic: 'TEXT', unit: 'NONE', aggregation: 'NONE' },
      ],
      durationFieldKey: 'f1_aaaaaaaaaa',
      subjectFieldKey: undefined,
    })
    expect(w.find('[data-testid="effort-settings-saved"]').exists()).toBe(true)
  })

  it('400 の fieldErrors を該当項目と対応先に表示する', async () => {
    getMock.mockResolvedValue(settings())
    updateMock.mockRejectedValue(httpError(400, [
      { field: 'fields.f2_bbbbbbbbbb', message: 'この項目の型には指定の意味を設定できません' },
      { field: 'fields.durationFieldKey', message: '対応先が不正です' },
      { field: 'fields', message: '全体のエラー' },
    ]))
    const w = await mountSection()
    await w.find('[data-testid="effort-settings-save"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-setting-error-f2_bbbbbbbbbb"]').text()).toContain('指定の意味を設定できません')
    expect(w.find('[data-testid="effort-setting-duration-error"]').text()).toContain('対応先が不正です')
    expect(w.find('[data-testid="effort-setting-general-error"]').text()).toContain('全体のエラー')
    expect(w.find('[data-testid="effort-settings-saved"]').exists()).toBe(false)
  })

  it('409 は競合を表示し、再読込ボタンで最新を取り直す', async () => {
    getMock.mockResolvedValueOnce(settings(3)).mockResolvedValueOnce(settings(5))
    updateMock.mockRejectedValue(httpError(409))
    const w = await mountSection()
    await w.find('[data-testid="effort-settings-save"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-settings-conflict"]').exists()).toBe(true)

    await w.find('[data-testid="effort-settings-conflict"] button').trigger('click')
    await flushPromises()
    expect(getMock).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="effort-settings-conflict"]').exists()).toBe(false)

    updateMock.mockResolvedValue(settings(6))
    await w.find('[data-testid="effort-settings-save"]').trigger('click')
    await flushPromises()
    expect(updateMock).toHaveBeenLastCalledWith(7, 11, expect.objectContaining({ version: 5 }))
  })

  it('取得失敗はエラーと再試行を表示し、握りつぶさない', async () => {
    getMock.mockRejectedValueOnce(httpError(500)).mockResolvedValueOnce(settings())
    const w = await mountSection()
    expect(w.find('[data-testid="effort-settings-load-error"]').exists()).toBe(true)
    await w.find('[data-testid="effort-settings-load-error"] button').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-settings-load-error"]').exists()).toBe(false)
    expect(w.find('[data-testid="effort-setting-row-f1_aaaaaaaaaa"]').exists()).toBe(true)
  })

  it('取得が 403 のときはセクションごと出さない', async () => {
    getMock.mockRejectedValue(httpError(403))
    const w = await mountSection()
    expect(w.find('[data-testid="effort-template-settings"]').exists()).toBe(false)
    expect(w.find('[data-testid="effort-settings-load-error"]').exists()).toBe(false)
  })
})

describe('ActivityTemplateForm の出し分け', () => {
  const template: ActivityTemplate = {
    id: 11, scopeType: 'TEAM', scopeId: 7, name: 'T', description: null, icon: null, color: null,
    isParticipantRequired: false, defaultVisibility: null, sortOrder: 0, fields: [], createdAt: '', updatedAt: '',
  }

  beforeEach(() => {
    getMock.mockReset()
    getMock.mockResolvedValue(settings())
  })

  it('管理権限があり保存済みテンプレートのときだけ指標セクションを出す', async () => {
    const allowed = await mountSuspended(ActivityTemplateForm, { props: { template, visible: true, canManageEffortSettings: true } })
    await flushPromises()
    expect(allowed.findComponent(EffortTemplateSettingsSection).exists()).toBe(true)

    const denied = await mountSuspended(ActivityTemplateForm, { props: { template, visible: true, canManageEffortSettings: false } })
    await flushPromises()
    expect(denied.findComponent(EffortTemplateSettingsSection).exists()).toBe(false)

    const creating = await mountSuspended(ActivityTemplateForm, { props: { template: null, visible: true, canManageEffortSettings: true } })
    await flushPromises()
    expect(creating.findComponent(EffortTemplateSettingsSection).exists()).toBe(false)
  })
})
