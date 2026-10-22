import type { DinosaurSummary, RenderStyle } from '~/types/ranch'
import clipManifest from './ranch-development-clips-manifest.json'

export type RanchClipAction = 'idle' | 'walk' | 'feed' | 'happy'
export type RanchClipHabitat = 'LAND' | 'SEA' | 'AIR'
export interface RanchClipPoint { readonly x: number; readonly y: number }
export interface RanchClipRect extends RanchClipPoint { readonly width: number; readonly height: number }
export interface RanchClipImage {
  readonly src: string
  readonly sourcePath: string
  readonly sourceSha256: string
  readonly sourceWidth: number
  readonly sourceHeight: number
}
export interface RanchDevelopmentClip extends RanchClipImage {
  readonly action: RanchClipAction
  readonly cell: number
  readonly frames: 4
  readonly durationMs: number
  readonly phaseStarts: readonly number[]
  readonly phaseMs: readonly number[]
  readonly loop: boolean
  readonly returnTo: 'idle' | null
  readonly floorByFrame: readonly number[] | null
  readonly sourceFloor384: number | null
  readonly sourceFloorByFrame384: readonly (number | null)[] | null
  readonly tailMarks: readonly RanchClipPoint[] | null
}
export interface RanchDevelopmentClipEntry {
  readonly assetKey: string
  readonly speciesKey: string
  readonly variantKey: 'DEV_BASE_16_CLIPS_V1'
  readonly stage: Exclude<DinosaurSummary['stage'], 'EGG'>
  readonly renderStyle: RenderStyle
  readonly catalogVersion: '3'
  readonly approval: 'DRAFT'
  readonly origin: 'ISOLATED_DEVELOPMENT'
  readonly sourceSpecies: string
  readonly habitat: RanchClipHabitat
  readonly logicalCanvas: { readonly width: 112; readonly height: 112 }
  readonly careSpriteRect: RanchClipRect
  readonly fallback: RanchClipImage
  readonly clips: Readonly<Record<RanchClipAction, RanchDevelopmentClip>>
}
export interface RanchDevelopmentClipContext {
  readonly production: boolean
  readonly isolatedDevelopment: boolean
  readonly clipsEnabled: boolean
}
export interface BoundRanchDevelopmentClip { readonly dinosaurId: string; readonly entry: RanchDevelopmentClipEntry }

// 正本と480画像の完全照合はscripts/verify-ranch-development-clips.mjsが担う。
export const developmentRanchClips: readonly RanchDevelopmentClipEntry[] = import.meta.dev
  ? Object.freeze(clipManifest.entries) as unknown as readonly RanchDevelopmentClipEntry[]
  : Object.freeze([])
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function resolveRanchDevelopmentClip(dinosaur: DinosaurSummary, style: RenderStyle, context: RanchDevelopmentClipContext): BoundRanchDevelopmentClip | null {
  if (context.production || !context.isolatedDevelopment || !context.clipsEnabled || !UUID.test(dinosaur.id) || !dinosaur.speciesKey || !dinosaur.variantKey || dinosaur.stage === 'EGG') return null
  const entry = developmentRanchClips.find(value => value.speciesKey === dinosaur.speciesKey && value.variantKey === dinosaur.variantKey && value.stage === dinosaur.stage && value.renderStyle === style && value.catalogVersion === dinosaur.speciesCatalogVersion)
  return entry ? { dinosaurId: dinosaur.id, entry } : null
}
export function ranchDevelopmentClipFrameRect(clip: RanchDevelopmentClip, frame: number): RanchClipRect | null {
  if (!Number.isInteger(frame) || frame < 0 || frame >= clip.frames || clip.cell <= 0 || clip.cell * clip.frames !== clip.sourceWidth || clip.cell !== clip.sourceHeight) return null
  return { x: frame * clip.cell, y: 0, width: clip.cell, height: clip.cell }
}
