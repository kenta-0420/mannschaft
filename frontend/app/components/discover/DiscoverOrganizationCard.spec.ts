import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { PublicOrganizationSearchResult } from '~/types/public'
import DiscoverOrganizationCard from './DiscoverOrganizationCard.vue'

/**
 * F01.2.1 8-A（AC-A10）— 公開組織検索カードの「加盟受付中」バッジの FE-UT。
 */

function makeOrg(over: Partial<PublicOrganizationSearchResult> = {}): PublicOrganizationSearchResult {
  return {
    id: 1,
    slug: 'org-1',
    name: '組織1',
    iconUrl: null,
    memberCount: 5,
    lastPostDate: null,
    ...over,
  }
}

const stubs = { Card: { template: '<div><slot name="content" /></div>' }, Button: true, NuxtLink: { template: '<a><slot /></a>' } }

describe('DiscoverOrganizationCard 加盟受付中バッジ', () => {
  it('DC-01: acceptingTeamApplications=true のときバッジが付く', async () => {
    const wrapper = await mountSuspended(DiscoverOrganizationCard, {
      props: { organization: makeOrg({ acceptingTeamApplications: true }) },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="accepting-badge"]').exists()).toBe(true)
  })

  it('DC-02: false または未設定ならバッジを付けない', async () => {
    const off = await mountSuspended(DiscoverOrganizationCard, {
      props: { organization: makeOrg({ acceptingTeamApplications: false }) },
      global: { stubs },
    })
    expect(off.find('[data-testid="accepting-badge"]').exists()).toBe(false)
    const unset = await mountSuspended(DiscoverOrganizationCard, {
      props: { organization: makeOrg() },
      global: { stubs },
    })
    expect(unset.find('[data-testid="accepting-badge"]').exists()).toBe(false)
  })
})
