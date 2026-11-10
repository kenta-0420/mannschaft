import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamSidebar from './TeamSidebar.vue'
import type { SidebarCategory } from '~/types/sidebar'

const { getPermissions, flags } = vi.hoisted(() => ({
  getPermissions: vi.fn(), flags: { enabled: true },
}))
vi.mock('~/composables/useAttendancePermissionsApi', () => ({
  useAttendancePermissionsApi: () => ({ getPermissions }),
}))
mockNuxtImport('useFeatureFlagStore', () => () => ({ isEnabled: () => flags.enabled }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

const base = { name: 'BaseSidebar', props: ['categories'], template: '<div />' }
async function mount(template: string | null = 'CLASS') {
  return mountSuspended(TeamSidebar, {
    props: { teamId: 'class-blue', teamNumericId: 42, teamTemplate: template },
    global: { stubs: { BaseSidebar: base } },
  })
}
async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}
function school(wrapper: Awaited<ReturnType<typeof mount>>) {
  return (wrapper.findComponent({ name: 'BaseSidebar' }).props('categories') as SidebarCategory[])
    .find(category => category.key === 'school')
}
const view = { canView: true, canRecordDaily: false, canRecordPeriod: false }
beforeEach(() => {
  flags.enabled = true
  getPermissions.mockReset().mockResolvedValue(view)
})

describe('学校メニューをSYSTEM_ADMINバイパス前に絞る', () => {
  it.each(['SCHOOL', 'CLASS'])('%sでは閲覧Vで同teamの5画面を出す', async (template) => {
    const wrapper = await mount(template)
    await flush()
    const items = school(wrapper)!.items
    expect(items.map(item => item.path)).toEqual([
      'school-attendance/daily-roll-call', 'school-attendance/period-attendance',
      'school-attendance/transition-alerts', 'school-attendance/statistics', 'school-attendance/notices',
    ])
    expect(items.every(item => item.gateKey === 'FEATURE_FAMILY_CARE_ENABLED' && item.moduleSlug === null)).toBe(true)
    expect(getPermissions).toHaveBeenCalledExactlyOnceWith('42')
    const categories = wrapper.findComponent({ name: 'BaseSidebar' }).props('categories') as SidebarCategory[]
    expect(categories.flatMap(category => category.items).find(item => item.path === 'voting'))
      .toMatchObject({ moduleSlug: 'voting', requiredRole: 'MEMBER' })
  })

  it.each([null, '', 'UNKNOWN', 'CLUB'])('非対象の種別%sでは資格照会も学校項目も出さない', async (template) => {
    const wrapper = await mount(template)
    await flush()
    expect(school(wrapper)).toBeUndefined()
    expect(getPermissions).not.toHaveBeenCalled()
  })

  it('ゲートOFF・未取得では資格があっても学校項目を出さない', async () => {
    flags.enabled = false
    const wrapper = await mount()
    await flush()
    expect(school(wrapper)).toBeUndefined()
    expect(getPermissions).not.toHaveBeenCalled()
  })

  it('SYSTEM_ADMIN単独・未委任・他tenant等の全false結果をロールで迂回しない', async () => {
    getPermissions.mockResolvedValue({ canView: false, canRecordDaily: false, canRecordPeriod: false })
    const wrapper = await mount()
    await flush()
    expect(school(wrapper)).toBeUndefined()
  })

  it('VなしPありは時限だけに絞る', async () => {
    getPermissions.mockResolvedValue({ canView: false, canRecordDaily: false, canRecordPeriod: true })
    const wrapper = await mount()
    await flush()
    expect(school(wrapper)!.items.map(item => item.path)).toEqual(['school-attendance/period-attendance'])
  })

  it('資格失敗は空の成功と区別し、再試行後にだけ項目を出す', async () => {
    getPermissions.mockRejectedValueOnce({ statusCode: 500 }).mockResolvedValueOnce(view)
    const wrapper = await mount()
    await flush()
    expect(school(wrapper)).toBeUndefined()
    await wrapper.find('[data-testid="school-sidebar-permission-error-retry"]').trigger('click')
    await flush()
    expect(school(wrapper)!.items).toHaveLength(5)
  })

  it('照会中とAからBへの切替で古い資格を表示しない', async () => {
    let finish!: (value: unknown) => void
    getPermissions.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const wrapper = await mount()
    expect(school(wrapper)).toBeUndefined()
    await wrapper.setProps({ teamId: 'club-b', teamNumericId: 9, teamTemplate: 'OTHER' })
    finish(view)
    await flush()
    expect(school(wrapper)).toBeUndefined()
  })
})
