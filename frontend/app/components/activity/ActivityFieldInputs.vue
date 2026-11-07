<script setup lang="ts">
import type { ActivityTemplateField } from '~/types/activity'
import { parseSelectOptions, type ActivityFieldValue } from '~/utils/activityFields'
defineProps<{ fields: ActivityTemplateField[] }>()
const values = defineModel<Record<string, ActivityFieldValue>>({ required: true })
</script>

<template>
  <div v-for="field in fields" :key="field.fieldKey" data-testid="activity-custom-field">
    <label
      :for="`activity-field-${field.fieldKey}`"
      class="mb-1 inline-flex min-h-11 items-center text-sm font-medium"
    >
      {{ field.fieldLabel }} <span v-if="field.isRequired">*</span>
      <span v-if="field.unit">({{ field.unit }})</span>
    </label>
    <InputText
      v-if="field.fieldType === 'TEXT'"
      :id="`activity-field-${field.fieldKey}`"
      v-model="values[field.fieldKey] as string"
      class="min-h-11 w-full text-base"
      :placeholder="field.placeholder ?? ''"
    />
    <Textarea
      v-else-if="field.fieldType === 'TEXTAREA'"
      :id="`activity-field-${field.fieldKey}`"
      v-model="values[field.fieldKey] as string"
      class="min-h-11 w-full text-base"
      rows="2"
      auto-resize
    />
    <InputNumber
      v-else-if="field.fieldType === 'NUMBER'"
      v-model="values[field.fieldKey] as number"
      input-class="min-h-11 text-base"
      :input-id="`activity-field-${field.fieldKey}`"
      class="w-full"
    />
    <DatePicker
      v-else-if="field.fieldType === 'DATE' || field.fieldType === 'DATETIME'"
      v-model="values[field.fieldKey] as Date"
      input-class="min-h-11 text-base"
      :input-id="`activity-field-${field.fieldKey}`"
      class="w-full"
      date-format="yy/mm/dd"
      :show-time="field.fieldType === 'DATETIME'"
      hour-format="24"
      show-icon
    />
    <Select
      v-else-if="field.fieldType === 'SELECT'"
      v-model="values[field.fieldKey] as string"
      :input-id="`activity-field-${field.fieldKey}`"
      :options="parseSelectOptions(field.optionsJson)"
      option-label="label"
      option-value="value"
      class="min-h-11 w-full"
    />
    <Checkbox
      v-else-if="field.fieldType === 'CHECKBOX'"
      v-model="values[field.fieldKey] as boolean"
      :input-id="`activity-field-${field.fieldKey}`"
      binary
    />
  </div>
</template>
