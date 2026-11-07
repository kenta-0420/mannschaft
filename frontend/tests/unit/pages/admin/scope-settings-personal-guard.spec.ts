import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import LineSettingsPage from '~/pages/admin/line-settings.vue'
import ScheduleSettingsPage from '~/pages/admin/schedule-settings.vue'
import SnsSettingsPage from '~/pages/admin/sns-settings.vue'

/**
 * CMP-260903-1358 スコープ設定画面群の個人スコープガード。
 *
 * line / schedule / sns の設定画面は scopeId が確定してからでないと load() を呼ばないため、
 * 個人スコープ（scopeId = null）で開くと loading が true のまま解除されず永久スピナーになっていた。
 * 個人スコープでは対象外の案内を出し、API を呼ばず、操作ボタンも出さない。
 *
 * affiliate-settings / tax-settings は SYSTEM_ADMIN 専用でスコープに依存しない
 * （/api/v1/system-admin/*）ため対象外。reservation 設定は admin 配下に存在しない。
 *
 * テストケース一覧:
 *  PSG-LINE/SCHEDULE/SNS-001: 個人スコープでは案内が出て、スピナーも API 呼び出しも無い
 *  PSG-LINE/SCHEDULE/SNS-002（対照）: チームスコープでは案内を出さず API を呼ぶ
 */

const getConfig = vi.fn()
const getCategories = vi.fn()
const listFeeds = vi.fn()

vi.mock('~/composables/useLineConfigApi', () => ({
  useLineConfigApi: () => ({ getConfig, createConfig: vi.fn(), updateConfig: vi.fn(), deleteConfig: vi.fn() }),
}))
vi.mock('~/composables/useScheduleApi', () => ({
  useScheduleApi: () => ({ getCategories, createCategory: vi.fn() }),
}))
vi.mock('~/composables/useSnsFeedApi', () => ({
  useSnsFeedApi: () => ({
    listFeeds, createFeed: vi.fn(), updateFeed: vi.fn(), deleteFeed: vi.fn(), previewFeed: vi.fn(),
  }),
}))
vi.mock('~/composables/useAdminScopeGuard', () => ({ useAdminScopeGuard: () => undefined }))

const pages = [
  { name: 'LINE', page: LineSettingsPage, api: getConfig },
  { name: 'SCHEDULE', page: ScheduleSettingsPage, api: getCategories },
  { name: 'SNS', page: SnsSettingsPage, api: listFeeds },
]

async function settle() {
  await new Promise(resolve => setTimeout(resolve, 0))
  await new Promise(resolve => setTimeout(resolve, 0))
}

describe.each(pages)('pages/admin $name 設定 — 個人スコープガード', ({ name, page, api }) => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({ data: [] })
  })

  it(`PSG-${name}-001: 個人スコープでは案内を出し、スピナーも API 呼び出しも無い`, async () => {
    useScopeStore().setPersonalScope()
    const wrapper = await mountSuspended(page)
    await settle()

    expect(wrapper.find('[data-testid="personal-scope-unsupported"]').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'PageLoading' }).exists()).toBe(false)
    // 追加・保存・削除などの操作ボタンは出さない（押しても API を呼べないため）。
    expect(wrapper.text()).not.toMatch(/保存|削除|カテゴリを追加|アカウントを追加/)
    expect(api).not.toHaveBeenCalled()
  })

  it(`PSG-${name}-002（対照）: チームスコープでは案内を出さず API を呼ぶ`, async () => {
    useScopeStore().setTeamScope(1, 'テストチーム')
    const wrapper = await mountSuspended(page)
    await settle()

    expect(wrapper.find('[data-testid="personal-scope-unsupported"]').exists()).toBe(false)
    expect(api).toHaveBeenCalled()
  })
})
