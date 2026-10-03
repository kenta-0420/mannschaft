<script setup lang="ts">
const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })
const { t } = useI18n()
const ranch = useRanchState()
const collapsed = ref(false)
const message = ref('')
const egg = computed(() => ranch.state.value?.dinosaur?.stage === 'EGG')
const feedingUnavailable = computed(() => ranch.state.value?.featureStatus !== 'AVAILABLE' || ranch.state.value?.owner?.status !== 'ACTIVE')
const sceneActive = computed(() => props.active && !collapsed.value && !!ranch.state.value?.settings?.isVisible)
async function care(kind: 'feed' | 'touch') {
 if (kind === 'feed' && feedingUnavailable.value && !ranch.api.command.pending.value) return
 const version = ranch.state.value?.owner?.version; if (!version) return
 try { const result = await ranch.act(() => kind === 'feed' ? ranch.api.feed(version) : ranch.api.touch(version)); message.value = kind === 'feed' ? t('ranch.care.fed', { xp: (result as { gainedXp: string }).gainedXp }) : t('ranch.care.touched') } catch { message.value = t('ranch.command.failed') }
}
onMounted(ranch.load)
</script>
<template>
 <DashboardWidgetCard :title="t('ranch.title')" icon="pi pi-sparkles" to="/my/ranch" :scrollable="false" :loading="ranch.loading.value" refreshable @refresh="ranch.load" @collapse-change="collapsed = $event">
  <DashboardErrorState v-if="ranch.failed.value" @retry="ranch.load" />
  <template v-else-if="ranch.state.value?.dinosaur && ranch.state.value.settings">
   <DinosaurScene :dinosaur="ranch.state.value.dinosaur" :render-style="ranch.state.value.settings.renderStyle" :motion-mode="ranch.state.value.owner?.status === 'PAUSED' ? 'STOPPED' : ranch.state.value.settings.motionMode" :active="sceneActive" />
   <p v-if="ranch.state.value.owner?.status === 'PAUSED'" class="mt-2">{{ t('ranch.paused') }}</p>
   <div class="grid grid-cols-3 gap-2 mt-3">
    <Button class="min-h-11" :label="t(egg ? 'ranch.care.eggTouch' : 'ranch.care.food')" :disabled="ranch.api.command.running.value || (!egg && feedingUnavailable && !ranch.api.command.pending.value)" @click="care(egg ? 'touch' : 'feed')" />
    <Button v-if="egg" class="min-h-11" :label="t('ranch.care.choose')" outlined @click="navigateTo('/my/ranch/assignment')" />
    <Button v-else class="min-h-11" :label="t('ranch.care.touch')" outlined :disabled="ranch.api.command.running.value" @click="care('touch')" />
    <Button class="min-h-11" :label="t('ranch.care.status')" outlined @click="navigateTo('/my/ranch')" />
   </div>
   <p role="status" aria-live="polite" class="mt-2 text-sm">{{ message }}</p>
  </template>
  <DashboardEmptyState v-else-if="!ranch.loading.value" :message="t('ranch.optional')" />
 </DashboardWidgetCard>
</template>
