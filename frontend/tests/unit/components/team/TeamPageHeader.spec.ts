import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import TeamPageHeader from '~/components/team/TeamPageHeader.vue'
import type { TeamResponse } from '~/types/team'

/**
 * TeamPageHeader.vue: 戻る矢印・モバイル用オーバーフロー（⋮）に名前があり、
 * レイアウトのナビゲーション（≡）と名前が衝突しないこと。
 */
beforeEach(() => {
  setActivePinia(createPinia())
})

describe('TeamPageHeader.vue アクセシブルネーム', () => {
  it('戻る矢印と ⋮ に異なる aria-label が付く', async () => {
    const { $i18n } = useNuxtApp()
    const t = (k: string) => $i18n.t(k)
    const wrapper = await mountSuspended(TeamPageHeader, {
      props: {
        team: { id: 1, slug: 'fc', name: 'FC' } as unknown as TeamResponse,
        displayName: 'FC',
        roleName: 'MEMBER',
        isAdmin: false,
        isAdminOrDeputy: false,
        followStatus: 'NONE',
        followLoading: false,
        followPermissionSyncError: false,
        joinRequestStatus: 'NONE',
        joinRequestLoading: false,
        templateLabel: {},
      } as unknown as InstanceType<typeof TeamPageHeader>['$props'],
    })
    const back = wrapper.find('button .pi-arrow-left').element.closest('button')!
    const more = wrapper.find('button .pi-ellipsis-v').element.closest('button')!
    expect(back.getAttribute('aria-label')).toBe(t('button.back'))
    expect(more.getAttribute('aria-label')).toBe(t('common.moreActions'))
    expect(t('common.moreActions')).not.toBe(t('common.openNavigation'))
    expect(t('common.moreActions')).not.toBe('common.moreActions')
  })
})
