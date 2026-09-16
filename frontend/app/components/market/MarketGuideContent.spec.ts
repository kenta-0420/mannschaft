import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const contentSource = readFileSync(
  resolve(process.cwd(), 'app/components/market/MarketGuideContent.vue'),
  'utf8',
)
const modalSource = readFileSync(
  resolve(process.cwd(), 'app/components/market/MarketGuideModal.vue'),
  'utf8',
)
const marketPageSource = readFileSync(resolve(process.cwd(), 'app/pages/market/index.vue'), 'utf8')
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const guideSectionKeys = [
  'what',
  'search',
  'apply',
  'personal_create',
  'visibility',
  'team_organization',
  'cancellation',
] as const

describe('市ヘルプモーダル契約', () => {
  it('公開市ページのヘルプ操作から既存モーダルを開く', () => {
    expect(marketPageSource).toContain('help @help="showGuide = true"')
    expect(marketPageSource).toContain('<MarketGuideModal v-model:visible="showGuide" />')
    expect(modalSource).toContain('data-testid="market-guide-modal"')
  })

  it('7枚の色付きカードと番号付き手順を表示する', () => {
    expect(contentSource.match(/<SectionCard>/g) ?? []).toHaveLength(7)
    expect(contentSource.match(/list-decimal/g) ?? []).toHaveLength(7)
    expect(contentSource).toContain('bg-blue-100')
    expect(contentSource).toContain('bg-orange-100')
  })

  it.each(locales)('%s ロケールに同一の市ガイドキーがある', (locale) => {
    const localeSource = readFileSync(
      resolve(process.cwd(), `app/locales/${locale}/market.json`),
      'utf8',
    )
    const guide = JSON.parse(localeSource).market.market_guide as Record<string, unknown>

    expect(Object.keys(guide)).toEqual(['page_title', ...guideSectionKeys])
    expect(guide.personal_create).toBeDefined()
    expect(guide.visibility).toBeDefined()
    expect(guide.team_organization).toBeDefined()
    expect(guide.cancellation).toBeDefined()
  })

  it('応募、開催場所必須、検索ボタンと締切順を案内する', () => {
    expect(contentSource).toContain('market.market_guide.apply.steps')
    expect(contentSource).toContain('market.market_guide.personal_create.steps')

    const guide = JSON.parse(
      readFileSync(resolve(process.cwd(), 'app/locales/ja/market.json'), 'utf8'),
    ).market.market_guide
    expect(guide.apply.steps.step3).toContain('再応募できません')
    expect(guide.personal_create.steps.step2).toContain('必須の開催場所')
    expect(guide.search.steps.step3).toContain('締切が近い順')
    expect(guide.search.steps.step4).toContain('検索ボタン')
  })
})
