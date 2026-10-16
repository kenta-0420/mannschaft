<script setup lang="ts">
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import type { LoyaltyReward } from '~/types/team-loyalty'
const props = defineProps<{ teamId: number; reward?: LoyaltyReward }>()
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const { defineField, handleSubmit, errors, isSubmitting, resetForm } = useForm({
  validationSchema: toTypedSchema(z.object({ name: z.string().trim().min(1).max(100), description: z.string().max(1000).nullable(), requiredPoints: z.number().int().min(1).max(1000000), isActive: z.boolean() })),
  initialValues: props.reward ?? { name: '', description: null, requiredPoints: 20, isActive: true },
})
const [name] = defineField('name')
const [description] = defineField('description')
const [requiredPoints] = defineField('requiredPoints')
const [isActive] = defineField('isActive')
const save = handleSubmit(async (value) => {
  try {
    const body = { name: value.name, description: value.description?.trim() || null, requiredPoints: value.requiredPoints }
    if (props.reward) await api.updateReward(props.teamId, props.reward.id, { ...body, isActive: value.isActive, expectedVersion: props.reward.version })
    else { await api.createReward(props.teamId, body); resetForm() }
    notification.success(t('loyalty.saved')); emit('saved')
  } catch (e) { handleApiError(e) }
})
</script>
<template>
  <SectionCard :title="reward?.name ?? t('loyalty.reward.add')">
    <form class="space-y-3" @submit.prevent="save">
      <label class="block">{{ t('loyalty.name') }}<InputText v-model="name" class="mt-1 w-full text-base" /></label>
      <label class="block">{{ t('loyalty.description') }}<Textarea v-model="description" class="mt-1 w-full text-base" /></label>
      <label class="block">{{ t('loyalty.reward.amount') }}<InputNumber v-model="requiredPoints" :min="1" :max="1000000" class="mt-1 w-full" /></label>
      <label v-if="reward" class="flex min-h-11 items-center gap-3"><Checkbox v-model="isActive" binary />{{ t('loyalty.active') }}</label>
      <p v-for="(message, field) in errors" :key="field" role="alert">{{ message }}</p>
      <Button type="submit" class="min-h-11" :label="t('loyalty.save')" :loading="isSubmitting" />
    </form>
  </SectionCard>
</template>
