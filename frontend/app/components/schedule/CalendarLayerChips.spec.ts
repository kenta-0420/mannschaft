import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarLayerChips from './CalendarLayerChips.vue'
import type { ScopeOption } from '~/composables/useMyCalendarData'

const layer = (over: Partial<ScopeOption> & { label: string; value: string }): ScopeOption => ({
  scopeType: 'TEAM',
  scopeId: '42',
  color: '#7C3AED',
  colorSource: 'LAYER_AUTO',
  isFallback: false,
  layerScopeId: 42,
  ...over,
})

const personal = layer({
  label: '個人', value: 'PERSONAL:0', scopeType: 'PERSONAL', scopeId: '0',
  layerScopeId: 0, color: '#059669',
})
const team = layer({ label: '青葉FC', value: 'TEAM:42' })
const emptyTeam = layer({ label: '予定ゼロ団', value: 'TEAM:99', scopeId: '99', layerScopeId: 99, color: '#DB2777' })
const fallback: ScopeOption = {
  label: 'レイヤー外', value: 'TEAM:777', scopeType: 'TEAM', scopeId: '777',
  color: '#94A3B8', isFallback: true,
}

function mount(options: ScopeOption[], selected: string[] = []) {
  return mountSuspended(CalendarLayerChips, {
    props: { options, selected },
    global: { stubs: { MultiSelect: { template: '<div class="ms-stub" />' } } },
  })
}

describe('レイヤーチップ列（F03.19 §6.4）', () => {
  it('AC-02: 予定0件のレイヤーもチップとして名前つきで表示する', async () => {
    const w = await mount([personal, team, emptyTeam])

    expect(w.find('[data-testid="layer-chip-TEAM:99"]').exists()).toBe(true)
    expect(w.find('[data-testid="layer-chip-TEAM:99"]').text()).toContain('予定ゼロ団')
  })

  it('色だけに識別を担わせない — 全チップが色ドットと名前を併記する（§3.3）', async () => {
    const w = await mount([personal, team, emptyTeam, fallback])

    const chips = w.findAll('[data-testid^="layer-chip-TEAM"], [data-testid^="layer-chip-PERSONAL"]')
    expect(chips.length).toBe(4)
    for (const chip of chips) {
      expect(chip.find('[data-testid="layer-chip-dot"]').exists()).toBe(true)
      expect(chip.text().trim().length).toBeGreaterThan(0)
    }
  })

  it('選択中は塗り、非選択は輪郭のみ（色の情報は両方に残る）', async () => {
    const w = await mount([personal, team], ['TEAM:42'])

    const selectedStyle = w.find('[data-testid="layer-chip-TEAM:42"]').attributes('style') ?? ''
    const unselectedStyle = w.find('[data-testid="layer-chip-PERSONAL:0"]').attributes('style') ?? ''
    // 塗り: 背景がレイヤー色
    expect(selectedStyle).toContain('background-color: #7C3AED')
    // 輪郭のみ: 背景は透明だが枠線にレイヤー色が残る
    expect(unselectedStyle).toContain('background-color: transparent')
    expect(unselectedStyle).toContain('border-color: #059669')
  })

  it('チップのクリックで toggle を発火する', async () => {
    const w = await mount([personal, team])

    await w.find('[data-testid="layer-chip-TEAM:42"]').trigger('click')

    expect(w.emitted('toggle')?.[0]).toEqual(['TEAM:42'])
  })

  it('右クリックで色変更ポップオーバーが開き、パレット12色と「自動に戻す」が並ぶ', async () => {
    const w = await mount([personal, team])
    expect(w.find('[data-testid="layer-color-popover-TEAM:42"]').exists()).toBe(false)

    await w.find('[data-testid="layer-chip-TEAM:42"]').trigger('contextmenu')

    expect(w.find('[data-testid="layer-color-popover-TEAM:42"]').exists()).toBe(true)
    expect(w.findAll('[data-testid^="layer-color-#"]')).toHaveLength(12)
    expect(w.find('[data-testid="layer-color-auto-TEAM:42"]').exists()).toBe(true)
  })

  it('色を選ぶと color を、自動に戻すと reset-color を発火する（PATCH と DELETE の使い分け）', async () => {
    const w = await mount([personal, team])
    await w.find('[data-testid="layer-chip-more-TEAM:42"]').trigger('click')

    await w.find('[data-testid="layer-color-#CA8A04"]').trigger('click')
    expect(w.emitted('color')?.[0]).toEqual([{ scopeType: 'TEAM', scopeId: 42, color: '#CA8A04' }])

    await w.find('[data-testid="layer-chip-more-TEAM:42"]').trigger('click')
    await w.find('[data-testid="layer-color-auto-TEAM:42"]').trigger('click')
    expect(w.emitted('reset-color')?.[0]).toEqual([{ scopeType: 'TEAM', scopeId: 42 }])
  })

  it('フォールバックチップは色変更を開かない（表示／非表示のみ・§6.4）', async () => {
    const w = await mount([personal, fallback])

    expect(w.find('[data-testid="layer-chip-more-TEAM:777"]').exists()).toBe(false)
    await w.find('[data-testid="layer-chip-TEAM:777"]').trigger('contextmenu')
    expect(w.find('[data-testid="layer-color-popover-TEAM:777"]').exists()).toBe(false)
  })

  it('5件以下はチップ列のまま（畳まない）', async () => {
    const opts = Array.from({ length: 5 }, (_, i) =>
      layer({ label: `T${i}`, value: `TEAM:${i}`, scopeId: String(i), layerScopeId: i }))
    const w = await mount(opts)

    expect(w.findAll('[data-testid^="layer-chip-TEAM"]')).toHaveLength(5)
    expect(w.find('[data-testid="layer-count"]').exists()).toBe(false)
  })

  it('5件超は MultiSelect へ畳むが、件数「N件のレイヤー」で存在を隠さない', async () => {
    const opts = Array.from({ length: 6 }, (_, i) =>
      layer({ label: `T${i}`, value: `TEAM:${i}`, scopeId: String(i), layerScopeId: i }))
    const w = await mount(opts)

    expect(w.findAll('[data-testid^="layer-chip-TEAM"]')).toHaveLength(0)
    expect(w.find('.ms-stub').exists()).toBe(true)
    // 件数は既定値（FILTER_OVERFLOW=5）と重ならない 6 を使い、偶然一致の偽緑を避ける
    expect(w.find('[data-testid="layer-count"]').text()).toContain('6')
  })
})
