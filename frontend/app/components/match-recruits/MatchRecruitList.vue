<script setup lang="ts">
/**
 * F17.1 村機能 — 練習試合・募集タブ 一覧コンポーネント
 *
 * 親 (villages/[id]/match-recruits.vue) から props で渡された
 * フィルタ状態・募集リストを表示し、操作イベントを emit するのみの
 * 純粋なプレゼンテーション層。
 */
import type {
  VillageMatchRecruitCategory,
  VillageMatchRecruitResponse,
  VillageMatchRecruitStatus,
} from '~/types/village'

type CategoryFilter = VillageMatchRecruitCategory | 'ALL'
type StatusFilter = VillageMatchRecruitStatus | 'ALL'

interface DropdownOption<T> {
  value: T
  label: string
}

const props = defineProps<{
  recruits: VillageMatchRecruitResponse[]
  recruitsLoading: boolean
  isVillager: boolean
  categoryFilter: CategoryFilter
  statusFilter: StatusFilter
  categoryDropdownOptions: DropdownOption<CategoryFilter>[]
  statusDropdownOptions: DropdownOption<StatusFilter>[]
}>()

const emit = defineEmits<{
  (e: 'update:categoryFilter', value: CategoryFilter): void
  (e: 'update:statusFilter', value: StatusFilter): void
  (e: 'create'): void
  (e: 'select', recruit: VillageMatchRecruitResponse): void
}>()

const { t } = useI18n()

const categoryFilterModel = computed({
  get: () => props.categoryFilter,
  set: value => emit('update:categoryFilter', value),
})
const statusFilterModel = computed({
  get: () => props.statusFilter,
  set: value => emit('update:statusFilter', value),
})

function severityForStatus(
  status: VillageMatchRecruitStatus,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'OPEN':
      return 'success'
    case 'CLOSED':
      return 'secondary'
    case 'FULFILLED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <!-- フィルタ + 作成ボタン -->
    <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
      <div class="flex items-center gap-2 flex-wrap">
        <Select
          v-model="categoryFilterModel"
          :options="categoryDropdownOptions"
          option-value="value"
          option-label="label"
          class="w-44"
        />
        <Select
          v-model="statusFilterModel"
          :options="statusDropdownOptions"
          option-value="value"
          option-label="label"
          class="w-44"
        />
      </div>
      <Button
        v-if="isVillager"
        :label="t('village.matchRecruit.create')"
        icon="pi pi-plus"
        severity="primary"
        size="small"
        @click="emit('create')"
      />
    </div>

    <!-- 募集一覧 -->
    <div v-if="recruitsLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="recruits.length === 0"
      icon="pi pi-flag"
      :message="t('village.matchRecruit.empty')"
    />
    <div v-else class="flex flex-col gap-3">
      <button
        v-for="r in recruits"
        :key="r.id"
        type="button"
        class="village-match-recruit__row flex flex-col gap-1 rounded-lg border border-surface-200 p-4 text-left transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
        @click="emit('select', r)"
      >
        <div class="flex items-center justify-between gap-2 flex-wrap">
          <div class="flex items-center gap-2">
            <Badge
              :value="t(`village.matchRecruit.category.${r.category}`)"
              severity="secondary"
            />
            <Badge
              :value="t(`village.matchRecruit.status.${r.status}`)"
              :severity="severityForStatus(r.status)"
            />
          </div>
          <span v-if="r.applicationDeadline" class="text-xs text-surface-500">
            {{ t('village.matchRecruit.deadline') }}: {{ r.applicationDeadline }}
          </span>
        </div>
        <span class="font-semibold truncate">{{ r.title }}</span>
        <div class="text-xs text-surface-500 flex items-center gap-3 flex-wrap">
          <span v-if="r.matchDate">
            <i class="pi pi-calendar mr-1" />{{ r.matchDate }}
            <span v-if="r.matchTimeStart"> {{ r.matchTimeStart }}</span>
            <span v-if="r.matchTimeEnd"> - {{ r.matchTimeEnd }}</span>
          </span>
          <span v-if="r.venue">
            <i class="pi pi-map-marker mr-1" />{{ r.venue }}
          </span>
          <span v-if="r.requiredCount">
            <i class="pi pi-users mr-1" />{{ r.requiredCount }}
          </span>
        </div>
      </button>
    </div>
  </div>
</template>
