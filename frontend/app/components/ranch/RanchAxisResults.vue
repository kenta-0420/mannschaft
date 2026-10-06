<script setup lang="ts">
import type { DiagnosisResult } from '~/types/ranch'

const props = defineProps<{ result: DiagnosisResult }>()
const { t, locale } = useI18n()
// 表示順だけを定義する。結果の選択側はAPIの軸キーで照合し、Map順や説明文から推測しない。
const AXES = [
  'FAMILIAR_NEW',
  'FOCUS_VARIETY',
  'SPONTANEOUS_PLAN',
  'SOLO_TOGETHER',
  'EXPRESSION',
  'NOTICE',
] as const
const tendencies = computed(() =>
  AXES.map((axis) => {
    const selection = props.result.axisSelections?.[axis]
    const labels = selection?.side === 0 ? selection.zero : selection?.side === 1 ? selection.one : null
    return { axis, label: labels?.[locale.value] ?? labels?.ja, tied: props.result.axes?.[axis] === 0 }
  }),
)
</script>

<template>
  <div class="my-4 space-y-3">
    <p v-if="result.questionnaireVersion === 'draft-20261003-v1'" role="status">
      {{ t('ranch.diagnosisResults.draftNotice') }}
    </p>
    <h2 class="font-semibold">{{ t('ranch.diagnosisResults.tendencies') }}</h2>
    <p v-if="!result.axisSelections" role="status">{{ t('ranch.diagnosisResults.labelsUnavailable') }}</p>
    <ul v-else class="space-y-3">
      <li v-for="tendency in tendencies" :key="tendency.axis" :data-axis="tendency.axis">
        <p class="text-sm text-surface-500">{{ t(`ranch.diagnosisResults.axes.${tendency.axis}`) }}</p>
        <p class="font-semibold">{{ tendency.label || t('ranch.diagnosisResults.labelsUnavailable') }}</p>
        <p v-if="tendency.label && tendency.tied" class="text-sm">{{ t('ranch.diagnosisResults.tieSelection') }}</p>
      </li>
    </ul>
  </div>
</template>
