<script setup lang="ts">
import { useForm } from 'vee-validate'
import { z } from 'zod'
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
  /** 保存 API が返した項目別エラー（400 の fieldErrors。キーは BE の camelCase）。 */
  fieldErrors?: Record<string, string>
  /** 項目に紐づかない保存エラー（BE の message。重複作成 SHIFT_BUDGET_011 など）。 */
  formError?: string
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'submit-create', payload: AllocationCreateRequest): void
  (e: 'submit-update', payload: { id: number; request: AllocationUpdateRequest }): void
}

const props = withDefaults(defineProps<Props>(), {
  allocation: null,
  fieldErrors: () => ({}),
  formError: '',
})
const emit = defineEmits<Emits>()

const { t } = useI18n()

const isEdit = computed(() => props.allocation !== null && props.allocation !== undefined)

const amountSchema = z.number().finite().nonnegative()
const { errors, defineField, handleSubmit, resetForm } = useForm<{ allocated_amount: number | null }>({
  initialValues: { allocated_amount: 0 },
  validationSchema: {
    allocated_amount: (value: unknown) => amountSchema.safeParse(value).success
      || t('shiftBudget.allocation.form.amountHint'),
  },
})
const [allocatedAmount] = defineField('allocated_amount')

// 作成フォーム
const createForm = ref<Omit<AllocationCreateRequest, 'allocated_amount'>>({
  team_id: null,
  project_id: null,
  fiscal_year_id: 0,
  budget_category_id: 0,
  period_start: '',
  period_end: '',
  currency: 'JPY',
  note: '',
})

// 更新フォーム
const updateForm = ref<Omit<AllocationUpdateRequest, 'allocated_amount'>>({
  note: '',
  version: 0,
})

watch(
  () => props.allocation,
  (a) => {
    resetForm({ values: { allocated_amount: a?.allocated_amount ?? 0 } })
    if (a) {
      updateForm.value = {
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
        currency: 'JPY',
        note: '',
      }
    }
  },
  { immediate: true },
)

watch(() => props.visible, (visible) => {
  if (visible) clientErrors.value = {}
  if (visible) resetForm({ values: { allocated_amount: props.allocation?.allocated_amount ?? 0 } })
})

/** 送信前に検出した項目別エラー（キーは作成リクエストの項目名）。 */
const clientErrors = ref<Record<string, string>>({})

/** BE の fieldErrors は camelCase。リクエスト項目名(snake_case)へ対応づける。 */
const serverFieldKey: Record<string, string> = {
  team_id: 'teamId',
  project_id: 'projectId',
  fiscal_year_id: 'fiscalYearId',
  budget_category_id: 'budgetCategoryId',
  period_start: 'periodStart',
  period_end: 'periodEnd',
}

function errorOf(field: string): string | undefined {
  return clientErrors.value[field]
    ?? props.fieldErrors?.[serverFieldKey[field] ?? field]
    ?? props.fieldErrors?.[field]
}

/** 作成モードの必須項目検証。BE が 400 を返す前に弾く。 */
function validateCreate(): boolean {
  const f = createForm.value
  const errors: Record<string, string> = {}
  if (!f.fiscal_year_id || f.fiscal_year_id < 1) errors.fiscal_year_id = t('shiftBudget.allocation.form.fiscalYearRequired')
  if (!f.budget_category_id || f.budget_category_id < 1) errors.budget_category_id = t('shiftBudget.allocation.form.categoryRequired')
  if (!f.period_start) errors.period_start = t('shiftBudget.allocation.form.periodStartRequired')
  if (!f.period_end) errors.period_end = t('shiftBudget.allocation.form.periodEndRequired')
  else if (f.period_start && f.period_end < f.period_start) errors.period_end = t('shiftBudget.allocation.form.periodEndBeforeStart')
  clientErrors.value = errors
  return Object.keys(errors).length === 0
}

function close() {
  emit('update:visible', false)
}

const onSubmit = handleSubmit((values) => {
  // Zodで負数・空・非有限値を拒否した値だけをAPIのnumber契約へ戻す。
  const amount = values.allocated_amount
  if (typeof amount !== 'number') return
  if (isEdit.value && props.allocation) {
    emit('submit-update', { id: props.allocation.id, request: { ...updateForm.value, allocated_amount: amount } })
  }
  else {
    if (!validateCreate()) return
    emit('submit-create', { ...createForm.value, allocated_amount: amount })
  }
})
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
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.amount') }}</label>
        <!-- min=0はPrimeVueが打鍵した負号だけを黙殺するため使わず、検証で保存を拒否する。 -->
        <InputNumber v-model="allocatedAmount" mode="decimal" :invalid="!!errors.allocated_amount" class="w-full" />
        <p v-if="errors.allocated_amount" role="alert" class="mt-1 text-sm text-red-600">{{ errors.allocated_amount }}</p>
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
          <InputNumber v-model="createForm.fiscal_year_id" :min="1" class="w-full" :use-grouping="false" :invalid="!!errorOf('fiscal_year_id')" />
          <p v-if="errorOf('fiscal_year_id')" role="alert" class="mt-1 text-sm text-red-600" data-testid="allocation-error-fiscal_year_id">{{ errorOf('fiscal_year_id') }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.category') }} ID *</label>
          <InputNumber v-model="createForm.budget_category_id" :min="1" class="w-full" :use-grouping="false" :invalid="!!errorOf('budget_category_id')" />
          <p v-if="errorOf('budget_category_id')" role="alert" class="mt-1 text-sm text-red-600" data-testid="allocation-error-budget_category_id">{{ errorOf('budget_category_id') }}</p>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.form.periodStart') }} *</label>
          <InputText v-model="createForm.period_start" type="date" class="w-full" :invalid="!!errorOf('period_start')" />
          <p v-if="errorOf('period_start')" role="alert" class="mt-1 text-sm text-red-600" data-testid="allocation-error-period_start">{{ errorOf('period_start') }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.form.periodEnd') }} *</label>
          <InputText v-model="createForm.period_end" type="date" class="w-full" :invalid="!!errorOf('period_end')" />
          <p v-if="errorOf('period_end')" role="alert" class="mt-1 text-sm text-red-600" data-testid="allocation-error-period_end">{{ errorOf('period_end') }}</p>
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.allocation.amount') }} *</label>
        <InputNumber v-model="allocatedAmount" mode="decimal" :invalid="!!errors.allocated_amount" class="w-full" />
        <p v-if="errors.allocated_amount" role="alert" class="mt-1 text-sm text-red-600">{{ errors.allocated_amount }}</p>
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

    <p v-if="formError" role="alert" class="mt-4 text-sm text-red-600" data-testid="allocation-form-error">{{ formError }}</p>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="t('shiftBudget.allocation.form.cancel')" severity="secondary" @click="close" />
        <Button :label="t('shiftBudget.allocation.form.save')" @click="onSubmit" />
      </div>
    </template>
  </Dialog>
</template>
