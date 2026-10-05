<script setup lang="ts">
import type { DinosaurSummary, MotionMode, RenderStyle } from '~/types/ranch'
import { resolveRanchAsset, type BoundRanchAsset, type RanchAssetContext } from '~/utils/ranch-assets'
import { eggCrackPaths } from '~/composables/useRanchFiniteReaction'
const props = withDefaults(defineProps<{
 dinosaur: DinosaurSummary; renderStyle: RenderStyle; motionMode: MotionMode; active?: boolean
 backgroundPaused?: boolean; assetContext?: RanchAssetContext; soundEnabled?: boolean; soundVolume?: number
 reaction?: { key: string; dinosaurId: string; sequence: number; soundToken?: number | null } | null
}>(), { active: true, backgroundPaused: false, soundEnabled: false, soundVolume: 50, assetContext: () => ({ production: true, isolatedDevelopment: false, publicationEnabled: false }), reaction: null })
const { t } = useI18n()
const scene = ref<HTMLElement | null>(null)
const canvas = ref<HTMLCanvasElement | null>(null)
const activity = useRanchSceneActivity(scene, () => props.active, () => props.motionMode, () => props.backgroundPaused)
const sound = useRanchTouchSound({ dinosaur: () => props.dinosaur, enabled: () => activity.enabled.value, motion: () => props.motionMode, renderStyle: () => props.renderStyle, soundEnabled: () => props.soundEnabled, volume: () => props.soundVolume })
defineExpose({ prepareTouchSound: sound.prepare, cancelTouchSound: sound.cancel })
const reaction = useRanchFiniteReaction({ dinosaur: () => props.dinosaur, motion: () => props.motionMode, renderStyle: () => props.renderStyle, contextEnabled: () => activity.enabled.value, osReduced: () => activity.osReduced.value })
let previousAsset: BoundRanchAsset | null = null
const asset = computed(() => {
 const next = resolveRanchAsset(props.dinosaur, props.renderStyle, props.assetContext)
 if (next && previousAsset?.dinosaurId === next.dinosaurId && previousAsset.entry === next.entry) return previousAsset
 previousAsset = next
 return next
})
const atlas = useRanchAtlas(canvas, () => asset.value, () => activity.enabled.value, () => reaction.pilotFrame.value, () => props.motionMode !== 'NORMAL' || activity.osReduced.value)
const crackPaths = computed(() => eggCrackPaths[props.dinosaur.egg?.crackStage ?? 'INTACT'])
watch(() => props.reaction, value => { if (value) { if (reaction.respond(value.key, value.dinosaurId)) sound.play(value.key, value.dinosaurId, value.soundToken ?? null); else sound.cancel(value.soundToken ?? null) } }, { flush: 'sync' })
const offset = ref(0)
const backgroundMoving = computed(() => activity.backgroundMoving.value && (props.dinosaur.habitat === 'SEA' || props.dinosaur.habitat === 'AIR'))
let frame: number | null = null
let lastTime: number | null = null
function stop() { if (frame !== null) cancelAnimationFrame(frame); frame = null; lastTime = null }
function tick(time: number) {
 if (!backgroundMoving.value) { stop(); return }
 if (lastTime !== null) offset.value = (offset.value + Math.min(Math.max(time - lastTime, 0), 50) / 1200) % 20
 lastTime = time; frame = requestAnimationFrame(tick)
}
watch(backgroundMoving, moving => { stop(); if (moving) frame = requestAnimationFrame(tick) }, { flush: 'sync' })
onScopeDispose(stop)
</script>
<template>
 <figure ref="scene" class="relative isolate min-h-56 overflow-hidden rounded-lg flex flex-col items-center justify-center" :class="dinosaur.habitat === 'SEA' ? 'bg-cyan-100 dark:bg-cyan-950' : dinosaur.habitat === 'AIR' ? 'bg-sky-100 dark:bg-sky-950' : 'bg-emerald-50 dark:bg-emerald-950'" :data-scene-active="activity.enabled.value" :data-background-moving="backgroundMoving" :data-dinosaur-id="dinosaur.id" :data-render-style="renderStyle">
  <div v-if="dinosaur.habitat === 'SEA'" aria-hidden="true" class="pointer-events-none absolute inset-0">
   <span v-for="n in 5" :key="n" class="absolute w-3 h-3 rounded-full border border-cyan-400/40" :style="{ left: n * 17 + '%', bottom: n * 13 + offset + '%' }" />
  </div>
  <div v-else-if="dinosaur.habitat === 'AIR'" aria-hidden="true" class="pointer-events-none absolute inset-0">
   <span v-for="n in 3" :key="n" class="absolute w-16 h-5 rounded-full bg-white/50" :style="{ top: n * 17 + '%', left: n * 21 + offset + '%' }" />
  </div>
  <div :key="reaction.sequence.value" :data-reaction-sequence="reaction.sequence.value" :data-reaction-key="reaction.reaction.value ?? ''" class="relative" :class="{ 'ranch-reaction-reduced': reaction.reaction.value && reaction.reduced.value }" :style="reaction.reaction.value && !reaction.reduced.value ? { transform: 'translateY(' + (-Math.sin(reaction.progress.value * Math.PI) * 4) + 'px)' } : undefined" v-on="reaction.reaction.value && reaction.reduced.value ? { animationend: reaction.finishReduced } : {}">
   <svg v-if="dinosaur.stage === 'EGG'" viewBox="0 0 96 96" width="192" height="192" aria-hidden="true" :data-crack-stage="dinosaur.egg?.crackStage ?? 'INTACT'">
    <path :d="renderStyle === 'PIXEL' ? 'M40 16H56V24H64V36H72V60H76V72H68V80H28V72H20V60H24V36H32V24H40Z' : 'M48 16C32 16 24 49 24 62C24 88 72 88 72 62C72 49 64 16 48 16Z'" fill="#f7ebd1" stroke="#665b45" stroke-width="3" />
    <path d="M37 35h8v8h-8zM52 53h9v9h-9zM34 65h7v7h-7z" fill="#b9cb89" />
    <path v-for="path in crackPaths" :key="path" :d="path" fill="none" stroke="#665b45" stroke-width="2" />
   </svg>
   <template v-else>
    <canvas v-if="asset && !atlas.failed.value" ref="canvas" v-show="atlas.loaded.value" :width="renderStyle === 'PIXEL' ? 96 : 192" :height="renderStyle === 'PIXEL' ? 96 : 192" role="img" :aria-label="dinosaur.name ?? t('ranch.avatar.label')" class="w-48 h-48" :style="{ imageRendering: renderStyle === 'PIXEL' ? 'pixelated' : 'auto' }" />
    <p v-if="!asset || !atlas.loaded.value || atlas.failed.value" class="text-sm p-8">{{ t('ranch.scene.assetPreparing') }}</p>
    <p v-else-if="atlas.usingFallback.value" class="text-xs">{{ t('ranch.scene.staticFallback') }}</p>
   </template>
  </div>
  <figcaption class="relative text-sm font-medium break-words text-center">{{ dinosaur.name ?? t('ranch.stage.EGG') }} · {{ t('ranch.stage.' + dinosaur.stage) }}</figcaption>
 </figure>
</template>
<style scoped>
.ranch-reaction-reduced { animation: ranch-touch-color 600ms ease-out 1; }
@keyframes ranch-touch-color { 0%, 100% { filter: brightness(1); } 35% { filter: brightness(1.12); } }
</style>
