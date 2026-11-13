import type { DinosaurSummary, RenderStyle } from '~/types/ranch'
import { productionRanchAssets } from './ranch-production-assets'
import type { RanchClipEntry } from './ranch-development-clips'
export interface RanchAssetEntry {
 readonly assetKey: string
 readonly speciesKey: string
 readonly variantKey: string
 readonly stage: DinosaurSummary['stage']
 readonly renderStyle: RenderStyle
 readonly catalogVersion: string
 readonly approval: 'DRAFT' | 'APPROVED'
 readonly origin: 'PRODUCTION' | 'ISOLATED_DEVELOPMENT'
 readonly src: string
 readonly fallbackSrc?: string
 readonly sourceWidth: number
 readonly sourceHeight: number
 readonly columnBoundaries: readonly number[]
 readonly rowBoundaries: readonly number[]
 readonly frames: number
 readonly sourceSha256: string
 readonly clipEntry?: RanchClipEntry
}
const productionManifest = productionRanchAssets
export function isCompleteProductionRanchManifest(entries: readonly RanchAssetEntry[]) {
 if (entries.length !== 512) return false
 const tuples = new Set<string>(), keys = new Set<string>(), species = new Map<string, Set<string>>()
 const version = entries[0]?.catalogVersion
 for (const entry of entries) {
  if (entry.approval !== 'APPROVED' || entry.origin !== 'PRODUCTION' || !version || entry.catalogVersion !== version || entry.speciesKey.startsWith('DEV_') || entry.variantKey.startsWith('DEV_')) return false
  const tuple = [entry.speciesKey, entry.variantKey, entry.stage, entry.renderStyle].join('/')
  if (tuples.has(tuple) || keys.has(entry.assetKey)) return false
  tuples.add(tuple); keys.add(entry.assetKey)
  if (!species.has(entry.speciesKey)) species.set(entry.speciesKey, new Set())
  species.get(entry.speciesKey)!.add(entry.variantKey)
 }
 if (species.size !== 16 || [...species.values()].some(variants => variants.size !== 4)) return false
 for (const [name, variants] of species) for (const variant of variants) for (const stage of ['EGG', 'BABY', 'JUVENILE', 'ADULT']) for (const style of ['PIXEL', 'PAINT_2D']) {
  if (!tuples.has([name, variant, stage, style].join('/'))) return false
 }
 return true
}
const productionManifestReady = isCompleteProductionRanchManifest(productionManifest)
export function productionRanchAssetsRegistered() { return productionManifestReady }
export function approvedRanchVisualsEnabled(available: boolean | undefined, loaded: boolean) {
 return loaded && available === true && productionRanchAssetsRegistered()
}
// COREで静的採択した専用開発catalog。本番64種の承認とは別に扱う。
const developmentCatalogVersion = '2'
export const developmentRanchAssets: readonly RanchAssetEntry[] = Object.freeze([
 Object.freeze({ assetKey: 'dev-triceratops-orange-pixel96-walk-v1', speciesKey: 'DEV_TRICERATOPS', variantKey: 'DEV_ORANGE_96_WALK_V1', stage: 'BABY', renderStyle: 'PIXEL', catalogVersion: developmentCatalogVersion, approval: 'DRAFT', origin: 'ISOLATED_DEVELOPMENT', src: '/dev-fixtures/ranch/triceratops-orange-pixel96-walk-v1.png', sourceWidth: 768, sourceHeight: 96, columnBoundaries: Object.freeze([0,96,192,288,384,480,576,672,768]), rowBoundaries: Object.freeze([0,96]), frames: 8, sourceSha256: '8ad7de84f1d0f495f9aeda86175f9c914fd0cbb788079b4f3c7e101f91358117' }),
 Object.freeze({ assetKey: 'dev-triceratops-orange-paint2d-walk-v1', speciesKey: 'DEV_TRICERATOPS', variantKey: 'DEV_ORANGE_96_WALK_V1', stage: 'BABY', renderStyle: 'PAINT_2D', catalogVersion: developmentCatalogVersion, approval: 'DRAFT', origin: 'ISOLATED_DEVELOPMENT', src: '/dev-fixtures/ranch/triceratops-orange-paint2d-walk-v1.png', sourceWidth: 1774, sourceHeight: 887, columnBoundaries: Object.freeze([0,444,887,1331,1774]), rowBoundaries: Object.freeze([0,444,887]), frames: 8, sourceSha256: 'f7d5d8822ee2e41b8b41200bb0547195586c1ea8a558b294d38b445450322c19' }),
])
export interface RanchAssetContext { readonly production: boolean; readonly isolatedDevelopment: boolean; readonly publicationEnabled: boolean }
export interface BoundRanchAsset { readonly dinosaurId: string; readonly entry: RanchAssetEntry }
export interface BoundRanchClip { readonly dinosaurId: string; readonly entry: RanchClipEntry }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function resolveRanchAsset(dinosaur: DinosaurSummary, style: RenderStyle, context: RanchAssetContext): BoundRanchAsset | null {
 if (!UUID.test(dinosaur.id) || !dinosaur.speciesKey || !dinosaur.variantKey || dinosaur.stage === 'EGG') return null
 const entries = context.production ? context.publicationEnabled ? productionManifest : [] : context.isolatedDevelopment ? developmentRanchAssets : context.publicationEnabled ? productionManifest : []
 const entry = entries.find(value => value.speciesKey === dinosaur.speciesKey && value.variantKey === dinosaur.variantKey && value.stage === dinosaur.stage && value.renderStyle === style && value.catalogVersion === dinosaur.speciesCatalogVersion)
 if (!entry) return null
 if (entry.origin === 'PRODUCTION' && (entry.approval !== 'APPROVED' || !context.publicationEnabled)) return null
 if (entry.origin === 'ISOLATED_DEVELOPMENT' && (context.production || !context.isolatedDevelopment)) return null
 return { dinosaurId: dinosaur.id, entry }
}
export function resolveRanchProductionClip(dinosaur: DinosaurSummary, style: RenderStyle, context: RanchAssetContext): BoundRanchClip | null {
 if (!context.publicationEnabled || !productionManifestReady) return null
 const asset = resolveRanchAsset(dinosaur, style, { ...context, isolatedDevelopment: false })
 const entry = asset?.entry.clipEntry
 return entry?.origin === 'PRODUCTION' && entry.approval === 'APPROVED' ? { dinosaurId: dinosaur.id, entry } : null
}
export function ranchAtlasFrameRect(entry: RanchAssetEntry, frame: number) {
 if (!Number.isInteger(frame) || frame < 0 || frame >= entry.frames) return null
 const columns = entry.columnBoundaries.length - 1
 if (columns < 1) return null
 const column = frame % columns; const row = Math.floor(frame / columns)
 const x = entry.columnBoundaries[column]; const right = entry.columnBoundaries[column+1]
 const y = entry.rowBoundaries[row]; const bottom = entry.rowBoundaries[row+1]
 if (x === undefined || right === undefined || y === undefined || bottom === undefined || right <= x || bottom <= y || right > entry.sourceWidth || bottom > entry.sourceHeight) return null
 return { x, y, width: right-x, height: bottom-y }
}
export const ranchReactionManifest = Object.freeze({ EGG_TOUCH: { durationMs: 600 }, DINOSAUR_TOUCH: { durationMs: 700 } })
export function resolveRanchReaction(value: string, stage: DinosaurSummary['stage']) {
 if (value === 'EGG_TOUCH' && stage === 'EGG') return ranchReactionManifest.EGG_TOUCH
 if (value === 'DINOSAUR_TOUCH' && (stage === 'BABY' || stage === 'JUVENILE' || stage === 'ADULT')) return ranchReactionManifest.DINOSAUR_TOUCH
 return null
}
