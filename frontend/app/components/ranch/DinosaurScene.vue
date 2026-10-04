<script setup lang="ts">
import type { DinosaurSummary, MotionMode, RenderStyle } from '~/types/ranch'
export interface DinosaurAsset {
 dinosaurId: string; speciesKey: string; variantKey: string; stage: DinosaurSummary['stage']; renderStyle: RenderStyle;
 staticUrl: string; approved: boolean
}
const props = withDefaults(defineProps<{ dinosaur: DinosaurSummary; renderStyle: RenderStyle; motionMode: MotionMode; active?: boolean; asset?: DinosaurAsset | null; reaction?: string }>(), { active: true, asset: null, reaction: '' })
const { t } = useI18n()
const scene = ref<HTMLElement | null>(null)
const activity = useRanchSceneActivity(scene, () => props.active, () => props.motionMode)
const loadedOnce = ref(false); const failed = ref(false); const offset = ref(0)
let frame: number | null = null; let lastTime: number | null = null
const validAsset = computed(() => {
 const a = props.asset; const d = props.dinosaur
 return a?.approved && a.dinosaurId === d.id && a.speciesKey === d.speciesKey && a.variantKey === d.variantKey && a.stage === d.stage && a.renderStyle === props.renderStyle && a.staticUrl.startsWith('/') && !a.staticUrl.startsWith('//') ? a : null
})
watch(activity.enabled, enabled => { if (enabled) loadedOnce.value = true }, { immediate: true })
watch(validAsset, () => { failed.value = false })
function stop() { if (frame !== null) cancelAnimationFrame(frame); frame = null; lastTime = null }
function tick(time: number) {
 if (!activity.backgroundMoving.value) { stop(); return }
 if (lastTime !== null) offset.value = (offset.value + Math.min(time - lastTime, 50) / 1200) % 20
 lastTime = time; frame = requestAnimationFrame(tick)
}
watch(activity.backgroundMoving, moving => { stop(); if (moving) frame = requestAnimationFrame(tick) }, { flush: 'sync' })
watch(() => props.renderStyle, () => { stop(); if (activity.backgroundMoving.value) frame = requestAnimationFrame(tick) })
onUnmounted(stop)
</script>
<template>
 <figure ref="scene" class="relative isolate min-h-56 overflow-hidden rounded-lg flex flex-col items-center justify-center" :class="dinosaur.habitat === 'SEA' ? 'bg-cyan-100 dark:bg-cyan-950' : dinosaur.habitat === 'AIR' ? 'bg-sky-100 dark:bg-sky-950' : 'bg-emerald-50 dark:bg-emerald-950'" :data-scene-active="activity.enabled.value" :data-background-moving="activity.backgroundMoving.value" :data-dinosaur-id="dinosaur.id">
  <div v-if="dinosaur.habitat === 'SEA'" aria-hidden="true" class="pointer-events-none absolute inset-0">
   <span v-for="n in 5" :key="n" class="absolute w-3 h-3 rounded-full border border-cyan-400/40" :style="{ left: `${n * 17}%`, bottom: `${n * 13 + offset}%` }" />
  </div>
  <div v-else-if="dinosaur.habitat === 'AIR'" aria-hidden="true" class="pointer-events-none absolute inset-0">
   <span v-for="n in 3" :key="n" class="absolute w-16 h-5 rounded-full bg-white/50" :style="{ top: `${n * 17}%`, left: `${n * 21 + offset}%` }" />
  </div>
  <svg v-if="dinosaur.stage === 'EGG'" viewBox="0 0 96 96" width="192" height="192" aria-hidden="true" class="relative">
   <path :d="renderStyle === 'PIXEL' ? 'M40 16H56V24H64V36H72V60H76V72H68V80H28V72H20V60H24V36H32V24H40Z' : 'M48 16C32 16 24 49 24 62C24 88 72 88 72 62C72 49 64 16 48 16Z'" fill="#f7ebd1" stroke="#665b45" stroke-width="3" />
   <path d="M37 35h8v8h-8zM52 53h9v9h-9zM34 65h7v7h-7z" fill="#b9cb89" />
  </svg>
  <img v-else-if="loadedOnce && validAsset && !failed" :key="`${dinosaur.id}-${renderStyle}-${dinosaur.stage}`" :src="validAsset.staticUrl" width="192" height="192" :alt="dinosaur.name ?? t('ranch.avatar.label')" class="relative object-contain" :style="{ imageRendering: renderStyle === 'PIXEL' ? 'pixelated' : 'auto' }" @error="failed = true">
  <p v-else class="relative text-sm p-8">{{ t('ranch.scene.assetPreparing') }}</p>
  <figcaption class="relative text-sm font-medium break-words text-center">{{ dinosaur.name ?? t('ranch.stage.EGG') }} · {{ t(`ranch.stage.${dinosaur.stage}`) }}</figcaption>
 </figure>
</template>
