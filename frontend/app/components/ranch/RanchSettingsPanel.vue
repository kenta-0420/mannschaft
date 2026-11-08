<script setup lang="ts">
import type { RanchState, RanchSettings } from '~/types/ranch'
const props = defineProps<{ state: RanchState; busy?: boolean }>()
const emit = defineEmits<{ start: []; settings: [settings: Omit<RanchSettings,'isVisible' | 'viewMode'>]; participation: [action: 'pause' | 'resume']; visibility: [visible: boolean] }>()
const { t } = useI18n()
const draft = ref({ renderStyle: props.state.settings?.renderStyle ?? 'PIXEL', motionMode: props.state.settings?.motionMode ?? 'NORMAL', isSoundEnabled: props.state.settings?.isSoundEnabled ?? false, soundVolume: props.state.settings?.soundVolume ?? 50 })
watch(() => props.state.settings, settings => { if (settings) draft.value = { renderStyle: settings.renderStyle, motionMode: settings.motionMode, isSoundEnabled: settings.isSoundEnabled, soundVolume: settings.soundVolume } })
function save() { if (props.state.owner) emit('settings', { ...draft.value, version: props.state.owner.version }) }
</script>
<template>
 <SectionCard :title="t('ranch.settings.title')">
  <p class="mb-3">{{ t('ranch.avatar.description') }}</p>
  <p class="mb-3">{{ t('ranch.optional') }}</p>
  <Button v-if="!state.owner" class="min-h-11" :label="t('ranch.start')" :disabled="state.featureStatus !== 'AVAILABLE' || busy" @click="emit('start')" />
  <template v-else>
   <div class="grid gap-3 md:grid-cols-2">
    <label>{{ t('ranch.settings.style') }}<Select v-model="draft.renderStyle" class="w-full" :options="['PIXEL','PAINT_2D']" :option-label="value => t(`ranch.style.${value}`)" :disabled="busy" /></label>
    <label>{{ t('ranch.settings.motion') }}<Select v-model="draft.motionMode" class="w-full" :options="['NORMAL','REDUCED','STOPPED']" :option-label="value => t(`ranch.motion.${value}`)" :disabled="busy" /></label>
    <label class="flex gap-2 items-center min-h-11"><Checkbox v-model="draft.isSoundEnabled" binary :disabled="busy" />{{ t('ranch.settings.sound') }}</label>
    <label>{{ t('ranch.settings.volume') }}<InputNumber v-model="draft.soundVolume" :min="0" :max="100" :disabled="busy" /></label>
   </div>
   <p class="my-3">{{ t('ranch.settings.freewalkLater') }}</p>
   <div class="flex flex-wrap gap-3">
    <Button class="min-h-11" :label="t('ranch.save')" :disabled="busy" @click="save" />
    <Button class="min-h-11" :label="t(state.settings?.isVisible ? 'ranch.settings.hide' : 'ranch.settings.show')" outlined :disabled="busy" @click="emit('visibility', !state.settings?.isVisible)" />
    <Button class="min-h-11" :label="t(state.owner.status === 'PAUSED' ? 'ranch.resume' : 'ranch.pause')" outlined :disabled="busy" @click="emit('participation', state.owner.status === 'PAUSED' ? 'resume' : 'pause')" />
   </div>
  </template>
  <p v-if="state.featureStatus !== 'AVAILABLE'" class="mt-3" role="status">{{ t('ranch.unavailable') }}</p>
 </SectionCard>
</template>
