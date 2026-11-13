import { describe, expect, it } from 'vitest'
import { approvedRanchVisualsEnabled, isCompleteProductionRanchManifest, productionRanchAssetsRegistered, resolveRanchProductionClip } from './ranch-assets'
import { productionRanchAssets } from './ranch-production-assets'
import type { DinosaurSummary } from '~/types/ranch'
const dinosaur: DinosaurSummary = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', speciesKey: 'allosaurus', variantKey: 'V1', stage: 'BABY', habitat: 'LAND', name: 'Saved', xp: '0', nextStageXp: '40', version: '1', namedAt: null, speciesCatalogVersion: '20261108', egg: null }
describe('正式素材の可用性と保存identity', () => {
  it('件数512でも重複・未承認・DEVidentity・版混在は登録readyにしない', () => {
    expect(isCompleteProductionRanchManifest(Array(512).fill(productionRanchAssets[0]))).toBe(false)
    for (const changed of [{ approval: 'DRAFT' }, { speciesKey: 'DEV_CLIP_ALLOSAURUS' }, { catalogVersion: '4' }]) {
      const entries = [...productionRanchAssets]
      entries[0] = { ...entries[0]!, ...changed } as typeof entries[number]
      expect(isCompleteProductionRanchManifest(entries)).toBe(false)
    }
  })
  it('同梱512だけで公開せずBEのstrict trueと取得成功を要求する', () => {
    expect(productionRanchAssetsRegistered()).toBe(true)
    expect(approvedRanchVisualsEnabled(undefined, true)).toBe(false)
    expect(approvedRanchVisualsEnabled(false, true)).toBe(false)
    expect(approvedRanchVisualsEnabled(true, false)).toBe(false)
    expect(approvedRanchVisualsEnabled(true, true)).toBe(true)
  })
  it('productionでも正式keyの4動作を選び、gateとcatalog不一致を拒否する', () => {
    const context = { production: true, isolatedDevelopment: false, publicationEnabled: true }
    const clip = resolveRanchProductionClip(dinosaur, 'PIXEL', context)
    expect(clip?.entry.rendererProfile).toBe('APPROVED_APPEARANCE_V4')
    expect(Object.keys(clip!.entry.clips)).toEqual(['idle', 'walk', 'feed', 'happy'])
    expect(clip?.entry.clips.walk.frames).toBe(4)
    expect(clip?.entry.logicalCanvas).toEqual({ width: 112, height: 112 })
    expect(resolveRanchProductionClip(dinosaur, 'PIXEL', { ...context, publicationEnabled: false })).toBeNull()
    expect(resolveRanchProductionClip({ ...dinosaur, speciesCatalogVersion: '4' }, 'PIXEL', context)).toBeNull()
    expect(resolveRanchProductionClip({ ...dinosaur, speciesKey: 'DEV_CLIP_ALLOSAURUS' }, 'PIXEL', context)).toBeNull()
    expect(resolveRanchProductionClip({ ...dinosaur, stage: 'EGG' }, 'PIXEL', context)).toBeNull()
  })
})
