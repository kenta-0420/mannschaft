<script setup lang="ts">
import { useProvidedRanchState } from '~/composables/useRanchState'
import { productionRanchAssetsRegistered } from '~/utils/ranch-assets'
const props = withDefaults(defineProps<{ active?: boolean; collapsed?: boolean }>(), {
  active: true,
  collapsed: undefined,
})
const emit = defineEmits<{ 'update:collapsed': [collapsed: boolean] }>()
const { t } = useI18n()
const config = useRuntimeConfig()
const assetContext = {
  production: !import.meta.dev,
  isolatedDevelopment: config.public.ranchDevelopmentVisuals === true,
  publicationEnabled: productionRanchAssetsRegistered(),
}
const clipContext = {
  production: !import.meta.dev,
  isolatedDevelopment: import.meta.dev === true,
  clipsEnabled: import.meta.dev === true && config.public.ranchDevelopmentClips === true,
}
const reaction = shallowRef<{
  key: string
  dinosaurId: string
  sequence: number
  soundToken: number | null
} | null>(null)
let reactionSequence = 0
const scene = ref<{
  prepareTouchSound: () => number | null
  cancelTouchSound: (token: number | null) => void
} | null>(null)
const provided = useProvidedRanchState()
const ranch = provided ?? useRanchState()
const authStore = useAuthStore()
const localCollapsed = ref(false)
const collapsed = computed({
  get: () => props.collapsed ?? localCollapsed.value,
  set: (value) => {
    if (props.collapsed === undefined) localCollapsed.value = value
    emit('update:collapsed', value)
  },
})
const mounted = ref(false)
const pageVisible = ref(import.meta.client && document.visibilityState !== 'hidden')
let requestedInitialState = false
function loadWhenActive() {
  if (
    !mounted.value ||
    !props.active ||
    collapsed.value ||
    !pageVisible.value ||
    requestedInitialState
  )
    return
  requestedInitialState = true
  if (!provided) void ranch.load()
}
function updateVisibility() {
  pageVisible.value = document.visibilityState !== 'hidden'
  loadWhenActive()
}
watch([() => props.active, collapsed], loadWhenActive, { flush: 'sync' })
const message = ref('')
let feedbackGeneration = 0
let careGeneration = 0
const seenCommands = new Set<string>()
let disposed = false
watch(
  () => authStore.user?.id ?? null,
  () => {
    feedbackGeneration += 1
    careGeneration += 1
    seenCommands.clear()
    message.value = ''
    reaction.value = null
  },
  { flush: 'sync' },
)
onScopeDispose(() => {
  disposed = true
})
const egg = computed(() => ranch.state.value?.dinosaur?.stage === 'EGG')
const feedingUnavailable = computed(
  () =>
    ranch.state.value?.featureStatus !== 'AVAILABLE' ||
    ranch.state.value?.owner?.status !== 'ACTIVE',
)
const sceneActive = computed(
  () => props.active && !collapsed.value && !!ranch.state.value?.settings?.isVisible,
)
async function care(kind: 'feed' | 'touch') {
  if (kind === 'feed' && feedingUnavailable.value && !ranch.api.command.pending.value) return
  const version = ranch.state.value?.owner?.version
  if (!version) return
  const owner = authStore.user?.id
  const currentScene = scene.value
  const soundToken = kind === 'touch' ? (currentScene?.prepareTouchSound() ?? null) : null
  const generation = feedbackGeneration
  const careRun = ++careGeneration
  const current = () =>
    !disposed &&
    owner !== undefined &&
    authStore.user?.id === owner &&
    feedbackGeneration === generation &&
    careGeneration === careRun
  function firstFeedback(commandId: string) {
    if (seenCommands.has(commandId)) return false
    // private Widget scopeの直近命令だけを保持し、長時間表示でも履歴を肥大させない。
    seenCommands.add(commandId)
    if (seenCommands.size > 32) {
      const oldest = seenCommands.values().next().value
      if (oldest !== undefined) seenCommands.delete(oldest)
    }
    return true
  }
  try {
    if (kind === 'feed') {
      const result = await ranch.act(() => ranch.api.feed(version))
      // actはstate reloadまで待つ。成長後の新stageをSceneへ反映してからclipを通知する。
      await nextTick()
      if (!current()) return
      message.value = t('ranch.care.fed', { xp: result.gainedXp })
      const dinosaur = ranch.state.value?.dinosaur
      if (
        dinosaur &&
        dinosaur.id === result.dinosaurId &&
        dinosaur.stage === result.stageAfter &&
        firstFeedback(result.commandId)
      ) {
        reaction.value = {
          key: 'DINOSAUR_FEED',
          dinosaurId: dinosaur.id,
          sequence: ++reactionSequence,
          soundToken: null,
        }
      }
    } else {
      const result = await ranch.act(() => ranch.api.touch(version))
      if (!current()) {
        currentScene?.cancelTouchSound(soundToken)
        return
      }
      const band = result.affinityBand
      const affinityLabel =
        band === 'NEUTRAL' || band === 'WARM' || band === 'CLOSE' ? t('ranch.affinity.' + band) : ''
      message.value = t('ranch.care.touched') + (affinityLabel ? ' · ' + affinityLabel : '')
      if (
        result.dinosaurId === ranch.state.value?.dinosaur?.id &&
        firstFeedback(result.commandId)
      ) {
        reaction.value = {
          key: result.reactionKey,
          dinosaurId: result.dinosaurId,
          sequence: ++reactionSequence,
          soundToken,
        }
      } else {
        currentScene?.cancelTouchSound(soundToken)
      }
    }
  } catch {
    currentScene?.cancelTouchSound(soundToken)
    if (current()) message.value = t('ranch.command.failed')
  }
}
onMounted(() => {
  mounted.value = true
  document.addEventListener('visibilitychange', updateVisibility)
  updateVisibility()
})
onUnmounted(() => document.removeEventListener('visibilitychange', updateVisibility))
</script>
<template>
  <DashboardWidgetCard
    :title="t('ranch.title')"
    icon="pi pi-sparkles"
    to="/my/ranch"
    :scrollable="false"
    :loading="ranch.loading.value && !ranch.state.value"
    :collapsed="collapsed"
    refreshable
    @refresh="ranch.load"
    @update:collapsed="collapsed = $event"
  >
    <DashboardErrorState v-if="ranch.failed.value" @retry="ranch.load" />
    <template v-else-if="ranch.state.value?.dinosaur && ranch.state.value.settings">
      <DinosaurScene
        ref="scene"
        :sound-enabled="ranch.state.value.settings.isSoundEnabled"
        :sound-volume="ranch.state.value.settings.soundVolume"
        :dinosaur="ranch.state.value.dinosaur"
        :render-style="ranch.state.value.settings.renderStyle"
        :motion-mode="ranch.state.value.settings.motionMode"
        :background-paused="ranch.state.value.owner?.status === 'PAUSED'"
        :active="sceneActive"
        :asset-context="assetContext"
        :clip-context="clipContext"
        :reaction="reaction"
      />
      <p v-if="ranch.state.value.owner?.status === 'PAUSED'" class="mt-2">
        {{ t('ranch.paused') }}
      </p>
      <RanchShelves :slots="ranch.state.value.roomSlots" :active="sceneActive" />
      <div class="grid grid-cols-3 gap-2 mt-3">
        <Button
          class="min-h-11"
          :label="t(egg ? 'ranch.care.eggTouch' : 'ranch.care.food')"
          :disabled="
            ranch.loading.value ||
            ranch.api.command.running.value ||
            (!egg && feedingUnavailable && !ranch.api.command.pending.value)
          "
          @click="care(egg ? 'touch' : 'feed')"
        />
        <Button
          v-if="egg"
          class="min-h-11"
          :label="t('ranch.care.choose')"
          outlined
          @click="navigateTo('/my/ranch/assignment')"
        />
        <Button
          v-else
          class="min-h-11"
          :label="t('ranch.care.touch')"
          outlined
          :disabled="ranch.loading.value || ranch.api.command.running.value"
          @click="care('touch')"
        />
        <Button
          class="min-h-11"
          :label="t('ranch.care.status')"
          outlined
          @click="navigateTo('/my/ranch')"
        />
      </div>
      <p role="status" aria-live="polite" class="mt-2 text-sm">{{ message }}</p>
    </template>
    <DashboardEmptyState v-else-if="!ranch.loading.value" :message="t('ranch.optional')" />
  </DashboardWidgetCard>
</template>
