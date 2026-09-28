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

    expect(wrapper.text()).toContain('Use this for short messages and acknowledgements.')
    expect(wrapper.get('a').attributes('href')).toBe('/teams/team-a/circulation')
    expect(wrapper.text()).toContain('Open Circulation')
  })

  it('回覧板からクイック確認への用途説明とリンクを表示する', async () => {
    const wrapper = await mountSuspended(ConfirmableCirculationGuide, {
      props: {
        currentFeature: 'circulation',
        targetPath: '/organizations/org-a/settings/confirmable-notifications',
      },
    })

    expect(wrapper.text()).toContain('Use this for documents that require stamps or a routing order.')
    expect(wrapper.get('a').attributes('href')).toBe('/organizations/org-a/settings/confirmable-notifications')
    expect(wrapper.text()).toContain('Open Quick Confirm')
  })
})
