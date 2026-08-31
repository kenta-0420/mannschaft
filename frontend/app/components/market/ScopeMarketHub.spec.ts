import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'app/components/market/ScopeMarketHub.vue'), 'utf8')

describe('ScopeMarketHub 管理操作', () => {
  it('チーム・組織の管理市から札作成と参加者管理へ進める', () => {
    expect(source).toContain('getScopeMarketRoutes')
    expect(source).toContain('market-${scopeType}-post-link')
    expect(source).toContain('RecruitmentParticipantManager')
  })

  it('管理権限者が終了前の札を取り下げられる', () => {
    expect(source).toContain('recruitmentApi.cancelListing')
    expect(source).toContain('market-listing-cancel-${listing.id}')
    expect(source).toContain("!['CANCELLED', 'AUTO_CANCELLED', 'COMPLETED'].includes(listing.status)")
  })
})
