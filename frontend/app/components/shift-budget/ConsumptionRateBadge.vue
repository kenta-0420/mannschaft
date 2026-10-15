<script setup lang="ts">
import type { ConsumptionStatus } from '~/types/shiftBudget'

/**
 * F08.7 消化率バッジ — OK / WARN / EXCEEDED / SEVERE_EXCEEDED の 4 段階で色分け表示する。
 *
 * 設計書 §6.2.3 status 判定:
 * - rate < 0.80 → OK (success)
 * - 0.80 ≤ rate < 1.00 → WARN (warn)
 * - 1.00 ≤ rate < 1.20 → EXCEEDED (danger)
 * - rate ≥ 1.20 → SEVERE_EXCEEDED (danger / 強調)
 */
interface Props {
  /** 消化率 (0.00〜2.00 等の小数値、null 許容) */
  rate?: number | null
  /** 明示的に status を渡したい場合（バックエンドからの値そのまま）*/
  status?: ConsumptionStatus | null
  /** バッジ表示時にパーセンテージも併記するか */
  showPercent?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  rate: null,
  status: null,
  showPercent: true,
})

const { t } = useI18n()

const computedStatus = computed<ConsumptionStatus>(() => {
  if (props.status) return props.status
  const r = props.rate ?? 0
  if (r >= 1.2) return 'SEVERE_EXCEEDED'
  if (r >= 1.0) return 'EXCEEDED'
  if (r >= 0.8) return 'WARN'
  return 'OK'
})

const severity = computed<'success' | 'warn' | 'danger'>(() => {
  switch (computedStatus.value) {
    case 'OK':
      return 'success'
    case 'WARN':
      return 'warn'
    case 'EXCEEDED':
    case 'SEVERE_EXCEEDED':
      return 'danger'
    default:
      return 'success'
  }
})

const label = computed(() => {
  const statusLabel = t(`shiftBudget.status.${computedStatus.value}`)
  if (props.showPercent && props.rate !== null && props.rate !== undefined) {
    const percent = Math.round(props.rate * 100)
    return `${statusLabel} (${percent}%)`
  }
  return statusLabel
})
</script>

<template>
  <Tag :value="label" :severity="severity" />
</template>
