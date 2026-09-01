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

  it('フィルター変更では検索せず、検索ボタンのsubmitで適用済み条件を更新する', () => {
    expect(source).toContain('@submit.prevent="applyFilters"')
    expect(source).toContain('appliedFilters.value = draftFilters()')
    expect(source).toContain('data-testid="market-search-button"')
    expect(source).not.toContain('keywordTimer')
    expect(source).not.toContain('@change="onCategoryChange"')
    expect(source).not.toContain('@change="onOwnerTypeChange"')
  })

  it('地域指定時は地域なしを除外し、全国時だけ含める', () => {
    expect(source).toContain(
      'includeRegionNone: filters.prefecture == null && filters.city == null',
    )
  })

  it('締切順をURLとAPIへ同期し、古い並行応答を破棄する', () => {
    expect(source).toContain("value: 'DEADLINE_ASC'")
    expect(source).toContain("value: 'DEADLINE_DESC'")
    expect(source).toContain('q.sort = filters.sort')
    expect(source).toContain('sort: filters.sort')
    expect(source).toContain('sequence !== listingRequestSequence')
  })

  it('初回だけ全面ローディングし、再検索中は既存一覧と検索ボタンを維持する', () => {
    expect(source).toContain('<PageLoading v-if="initialLoading" />')
    expect(source).toContain(':loading="searching"')
    expect(source).not.toContain('<PageLoading v-if="loading" />')
  })

  it('カテゴリ名はi18nキー文字列ではなく現在ロケールの翻訳ラベルを表示する', () => {
    expect(source).toContain('label: t(category.nameI18nKey)')
    expect(source).toContain(':options="categoryOptions"')
    expect(source).toContain('option-label="label"')
    expect(source).not.toContain('option-label="nameI18nKey"')
  })
})
