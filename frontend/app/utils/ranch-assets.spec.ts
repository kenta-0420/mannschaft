import { describe, expect, it } from 'vitest'
import { developmentRanchAssets, ranchAtlasFrameRect, resolveRanchAsset, resolveRanchReaction } from './ranch-assets'
import type { DinosaurSummary } from '~/types/ranch'
const dinosaur: DinosaurSummary = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', speciesKey: 'DEV_TRICERATOPS', variantKey: 'DEV_ORANGE_96_WALK_V1', stage: 'BABY', habitat: 'LAND', name: 'Synthetic', xp: '0', nextStageXp: '40', version: '0', namedAt: null, speciesCatalogVersion: '2', egg: null }
describe('有限Scene素材と反応の境界', () => {
 it('専用dev catalog2の同個体2styleだけ明示開発flagで選択し、本番gateは閉じる', () => {
  expect(resolveRanchAsset(dinosaur, 'PIXEL', { production: false, isolatedDevelopment: true, publicationEnabled: false })?.entry.assetKey).toBe('dev-triceratops-orange-pixel96-walk-v1')
  expect(resolveRanchAsset(dinosaur, 'PAINT_2D', { production: false, isolatedDevelopment: true, publicationEnabled: false })?.entry.assetKey).toBe('dev-triceratops-orange-paint2d-walk-v1')
  expect(resolveRanchAsset(dinosaur, 'PIXEL', { production: false, isolatedDevelopment: false, publicationEnabled: false })).toBeNull()
  expect(resolveRanchAsset({ ...dinosaur, stage: 'ADULT' }, 'PIXEL', { production: false, isolatedDevelopment: true, publicationEnabled: false })).toBeNull()
  expect(resolveRanchAsset({ ...dinosaur, speciesCatalogVersion: '1' }, 'PIXEL', { production: false, isolatedDevelopment: true, publicationEnabled: false })).toBeNull()
  expect(resolveRanchAsset(dinosaur, 'PAINT_2D', { production: true, isolatedDevelopment: true, publicationEnabled: true })).toBeNull()
 })
 it('PIXEL96と非均等2D境界は同個体BABY一段階だけの別entry', () => {
  const pixel = developmentRanchAssets.find(value => value.renderStyle === 'PIXEL')
  const paint = developmentRanchAssets.find(value => value.renderStyle === 'PAINT_2D')
  if (!pixel || !paint) throw new Error('FINITE_DEV_ENTRY_MISSING')
  expect([pixel.speciesKey,pixel.variantKey,pixel.stage]).toEqual([paint.speciesKey,paint.variantKey,paint.stage])
  expect(ranchAtlasFrameRect(pixel, 7)).toEqual({ x: 672, y: 0, width: 96, height: 96 })
  expect(ranchAtlasFrameRect(paint, 0)).toEqual({ x: 0, y: 0, width: 444, height: 444 })
  expect(ranchAtlasFrameRect(paint, 7)).toEqual({ x: 1331, y: 444, width: 443, height: 443 })
  expect(ranchAtlasFrameRect(paint, 8)).toBeNull()
 })
 it('反応キーは固定値とstageの組合せだけで、URL文字列を解釈しない', () => {
  expect(resolveRanchReaction('/arbitrary.png', 'BABY')).toBeNull()
  expect(resolveRanchReaction('EGG_TOUCH', 'BABY')).toBeNull()
  expect(resolveRanchReaction('DINOSAUR_TOUCH', 'EGG')).toBeNull()
  expect(resolveRanchReaction('EGG_TOUCH', 'EGG')?.durationMs).toBe(600)
  expect(resolveRanchReaction('DINOSAUR_TOUCH', 'ADULT')?.durationMs).toBe(700)
 })
})
