<script setup lang="ts">
/**
 * 物件履歴パッケージ 一覧カード（F09.13 Phase 1-ε）。
 *
 * Summary レスポンスを受け取り、ステータス・工事種別バッジ・業者・完了日・金額（マスク対応）を表示。
 * クリックで親に select を発火する。詳細遷移は親ページで navigateTo する。
 */
import type {
  PropertyWorkPackageSummaryResponse,
  WorkPackageStatus,
  WorkType,
} from '~/types/property'

const props = defineProps<{
  package: PropertyWorkPackageSummaryResponse
}>()

const emit = defineEmits<{
  select: [packageId: number]
}>()

const { t } = useI18n()

// `package` は予約語のため template 内で `pkg` を使う
const pkg = computed(() => props.package)

function workTypeLabel(wt: WorkType): string {
  return t(`property.workType.${wt}`)
}

function statusLabel(status: WorkPackageStatus): string {
  return t(`property.status.${status}`)
}

function statusClass(status: WorkPackageStatus): string {
  switch (status) {
    case 'PLANNED':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
    case 'IN_PROGRESS':
      return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
    case 'COMPLETED':
      return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'CLOSED':
      return 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-400'
    case 'CANCELLED':
      return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
    default:
      return 'bg-surface-100'
  }
}

function workTypeClass(wt: WorkType): string {
  switch (wt) {
    case 'RENOVATION':
      return 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-300'
    case 'REPAIR':
      return 'bg-amber-100 text-amber-700 dark:bg-amber-900 dark:text-amber-300'
    case 'INCIDENT':
      return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
    case 'INSPECTION':
      return 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900 dark:text-cyan-300'
    case 'DISASTER':
      return 'bg-rose-100 text-rose-700 dark:bg-rose-900 dark:text-rose-300'
    case 'MEETING':
      return 'bg-purple-100 text-purple-700 dark:bg-purple-900 dark:text-purple-300'
    default:
      return 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
  }
}

const displayDate = computed(() => {
  return pkg.value.actualEndDate ?? pkg.value.plannedEndDate ?? null
})

const formattedAmount = computed<string | null>(() => {
  if (!pkg.value.canViewAmount) {
    return t('property.masked')
  }
  if (pkg.value.actualAmount === null || pkg.value.actualAmount === undefined) {
    return null
  }
  return pkg.value.actualAmount.toLocaleString('ja-JP')
})

function onClick() {
  emit('select', pkg.value.id)
}
</script>

<template>
  <button
    type="button"
    class="block w-full rounded-lg border border-surface-200 bg-white p-4 text-left shadow-sm transition hover:border-primary-400 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-primary-300 dark:border-surface-700 dark:bg-surface-900"
    :data-testid="`property-package-card-${pkg.id}`"
    @click="onClick"
  >
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div class="flex flex-wrap items-center gap-2">
        <span
          class="rounded-full px-2 py-0.5 text-xs font-medium"
          :class="workTypeClass(pkg.workType)"
        >
          {{ workTypeLabel(pkg.workType) }}
        </span>
        <span
          class="rounded-full px-2 py-0.5 text-xs font-medium"
          :class="statusClass(pkg.status)"
        >
          {{ statusLabel(pkg.status) }}
        </span>
        <span
          v-if="pkg.category"
          class="text-xs text-surface-500 dark:text-surface-400"
        >
          {{ pkg.category }}
        </span>
      </div>
      <span
        v-if="displayDate"
        class="text-xs text-surface-500 dark:text-surface-400"
      >
        {{ displayDate }}
      </span>
    </div>

    <h3 class="mt-2 text-base font-semibold text-surface-900 dark:text-surface-100">
      {{ pkg.title }}
    </h3>

    <div class="mt-2 flex flex-wrap items-center justify-between gap-2 text-sm">
      <span
        v-if="pkg.vendorNameSnapshot"
        class="text-surface-700 dark:text-surface-300"
      >
        {{ pkg.vendorNameSnapshot }}
      </span>
      <span
        v-if="formattedAmount !== null"
        class="font-medium tabular-nums text-surface-800 dark:text-surface-200"
      >
        {{ formattedAmount }}
      </span>
    </div>
  </button>
</template>
