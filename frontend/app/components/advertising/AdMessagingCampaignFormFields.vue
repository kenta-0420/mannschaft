<script setup lang="ts">
// F09.17 メッセージ型キャンペーン 基本情報入力フィールド集約
// 設計書 §4 POST /campaigns/messaging のバリデーション仕様準拠

const { t } = useI18n()

interface FormModel {
  name: string
  totalBudgetYen: number
  startsAt: string
  endsAt: string
  scheduledTimezone: string
  frequencyCapOverride: number | null
}

const props = defineProps<{
  modelValue: FormModel
  errors?: Partial<Record<keyof FormModel, string>>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: FormModel): void
}>()

function updateField<K extends keyof FormModel>(key: K, value: FormModel[K]) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

const timezones = ['Asia/Tokyo', 'UTC', 'America/Los_Angeles', 'Europe/Berlin', 'Asia/Seoul', 'Asia/Shanghai']
</script>

<template>
  <div class="space-y-4">
    <!-- name -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.name') }} <span class="text-red-500">*</span></label>
      <InputText
        :model-value="modelValue.name"
        class="w-full"
        :placeholder="t('advertising.advertiser_crud.form.name_placeholder')"
        maxlength="120"
        @update:model-value="(v) => updateField('name', String(v ?? ''))"
      />
      <p v-if="errors?.name" class="mt-1 text-xs text-red-500">{{ errors.name }}</p>
    </div>

    <!-- total budget -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.total_budget') }} <span class="text-red-500">*</span></label>
      <InputNumber
        :model-value="modelValue.totalBudgetYen"
        :min="1000"
        :max="100000000"
        :step="1000"
        class="w-full"
        currency="JPY"
        mode="currency"
        locale="ja-JP"
        @update:model-value="(v) => updateField('totalBudgetYen', Number(v ?? 0))"
      />
      <p v-if="errors?.totalBudgetYen" class="mt-1 text-xs text-red-500">{{ errors.totalBudgetYen }}</p>
    </div>

    <!-- starts_at -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.starts_at') }} <span class="text-red-500">*</span></label>
      <InputText
        :model-value="modelValue.startsAt"
        type="datetime-local"
        class="w-full"
        @update:model-value="(v) => updateField('startsAt', String(v ?? ''))"
      />
      <p v-if="errors?.startsAt" class="mt-1 text-xs text-red-500">{{ errors.startsAt }}</p>
    </div>

    <!-- ends_at -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.ends_at') }} <span class="text-red-500">*</span></label>
      <InputText
        :model-value="modelValue.endsAt"
        type="datetime-local"
        class="w-full"
        @update:model-value="(v) => updateField('endsAt', String(v ?? ''))"
      />
      <p v-if="errors?.endsAt" class="mt-1 text-xs text-red-500">{{ errors.endsAt }}</p>
    </div>

    <!-- scheduled timezone -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.scheduled_timezone') }} <span class="text-red-500">*</span></label>
      <Select
        :model-value="modelValue.scheduledTimezone"
        :options="timezones"
        class="w-full"
        @update:model-value="(v) => updateField('scheduledTimezone', String(v ?? 'Asia/Tokyo'))"
      />
      <p v-if="errors?.scheduledTimezone" class="mt-1 text-xs text-red-500">{{ errors.scheduledTimezone }}</p>
    </div>

    <!-- frequency cap override -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('advertising.advertiser_crud.form.frequency_cap_override') }}</label>
      <InputNumber
        :model-value="modelValue.frequencyCapOverride ?? undefined"
        :min="1"
        :max="30"
        class="w-full"
        show-buttons
        @update:model-value="(v) => updateField('frequencyCapOverride', v == null ? null : Number(v))"
      />
      <p class="mt-1 text-xs text-surface-500">{{ t('advertising.advertiser_crud.form.frequency_cap_help') }}</p>
      <p v-if="errors?.frequencyCapOverride" class="mt-1 text-xs text-red-500">{{ errors.frequencyCapOverride }}</p>
    </div>
  </div>
</template>
