import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'app/pages/market/index.vue'), 'utf8')

describe('/market 公開市の主体別導線', () => {
  it('札を立てる操作は個人市の作成フォームへ遷移する', () => {
    expect(source).toContain("navigateTo({ path: '/me/market', query: { create: 'true' } })")
    expect(source).not.toContain("@click=\"navigateTo('/dashboard')\"")
  })

  it('個人・チーム・組織の札主区分フィルターを備える', () => {
    expect(source).toContain('selectedOwnerType')
    expect(source).toContain('market-owner-type-select')
    expect(source).toContain("'PERSONAL'")
    expect(source).toContain("'TEAM'")
    expect(source).toContain("'ORGANIZATION'")
  })
})
