import { computed, ref, watch, type Ref } from 'vue'
import type { DinosaurSummary, MotionMode, RenderStyle } from '~/types/ranch'
import {
  resolveRanchDevelopmentClip,
  type BoundRanchDevelopmentClip,
  type RanchDevelopmentClipContext,
} from '~/utils/ranch-development-clips'
import { useRanchClipPlayback } from './useRanchClipPlayback'
import { useRanchClipAtlas } from './useRanchClipAtlas'

// Sceneのv3接続だけをまとめる。legacy atlas/finite reactionの契約を混在させない。
export function useRanchSceneClips(
  canvas: Ref<HTMLCanvasElement | null>,
  options: {
    dinosaur: () => DinosaurSummary
    renderStyle: () => RenderStyle
    motion: () => MotionMode
    context: () => RanchDevelopmentClipContext
    enabled: () => boolean
    ambient: () => boolean
    reduced: () => boolean
    legacyReaction: () => string | null
  },
) {
  let previous: BoundRanchDevelopmentClip | null = null
  const clipAsset = computed(() => {
    const next = resolveRanchDevelopmentClip(
      options.dinosaur(),
      options.renderStyle(),
      options.context(),
    )
    if (next && previous?.dinosaurId === next.dinosaurId && previous.entry === next.entry)
      return previous
    previous = next
    return next
  })
  const identity = () => {
    const d = options.dinosaur()
    return [
      d.id,
      d.stage,
      options.renderStyle(),
      d.speciesKey,
      d.variantKey,
      d.speciesCatalogVersion,
    ].join('/')
  }
  const ready = ref(false)
  const playback = useRanchClipPlayback({
    entry: () => clipAsset.value?.entry ?? null,
    identity,
    enabled: options.enabled,
    ambient: options.ambient,
    reduced: options.reduced,
    stopped: () => options.motion() === 'STOPPED',
    ready: () => ready.value,
  })
  const clipAtlas = useRanchClipAtlas(canvas, {
    entry: () => clipAsset.value?.entry ?? null,
    identity,
    enabled: options.enabled,
    action: () => playback.action.value,
    request: () => playback.request.value,
    phase: () => playback.phase.value,
    frame: () => playback.frame.value,
    staticOnly: () => options.motion() !== 'NORMAL' || options.reduced(),
  })
  watch(
    [clipAtlas.loaded, clipAtlas.failed, clipAtlas.usingFallback],
    () => {
      ready.value =
        clipAtlas.loaded.value && !clipAtlas.failed.value && !clipAtlas.usingFallback.value
    },
    { flush: 'sync', immediate: true },
  )
  const nativeReaction = computed(
    () =>
      clipAsset.value &&
      !options.reduced() &&
      (playback.action.value === 'happy' || playback.action.value === 'feed'),
  )
  const reactionKey = computed(() =>
    nativeReaction.value
      ? playback.action.value === 'feed'
        ? 'DINOSAUR_FEED'
        : 'DINOSAUR_TOUCH'
      : (options.legacyReaction() ?? ''),
  )
  return { clipAsset, playback, clipAtlas, nativeReaction, reactionKey }
}
