import { defineComponent, h } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CampaignDetail from '~/pages/organizations/[slug]/advertiser/campaigns/[campaignId].vue'
import type { CampaignPerformanceResponse } from '~/types/advertiser'

const { performance, creatives, breakdown, exportCsv } = vi.hoisted(() => ({ performance: vi.fn(), creatives: vi.fn(), breakdown: vi.fn(), exportCsv: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'test-org', campaignId: '7' } }))
mockNuxtImport('useAdvertiserApi', () => () => ({ getCampaignPerformance: performance, getCreativeComparison: creatives, getBreakdown: breakdown, exportCampaignCsv: exportCsv }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => {
  const suffix = key.split('.').at(-1) ?? ''
  return ({ default_title: 'Campaign detail', date_from_placeholder: 'From', date_to_placeholder: 'To', csv_button: 'CSV', retry: 'Retry', load_failed: 'Campaign data could not be loaded', back_to_dashboard: 'Back to dashboard' } as Record<string, string>)[suffix] ?? key
} }))
const HeaderStub = defineComponent({ props: { title: String, backTo: String }, setup(props, { slots }) { return () => h('header', { 'data-testid': 'campaign-header' }, [h('a', { href: props.backTo }, 'Back'), h('h1', props.title), slots.default?.(), h('div', { 'data-testid': 'campaign-actions' }, slots.actions?.())]) } })
const result = { campaignId: 7, campaignName: 'Campaign Alpha', status: 'ACTIVE', pricingModel: 'CPM', summary: { totalImpressions: 0, totalClicks: 0, avgCtr: 0, totalCost: 0, avgCpm: null, avgCpc: null, conversions: null, conversionRate: null, costPerConversion: null }, benchmark: null, points: [] } satisfies CampaignPerformanceResponse
const cleanup: Array<() => void> = []
async function mountPage() {
  const wrapper = await mountSuspended(CampaignDetail, { global: { stubs: { PageHeader: HeaderStub, CampaignMetricsCards: true, CampaignDataTables: true } } })
  cleanup.push(() => wrapper.unmount())
  await flushPromises()
  return wrapper
}
describe('CMP-261014-2035 C1 組織広告詳細', () => {
  beforeEach(() => {
    performance.mockReset().mockResolvedValue({ data: result })
    creatives.mockReset().mockResolvedValue({ data: { campaignId: 7, creatives: [], winner: null } })
    breakdown.mockReset().mockResolvedValue({ data: { campaignId: 7, breakdownBy: 'PREFECTURE', items: [] } })
    exportCsv.mockReset().mockResolvedValue(new Blob(['test']))
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-21T03:00:00Z'))
  })
  afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
  it('PageHeaderにTagとactionsを配置し組織広告一覧へ戻れる', async () => {
    const wrapper = await mountPage()
    const header = wrapper.get('[data-testid="campaign-header"]')
    expect(header.get('h1').text()).toBe('Campaign Alpha')
    expect(header.get('a').attributes('href')).toBe('/organizations/test-org/advertiser')
    const inputs = header.get('[data-testid="campaign-actions"]').findAll('input')
    expect(inputs).toHaveLength(2)
    expect(inputs.map(input => input.attributes('placeholder'))).toEqual(['From', 'To'])
    expect(header.get('[data-testid="campaign-actions"]').get('button').text()).toBe('CSV')
    expect(wrapper.text()).not.toMatch(/[ぁ-んァ-ヶ一-龠]/)
  })
  it('CSVクリックはORGANIZATIONとslugでAPIを呼びダウンロードを開始する', async () => {
    const createUrl = vi.fn(() => 'blob:cmp2035')
    const revokeUrl = vi.fn()
    const OriginalUrl = URL
    vi.stubGlobal('URL', class extends OriginalUrl { static createObjectURL = createUrl; static revokeObjectURL = revokeUrl })
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="campaign-actions"]').get('button').trigger('click')
    await flushPromises()
    expect(exportCsv).toHaveBeenCalledExactlyOnceWith('ORGANIZATION', 'test-org', 7, '2026-09-21', '2026-10-21')
    expect(createUrl).toHaveBeenCalledOnce()
    expect(download).toHaveBeenCalledOnce()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:cmp2035')
  })
  it('取得失敗の再試行は初回と同じ3 APIを再取得して詳細へ復帰する', async () => {
    performance.mockRejectedValueOnce(new Error('取得失敗'))
    const wrapper = await mountPage()
    const retry = wrapper.findAll('button').find(button => button.text() === 'Retry')
    expect(retry, '再試行操作を必ず表示する').toBeDefined()
    await retry!.trigger('click')
    await flushPromises()
    expect(performance).toHaveBeenCalledTimes(2)
    expect(creatives).toHaveBeenCalledTimes(2)
    expect(breakdown).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="campaign-header"]').get('h1').text()).toBe('Campaign Alpha')
    expect(wrapper.text()).not.toContain('Campaign data could not be loaded')
  })
  it('取得失敗はロケール失敗文言と戻り先を表示する', async () => {
    performance.mockRejectedValue(new Error('取得失敗'))
    const wrapper = await mountPage()
    expect(wrapper.get('[data-testid="campaign-header"]').get('h1').text()).toBe('Campaign detail')
    expect(wrapper.text()).toContain('Campaign data could not be loaded')
    expect(wrapper.text()).toContain('Back to dashboard')
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toContain('/organizations/test-org/advertiser')
    expect(wrapper.text()).not.toMatch(/[ぁ-んァ-ヶ一-龠]/)
  })
})
