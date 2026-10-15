<script setup lang="ts">
import type {
  AllocationCreateRequest,
  AllocationResponse,
  AllocationUpdateRequest,
} from '~/types/shiftBudget'

/**
 * F08.7 シフト予算割当 作成・編集モーダル（Phase 10-γ）。
 *
 * <p>モード:</p>
 * <ul>
 *   <li>{@code allocation} 未指定 → 新規作成（POST /allocations）</li>
 *   <li>{@code allocation} 指定 → 編集（PUT /allocations/{id}、楽観ロック）</li>
 * </ul>
 *
 * <p>編集モードでは設計書 §6.2.1 に従い {@code allocated_amount} と {@code note} のみ更新可。
 * 期間・スコープ・費目の変更は不可（変更したい場合は新規作成を促す運用）。</p>
 */
interface Props {
  visible: boolean
  /** 編集対象（未指定=新規作成モード）*/
  allocation?: AllocationResponse | null
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'submit-create', payload: AllocationCreateRequest): void
  (e: 'submit-update', payload: { id: number; request: AllocationUpdateRequest }): void
}

const props = withDefaults(defineProps<Props>(), {
  allocation: null,
})
const emit = defineEmits<Emits>()

const { t } = useI18n()

const isEdit = computed(() => props.allocation !== null && props.allocation !== undefined)

// 作成フォーム
const createForm = ref<AllocationCreateRequest>({
  team_id: null,
  project_id: null,
  fiscal_year_id: 0,
  budget_category_id: 0,
  period_start: '',
  period_end: '',
  allocated_amount: 0,
  currency: 'JPY',
  note: '',
})

// 更新フォーム
const updateForm = ref<AllocationUpdateRequest>({
  allocated_amount: 0,
  note: '',
  version: 0,
})

watch(
  () => props.allocation,
  (a) => {
    if (a) {
      updateForm.value = {
        allocated_amount: a.allocated_amount ?? 0,
        note: a.note ?? '',
        version: a.version,
      }
    }
    else {
      // リセット
      createForm.value = {
        team_id: null,
        project_id: null,
        fiscal_year_id: 0,
        budget_category_id: 0,
        period_start: '',
        period_end: '',
        allocated_amount: 0,
        currency: 'JPY',
        note: '',
      }
    }
  },
  { immediate: true },
)

function close() {
  emit('update:visible', false)
}

function onSubmit() {
  if (isEdit.value && props.allocation) {
    emit('submit-update', { id: props.allocation.id, request: { ...updateForm.value } })
  }
  else {
    emit('submit-create', { ...createForm.value })
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="isEdit ? t('shiftBudget.allocation.edit') : t('shiftBudget.allocation.create')"
    :style="{ width: '600px' }"
    modal
    @update:visible="(v) => emit('update:visible', v)"
  >
    <!-- 編集モード -->
    <div v-if="isEdit && allocation" class="flex flex-col gap-4">
      <div class="rounded border border-surface-300 bg-surface-50 p-3 text-sm dark:border-surface-700 dark:bg-surface-800">
        <p>
          <span class="font-medium">{{ t('shiftBudget.allocation.id') }}:</span>
          {{ allocation.id }}
        </p>
        <p>
          <span class="font-medium">{{ t('shiftBudget.allocation.period') }}:</span>
          {{ allocation.period_start }} 〜 {{ allocation.period_end }}
        </p>
        <p>
          <span class="font-medium">{{ t('shiftBudget.allocation.version') }}:</span>
          {{ allocation.version }}
        </p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.amount') }}</label>
        <InputNumber v-model="updateForm.allocated_amount" mode="decimal" :min="0" class="w-full" />
        <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.allocation.form.amountHint') }}</p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.note') }}</label>
        <Textarea v-model="updateForm.note" rows="3" class="w-full" :maxlength="500" />
      </div>
    </div>

    <!-- 新規作成モード -->
    <div v-else class="flex flex-col gap-4">
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.team') }} ID</label>
          <InputNumber v-model="createForm.team_id" :min="1" class="w-full" :use-grouping="false" />
          <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.allocation.form.teamIdHint') }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.project') }} ID</label>
          <InputNumber v-model="createForm.project_id" :min="1" class="w-full" :use-grouping="false" />
          <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.allocation.form.projectIdHint') }}</p>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.fiscalYear') }} ID *</label>
          <InputNumber v-model="createForm.fiscal_year_id" :min="1" class="w-full" :use-grouping="false" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.category') }} ID *</label>
          <InputNumber v-model="createForm.budget_category_id" :min="1" class="w-full" :use-grouping="false" />
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.form.periodStart') }} *</label>
          <InputText v-model="createForm.period_start" type="date" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.form.periodEnd') }} *</label>
          <InputText v-model="createForm.period_end" type="date" class="w-full" />
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.amount') }} *</label>
        <InputNumber v-model="createForm.allocated_amount" mode="decimal" :min="0" class="w-full" />
        <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.allocation.form.amountHint') }}</p>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.currency') }}</label>
        <InputText v-model="createForm.currency" maxlength="3" class="w-full" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.note') }}</label>
        <Textarea v-model="createForm.note" rows="3" class="w-full" :maxlength="500" />
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="t('shiftBudget.allocation.form.cancel')" severity="secondary" @click="close" />
        <Button :label="t('shiftBudget.allocation.form.save')" @click="onSubmit" />
      </div>
    </template>
  </Dialog>
</template>
