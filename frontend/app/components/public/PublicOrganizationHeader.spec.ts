import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { PublicOrganizationResponse } from '~/types/public'
import PublicOrganizationHeader from './PublicOrganizationHeader.vue'

/**
 * F01.2.1 8-A — 公開組織ヘッダーの actions スロット（公開ページの「チームとして加盟を申請」置き場。AC-A11）。
 */

const organization = {
  id: 1,
  name: '組織1',
  prefecture: '東京都',
  city: '千代田区',
} as unknown as PublicOrganizationResponse

describe('PublicOrganizationHeader actions スロット', () => {
  it('PH-01: actions スロットの内容を描画する', async () => {
    const wrapper = await mountSuspended(PublicOrganizationHeader, {
      props: { organization },
      slots: { actions: '<button data-testid="slot-action">申請</button>' },
    })
    expect(wrapper.find('[data-testid="slot-action"]').exists()).toBe(true)
  })

  it('PH-02: スロットが無ければ操作欄を描画しない', async () => {
    const wrapper = await mountSuspended(PublicOrganizationHeader, { props: { organization } })
    expect(wrapper.find('[data-testid="slot-action"]').exists()).toBe(false)
  })
})
