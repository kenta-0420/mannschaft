import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import type { MotionMode } from '~/types/ranch'
export function useRanchSceneActivity(element: Ref<HTMLElement | null>, active: () => boolean, motion: () => MotionMode, backgroundPaused: () => boolean = () => false) {
 const inViewport = ref(false); const documentVisible = ref(false); const osReduced = ref(false)
 let observer: IntersectionObserver | null = null; let media: MediaQueryList | null = null
 const syncVisibility = () => { documentVisible.value = !document.hidden }
 const syncMotion = () => { osReduced.value = media?.matches ?? false }
 const enabled = computed(() => active() && inViewport.value && documentVisible.value)
 const backgroundMoving = computed(() => enabled.value && motion() === 'NORMAL' && !osReduced.value && !backgroundPaused())
 const reactionsAllowed = computed(() => enabled.value && motion() !== 'STOPPED')
 onMounted(() => {
  syncVisibility(); document.addEventListener('visibilitychange', syncVisibility)
  media = window.matchMedia('(prefers-reduced-motion: reduce)'); syncMotion(); media.addEventListener('change', syncMotion)
  observer = new IntersectionObserver(entries => { inViewport.value = entries.some(entry => entry.isIntersecting) })
  if (element.value) observer.observe(element.value)
 })
 watch(element, (next, previous) => { if (previous) observer?.unobserve(previous); if (next) observer?.observe(next) })
 onUnmounted(() => { observer?.disconnect(); document.removeEventListener('visibilitychange', syncVisibility); media?.removeEventListener('change', syncMotion) })
 return { enabled, backgroundMoving, reactionsAllowed, osReduced }
}
