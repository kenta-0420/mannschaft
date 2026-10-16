<script setup lang="ts">
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import type { LoyaltyRule } from '~/types/team-loyalty'
const props = defineProps<{ teamId: number; rule?: LoyaltyRule }>()
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const { defineField, handleSubmit, errors, isSubmitting } = useForm({
  validationSchema: toTypedSchema(z.object({
    name: z.string().trim().min(1).max(100), points: z.number().int().min(1).max(10000),
    frequency: z.enum(['ONCE_PER_JST_DAY', 'EACH_STAFF_CONFIRMATION']),
    isSelfAwardEnabled: z.boolean(), isActive: z.boolean(),
  }).refine(value => value.frequency !== 'EACH_STAFF_CONFIRMATION' || !value.isSelfAwardEnabled, { path: ['frequency'], message: t('loyalty.rule.selfDailyOnly') })),
  initialValues: props.rule ?? { name: '', points: 1, frequency: 'ONCE_PER_JST_DAY' as const, isSelfAwardEnabled: true, isActive: true },
})
const [name] = defineField('name')
const [points] = defineField('points')
const [frequency] = defineField('frequency')
const [isSelfAwardEnabled] = defineField('isSelfAwardEnabled')
const [isActive] = defineField('isActive')
const save = handleSubmit(async (value) => {
  try {
    const body = { name: value.name, points: value.points, frequency: value.frequency, isSelfAwardEnabled: value.isSelfAwardEnabled }
    if (props.rule) await api.updateRule(props.teamId, props.rule.id, { ...body, isActive: value.isActive, expectedVersion: props.rule.version })
    else await api.createRule(props.teamId, body)
    notification.success(t('loyalty.saved')); emit('saved')
  } catch (e) { handleApiError(e) }
})
</script>
<template>
  <SectionCard :title="t('loyalty.rule.title')">
    <form class="space-y-3" @submit.prevent="save">
      <label class="block">{{ t('loyalty.name') }}<InputText v-model="name" class="mt-1 w-full text-base" /></label>
      <label class="block">{{ t('loyalty.rule.amount') }}<InputNumber v-model="points" :min="1" :max="10000" class="mt-1 w-full" /></label>
      <label class="block">{{ t('loyalty.rule.frequency') }}<Select v-model="frequency" class="mt-1 w-full" :options="[{label:t('loyalty.rule.frequencyDaily'),value:'ONCE_PER_JST_DAY'},{label:t('loyalty.rule.frequencyStaff'),value:'EACH_STAFF_CONFIRMATION'}]" option-label="label" option-value="value" /></label>
      <label class="flex min-h-11 items-center gap-3"><Checkbox v-model="isSelfAwardEnabled" binary />{{ t('loyalty.rule.selfEnabled') }}</label>
      <label v-if="rule" class="flex min-h-11 items-center gap-3"><Checkbox v-model="isActive" binary />{{ t('loyalty.active') }}</label>
      <p v-for="(message, field) in errors" :key="field" role="alert">{{ message }}</p>
      <Button type="submit" class="min-h-11" :label="t('loyalty.save')" :loading="isSubmitting" />
    </form>
  </SectionCard>
</template>
