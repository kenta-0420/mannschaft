<script setup lang="ts">
import type { BudgetSummary } from '~/types/budget'

defineProps<{ summary: BudgetSummary }>()
const { t, locale } = useI18n()
const money = (value: number) =>
  new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(value)
</script>

<template>
  <div class="grid gap-4 sm:grid-cols-3">
    <SectionCard
      v-for="metric in ['totalIncome', 'totalExpense', 'balance'] as const"
      :key="metric"
    >
      <p class="text-xs text-surface-500">{{ t(`budget_accounting.${metric}`) }}</p>
      <p
        class="text-2xl font-bold"
        :class="metric === 'totalExpense' || summary[metric] < 0 ? 'text-red-500' : 'text-primary'"
      >
        {{ money(summary[metric]) }}
      </p>
    </SectionCard>
  </div>
  <SectionCard class="mt-4">
    <h2 class="mb-3 font-semibold">{{ t('budget_accounting.categories') }}</h2>
    <DashboardEmptyState
      v-if="!summary.categories.length"
      icon="pi pi-list"
      :message="t('budget_accounting.empty_categories')"
    />
    <div v-else class="space-y-3">
      <div
        v-for="cat in summary.categories"
        :key="cat.categoryId"
        class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-100 py-2 dark:border-surface-700"
      >
        <span>{{ cat.categoryName }}</span>
        <div class="flex flex-wrap gap-3 text-sm">
          <span>{{
            t('budget_accounting.budget_amount', { amount: money(cat.budgetAmount) })
          }}</span>
          <span :class="cat.categoryType === 'INCOME' ? 'text-green-600' : 'text-red-500'">{{
            t('budget_accounting.actual_amount', { amount: money(cat.actualAmount) })
          }}</span>
          <span :class="cat.executionRate > 100 ? 'font-bold text-red-500' : 'text-surface-500'">{{
            t('budget_accounting.execution_rate', { rate: cat.executionRate })
          }}</span>
        </div>
      </div>
    </div>
  </SectionCard>
</template>
