<script setup lang="ts">
import type {
  JobApplicationStatus,
  JobContractStatus,
  JobPostingStatus,
} from '~/types/jobmatching'

/**
 * 求人関連の状態バッジ。Posting / Application / Contract いずれかの状態を
 * PrimeVue {@code Tag} で色付きラベルとして描画する。
 *
 * <p>状態名は i18n キー経由（{@code jobmatching.status.<kind>.<STATUS>}）。
 * 未定義の状態に対しては文字列をそのままフォールバック表示する。</p>
 */

type StatusKind = 'posting' | 'application' | 'contract'

const props = defineProps<{
  kind: StatusKind
  status: JobPostingStatus | JobApplicationStatus | JobContractStatus
}>()

const { t, te } = useI18n()

type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast'

const POSTING_SEVERITY: Record<JobPostingStatus, TagSeverity> = {
  DRAFT: 'secondary',
  OPEN: 'success',
  CLOSED: 'info',
  CANCELLED: 'danger',
}

const APPLICATION_SEVERITY: Record<JobApplicationStatus, TagSeverity> = {
  APPLIED: 'info',
  ACCEPTED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'secondary',
}

const CONTRACT_SEVERITY: Record<JobContractStatus, TagSeverity> = {
  MATCHED: 'info',
  CHECKED_IN: 'info',
  IN_PROGRESS: 'info',
  CHECKED_OUT: 'info',
  TIME_CONFIRMED: 'info',
  COMPLETION_REPORTED: 'warn',
  COMPLETED: 'success',
  AUTHORIZED: 'success',
  CAPTURED: 'success',
  PAID: 'success',
  CANCELLED: 'danger',
  DISPUTED: 'danger',
}

const severity = computed<TagSeverity>(() => {
  switch (props.kind) {
    case 'posting':
      return POSTING_SEVERITY[props.status as JobPostingStatus] ?? 'secondary'
    case 'application':
      return APPLICATION_SEVERITY[props.status as JobApplicationStatus] ?? 'secondary'
    case 'contract':
      return CONTRACT_SEVERITY[props.status as JobContractStatus] ?? 'secondary'
  }
  return 'secondary'
})

const label = computed<string>(() => {
  const key = `jobmatching.status.${props.kind}.${props.status}`
  return te(key) ? t(key) : props.status
})
</script>

<template>
  <Tag
    :value="label"
    :severity="severity"
  />
</template>
