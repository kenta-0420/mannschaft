<script setup lang="ts">
/**
 * 重説書フォームの動的フィールドレンダラー（F09.14 Phase 2-β-5）。
 *
 * form_schema.fields[*].type に応じて PrimeVue コンポーネントを切り替えて表示する。
 *  - TEXT / NUMBER / DATE / SELECT / MULTISELECT / CHECKBOX / TEXTAREA: 編集可
 *  - AUTO_TABLE / AUTO_FIELD: 読み取り専用（DisclosureAutoFillBanner で再取得）
 */
import type { DisclosureFormField } from '~/types/disclosure'

const props = defineProps<{
  field: DisclosureFormField
  modelValue: unknown
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
}>()

const { t } = useI18n()

/** AUTO_TABLE 用の行配列。modelValue が想定外なら空配列を返す。 */
const autoTableRows = computed<Record<string, unknown>[]>(() => {
  const v = props.modelValue
  if (Array.isArray(v)) {
    return v.filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null)
  }
  return []
})

/** TEXT/TEXTAREA 用の string ガード。 */
const stringValue = computed<string>({
  get: () => (typeof props.modelValue === 'string' ? props.modelValue : ''),
  set: (v: string) => emit('update:modelValue', v),
})

/** NUMBER 用の number|null ガード。 */
const numberValue = computed<number | null>({
  get: () => (typeof props.modelValue === 'number' ? props.modelValue : null),
  set: (v: number | null) => emit('update:modelValue', v),
})

/** CHECKBOX 用の boolean ガード。 */
const booleanValue = computed<boolean>({
  get: () => Boolean(props.modelValue),
  set: (v: boolean) => emit('update:modelValue', v),
})

/** DATE 用の Date|null ガード（ISO 文字列 ↔ Date）。 */
const dateValue = computed<Date | null>({
  get: () => {
    const v = props.modelValue
    if (typeof v === 'string' && v) {
      const d = new Date(v)
      return Number.isNaN(d.getTime()) ? null : d
    }
    return null
  },
  set: (v: Date | null) => {
    if (v === null) {
      emit('update:modelValue', null)
      return
    }
    const y = v.getFullYear()
    const m = String(v.getMonth() + 1).padStart(2, '0')
    const day = String(v.getDate()).padStart(2, '0')
    emit('update:modelValue', `${y}-${m}-${day}`)
  },
})

/** SELECT 用の string|null ガード。 */
const selectValue = computed<string | null>({
  get: () => (typeof props.modelValue === 'string' ? props.modelValue : null),
  set: (v: string | null) => emit('update:modelValue', v),
})

/** MULTISELECT 用の string[] ガード。 */
const multiselectValue = computed<string[]>({
  get: () => {
    const v = props.modelValue
    if (Array.isArray(v)) {
      return v.filter((x): x is string => typeof x === 'string')
    }
    return []
  },
  set: (v: string[]) => emit('update:modelValue', v),
})

/** AUTO_FIELD 用の表示文字列化。 */
const autoFieldDisplay = computed<string>(() => {
  const v = props.modelValue
  if (v === null || v === undefined) return '-'
  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
    return String(v)
  }
  return JSON.stringify(v)
})

const inputId = computed(() => `disclosure-field-${props.field.id}`)
</script>

<template>
  <div class="disclosure-field" :data-testid="`disclosure-field-${field.id}`">
    <label
      v-if="field.type !== 'CHECKBOX'"
      :for="inputId"
      class="mb-1 block text-sm font-medium text-surface-700 dark:text-surface-200"
    >
      {{ field.label }}
      <span v-if="field.required" class="text-red-500">*</span>
    </label>

    <!-- TEXT -->
    <InputText
      v-if="field.type === 'TEXT'"
      :id="inputId"
      v-model="stringValue"
      :disabled="disabled"
      :maxlength="field.maxLength"
      :placeholder="field.hint"
      class="w-full"
    />

    <!-- NUMBER -->
    <InputNumber
      v-else-if="field.type === 'NUMBER'"
      :id="inputId"
      v-model="numberValue"
      :disabled="disabled"
      :placeholder="field.hint"
      class="w-full"
    />

    <!-- DATE -->
    <Calendar
      v-else-if="field.type === 'DATE'"
      :id="inputId"
      v-model="dateValue"
      :disabled="disabled"
      date-format="yy-mm-dd"
      show-icon
      show-button-bar
      :placeholder="field.hint"
      class="w-full"
    />

    <!-- SELECT -->
    <Dropdown
      v-else-if="field.type === 'SELECT'"
      :id="inputId"
      v-model="selectValue"
      :options="field.options ?? []"
      option-label="label"
      option-value="value"
      :disabled="disabled"
      :placeholder="field.hint"
      show-clear
      class="w-full"
    />

    <!-- MULTISELECT -->
    <MultiSelect
      v-else-if="field.type === 'MULTISELECT'"
      :id="inputId"
      v-model="multiselectValue"
      :options="field.options ?? []"
      option-label="label"
      option-value="value"
      :disabled="disabled"
      :placeholder="field.hint"
      class="w-full"
    />

    <!-- CHECKBOX -->
    <label
      v-else-if="field.type === 'CHECKBOX'"
      class="flex items-center gap-2 text-sm font-medium"
    >
      <Checkbox v-model="booleanValue" binary :disabled="disabled" />
      {{ field.label }}
      <span v-if="field.required" class="text-red-500">*</span>
    </label>

    <!-- TEXTAREA -->
    <Textarea
      v-else-if="field.type === 'TEXTAREA'"
      :id="inputId"
      v-model="stringValue"
      :disabled="disabled"
      :maxlength="field.maxLength"
      :placeholder="field.hint"
      rows="4"
      class="w-full"
    />

    <!-- AUTO_TABLE: 読み取り専用テーブル -->
    <div v-else-if="field.type === 'AUTO_TABLE'" class="rounded-md border border-surface-200 dark:border-surface-700">
      <DataTable
        :value="autoTableRows"
        size="small"
        striped-rows
        class="w-full"
      >
        <Column
          v-for="col in field.columns ?? []"
          :key="col"
          :field="col"
          :header="col"
        />
        <template #empty>
          <div class="p-3 text-center text-sm text-surface-500">
            {{ t('disclosure.noDrafts') }}
          </div>
        </template>
      </DataTable>
    </div>

    <!-- AUTO_FIELD: 読み取り専用表示 -->
    <div
      v-else-if="field.type === 'AUTO_FIELD'"
      class="rounded-md border border-dashed border-surface-300 bg-surface-50 px-3 py-2 text-sm text-surface-700 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-200"
    >
      {{ autoFieldDisplay }}
    </div>

    <p
      v-if="field.hint && field.type !== 'TEXT' && field.type !== 'TEXTAREA' && field.type !== 'NUMBER'"
      class="mt-1 text-xs text-surface-500 dark:text-surface-400"
    >
      {{ field.hint }}
    </p>
  </div>
</template>
