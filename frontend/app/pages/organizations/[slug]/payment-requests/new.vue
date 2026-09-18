<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'

definePageMeta({ middleware: 'auth', layout: 'default' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { isAdminOrDeputy, orgTeams } = useOrgShellContext()
const { resolveScopeId } = useActivityScopeId()
const paymentRequestApi = usePaymentRequestApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const orgSlug = computed(() => String(route.params.slug))
const orgId = ref<number | null>(null)
const resolving = ref(true)
const submitting = ref(false)

const validationSchema = toTypedSchema(z.object({
  payerTeamId: z.number({ message: t('required') }).int().positive(),
  title: z.string().trim().min(1, t('required')).max(120, t('max_length', { max: 120 })),
  description: z.string().max(1000, t('max_length', { max: 1000 })).optional(),
  faceAmount: z.number({ message: t('required') }).int().positive(t('positive_number')),
  dueDate: z.date({ message: t('required') }),
}))

const { defineField, handleSubmit, errors } = useForm({ validationSchema })
const [payerTeamId] = defineField('payerTeamId')
const [title] = defineField('title')
const [description] = defineField('description')
const [faceAmount] = defineField('faceAmount')
const [dueDate] = defineField('dueDate')

const teamOptions = computed(() => orgTeams.value.map((team) => ({
  label: team.nickname1 || team.name,
  value: team.id,
})))

const submit = handleSubmit(async (values) => {
  if (!orgId.value || submitting.value) return
  submitting.value = true
  try {
    await paymentRequestApi.createPaymentRequest(orgId.value, {
      payerTeamId: values.payerTeamId,
      title: values.title,
      description: values.description || undefined,
      faceAmount: values.faceAmount,
      currency: 'JPY',
      dueDate: values.dueDate.toLocaleDateString('sv-SE'),
    })
    notification.success(t('payment.membership.paymentRequest.created'))
    await router.push(`/organizations/${orgSlug.value}/payment-requests`)
  } catch (error: unknown) {
    handleApiError(error, 'payment-request.organization.create')
  } finally {
    submitting.value = false
  }
})

onMounted(async () => {
  orgId.value = await resolveScopeId('ORGANIZATION', orgSlug.value)
  resolving.value = false
})
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <PageHeader
      :title="t('payment.membership.paymentRequest.create')"
      :description="t('payment.membership.paymentRequest.adminDescription')"
    />
    <PageLoading v-if="resolving" />
    <Message v-else-if="!isAdminOrDeputy" severity="error" :closable="false">
      {{ t('payment.admin.permissionDenied') }}
    </Message>
    <Message v-else-if="orgId === null" severity="error" :closable="false">
      {{ t('payment.membership.paymentRequest.loadError') }}
    </Message>
    <SectionCard v-else :title="t('payment.membership.paymentRequest.create')">
      <form class="grid gap-4" @submit.prevent="submit">
        <div class="grid gap-1">
          <label for="payer-team">{{ t('payment.membership.paymentRequest.team') }}</label>
          <Select
            id="payer-team"
            v-model="payerTeamId"
            :options="teamOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :invalid="Boolean(errors.payerTeamId)"
          />
          <small v-if="errors.payerTeamId" class="text-red-600">{{ errors.payerTeamId }}</small>
        </div>
        <div class="grid gap-1">
          <label for="request-title">{{ t('payment.membership.paymentRequest.title') }}</label>
          <InputText id="request-title" v-model="title" maxlength="120" :invalid="Boolean(errors.title)" />
          <small v-if="errors.title" class="text-red-600">{{ errors.title }}</small>
        </div>
        <div class="grid gap-1">
          <label for="request-description">{{ t('payment.membership.paymentRequest.description') }}</label>
          <Textarea id="request-description" v-model="description" rows="4" maxlength="1000" />
          <small v-if="errors.description" class="text-red-600">{{ errors.description }}</small>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <div class="grid gap-1">
            <label for="face-amount">{{ t('payment.membership.paymentRequest.amount') }}</label>
            <InputNumber id="face-amount" v-model="faceAmount" :min="1" :use-grouping="false" />
            <small v-if="errors.faceAmount" class="text-red-600">{{ errors.faceAmount }}</small>
          </div>
          <div class="grid gap-1">
            <label for="due-date">{{ t('payment.membership.paymentRequest.due') }}</label>
            <DatePicker id="due-date" v-model="dueDate" date-format="yy/mm/dd" show-icon />
            <small v-if="errors.dueDate" class="text-red-600">{{ errors.dueDate }}</small>
          </div>
        </div>
        <div class="flex flex-wrap justify-end gap-2">
          <Button
            type="button"
            :label="t('button.cancel')"
            severity="secondary"
            outlined
            @click="router.push(`/organizations/${orgSlug}/payment-requests`)"
          />
          <Button type="submit" :label="t('button.create')" :loading="submitting" :disabled="submitting" />
        </div>
      </form>
    </SectionCard>
  </div>
</template>
