import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ConfirmableCirculationGuide from './ConfirmableCirculationGuide.vue'

describe('ConfirmableCirculationGuide', () => {
  it('クイック確認から回覧板への用途説明とリンクを表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableCirculationGuide, {
      props: {
        currentFeature: 'quickConfirm',
        targetPath: '/teams/team-a/circulation',
      },
    })

    expect(wrapper.text()).toContain('短い連絡への確認を集める機能です')
    expect(wrapper.get('a').attributes('href')).toBe('/teams/team-a/circulation')
    expect(wrapper.text()).toContain('回覧板を開く')
  })

  it('回覧板からクイック確認への用途説明とリンクを表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableCirculationGuide, {
      props: {
        currentFeature: 'circulation',
        targetPath: '/organizations/org-a/settings/confirmable-notifications',
      },
    })

    expect(wrapper.text()).toContain('文書への押印や回覧順序を管理する機能です')
    expect(wrapper.get('a').attributes('href')).toBe('/organizations/org-a/settings/confirmable-notifications')
    expect(wrapper.text()).toContain('クイック確認を開く')
  })
})
