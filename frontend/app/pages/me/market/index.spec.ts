import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'app/pages/me/market/index.vue'), 'utf8')
const featureGates = readFileSync(resolve(process.cwd(), 'app/constants/featureGates.ts'), 'utf8')

describe('/me/market 個人市ページ契約', () => {
  it('認証済みSPAとして本人専用APIだけを利用する', () => {
    expect(source).toContain("definePageMeta({ middleware: 'auth' })")
    expect(featureGates).toContain("'/me/market'")
    expect(source).toContain('api.listMyMarketListings')
    expect(source).toContain('api.listMyMarketMatches')
    expect(source).not.toContain('getMyListings(')
    expect(source).not.toContain('listListingParticipants(')
  })

  it('空状態と本人向け作成導線を備える', () => {
    expect(source).toContain('DashboardEmptyState')
    expect(source).toContain("t('market.personal.empty')")
    expect(source).toContain('showCreateForm = true')
  })

  it('個人札では決済と公開操作を表示しない', () => {
    expect(source).toContain('hide-payment')
    expect(source).toContain('paymentEnabled: false')
    expect(source).toContain("visibility: 'SCOPE_ONLY'")
    expect(source).not.toContain('publishListing')
    expect(source).not.toContain('paymentEnabled = true')
  })
})
