import { describe, expect, it } from 'vitest'
import type { DinosaurSummary, RenderStyle } from '~/types/ranch'
import { developmentRanchAssets, productionRanchAssetsRegistered, resolveRanchAsset } from './ranch-assets'
import { developmentRanchClips, ranchDevelopmentClipFrameRect, resolveRanchDevelopmentClip } from './ranch-development-clips'

const dinosaur: DinosaurSummary = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', speciesKey: 'DEV_CLIP_ALLOSAURUS', variantKey: 'DEV_BASE_16_CLIPS_V1', stage: 'BABY', habitat: 'LAND', name: 'Synthetic', xp: '0', nextStageXp: '40', version: '0', namedAt: null, speciesCatalogVersion: '3', egg: null }
const context = { production: false, isolatedDevelopment: true, clipsEnabled: true }

describe('隔離開発v3クリップの完全一致境界', () => {
  it('16種3stage2styleが同個体の4actionとfallbackへ明示解決される', () => {
    expect(developmentRanchClips).toHaveLength(96)
    expect(new Set(developmentRanchClips.map(entry => entry.speciesKey)).size).toBe(16)
    for (const entry of developmentRanchClips) {
      const resolved = resolveRanchDevelopmentClip({ ...dinosaur, speciesKey: entry.speciesKey, stage: entry.stage }, entry.renderStyle, context)
      expect(resolved).toEqual({ dinosaurId: dinosaur.id, entry })
      expect(Object.keys(entry.clips).sort()).toEqual(['feed', 'happy', 'idle', 'walk'])
      expect(entry.fallback.src).toContain(`/fallbacks/${entry.sourceSpecies}/`)
      expect(entry.clips.idle.phaseStarts[0]).toBe(0)
    }
  })

  it('native idleの不均等blink境界を共通4等分へ変換しない', () => {
    expect(resolveRanchDevelopmentClip(dinosaur, 'PIXEL', context)?.entry.clips.idle.phaseStarts).toEqual([0, 0.78, 0.84, 0.88])
  })

  it.each([
    { ...context, production: true },
    { ...context, isolatedDevelopment: false },
    { ...context, clipsEnabled: false },
  ])('productionまたは無効flagは公開しない %j', flags => {
    expect(resolveRanchDevelopmentClip(dinosaur, 'PIXEL', flags)).toBeNull()
  })

  it.each([
    { id: 'not-a-uuid' }, { speciesKey: null }, { variantKey: null }, { speciesCatalogVersion: null },
    { speciesKey: 'DEV_CLIP_UNKNOWN' }, { speciesKey: 'DEV_CLIP_ALLOSAURUS_extra' },
    { variantKey: 'BASE_ONLY' }, { variantKey: 'DEV_ORANGE_96_WALK_V1' },
    { speciesCatalogVersion: '2' }, { speciesCatalogVersion: '03' }, { stage: 'EGG' as const },
  ])('identityの欠損・未知・旧catalogを推定しない %j', patch => {
    expect(resolveRanchDevelopmentClip({ ...dinosaur, ...patch }, 'PIXEL', context)).toBeNull()
  })

  it('未知stage/styleは画像を返さない', () => {
    expect(resolveRanchDevelopmentClip({ ...dinosaur, stage: 'UNKNOWN' as DinosaurSummary['stage'] }, 'PIXEL', context)).toBeNull()
    expect(resolveRanchDevelopmentClip(dinosaur, 'UNKNOWN' as RenderStyle, context)).toBeNull()
  })

  it('native4frame境界を96/384単位で切り出し、範囲外や破損寸法を拒否する', () => {
    const pixel = resolveRanchDevelopmentClip(dinosaur, 'PIXEL', context)!.entry.clips.walk
    const paint = resolveRanchDevelopmentClip(dinosaur, 'PAINT_2D', context)!.entry.clips.walk
    expect(ranchDevelopmentClipFrameRect(pixel, 3)).toEqual({ x: 288, y: 0, width: 96, height: 96 })
    expect(ranchDevelopmentClipFrameRect(paint, 3)).toEqual({ x: 1152, y: 0, width: 384, height: 384 })
    for (const frame of [-1, 4, 1.5, NaN]) expect(ranchDevelopmentClipFrameRect(pixel, frame)).toBeNull()
    expect(ranchDevelopmentClipFrameRect({ ...pixel, sourceWidth: 383 }, 0)).toBeNull()
  })

  it('明示waterはSEA、spinosaurusはLANDでpartial384floorを保持する', () => {
    expect(developmentRanchClips.find(entry => entry.sourceSpecies === 'archelon')?.habitat).toBe('SEA')
    expect(developmentRanchClips.find(entry => entry.sourceSpecies === 'spinosaurus')?.habitat).toBe('LAND')
    const triceratops = developmentRanchClips.find(entry => entry.sourceSpecies === 'triceratops' && entry.stage === 'JUVENILE' && entry.renderStyle === 'PIXEL')!
    expect(triceratops.clips.walk.sourceFloorByFrame384).toEqual([null, null, 347, null])
  })

  it('既存v2のidentity・bytehash・8frameと本番512gateを維持する', () => {
    const legacy = { ...dinosaur, speciesKey: 'DEV_TRICERATOPS', variantKey: 'DEV_ORANGE_96_WALK_V1', speciesCatalogVersion: '2' }
    const oldContext = { production: false, isolatedDevelopment: true, publicationEnabled: false }
    expect(resolveRanchAsset(legacy, 'PIXEL', oldContext)?.entry.sourceSha256).toBe('8ad7de84f1d0f495f9aeda86175f9c914fd0cbb788079b4f3c7e101f91358117')
    expect(developmentRanchAssets.map(entry => entry.frames)).toEqual([8, 8])
    expect(resolveRanchDevelopmentClip(legacy, 'PIXEL', context)).toBeNull()
    expect(resolveRanchAsset(dinosaur, 'PIXEL', oldContext)).toBeNull()
    expect(productionRanchAssetsRegistered()).toBe(false)
  })
})
