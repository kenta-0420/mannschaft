<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ created: [] }>()

const { t } = useI18n()
const { createUnit } = useResidentApi()
const { showSuccess } = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()

const schema = toTypedSchema(
  z.object({
    unitNumber: z
      .string()
      .trim()
      .min(1, t('property.residents.validation.unitNumberRequired'))
      .max(50, t('property.residents.validation.unitNumberMax')),
    floor: z.number().nullable(),
    unitType: z.string().trim().max(20, t('property.residents.validation.unitTypeMax')),
    layout: z.string().trim().max(20, t('property.residents.validation.layoutMax')),
    areaSqm: z.number().nullable(),
    notes: z.string(),
  }),
)

const {
  defineField,
  handleSubmit,
  errors,
  resetForm: resetValidation,
} = useForm({
  validationSchema: schema,
  initialValues: {
    unitNumber: '',
    floor: null,
    unitType: '',
    layout: '',
    areaSqm: null,
    notes: '',
  },
})

const [unitNumber] = defineField('unitNumber')
const [floor] = defineField('floor')
const [unitType] = defineField('unitType')
const [layout] = defineField('layout')
const [areaSqm] = defineField('areaSqm')
const [notes] = defineField('notes')

const submitting = ref(false)
const serverErrors = ref<Record<string, string>>({})

function resetForm() {
  resetValidation()
  serverErrors.value = {}
}

const submit = handleSubmit(async (values) => {
  if (submitting.value) return

  submitting.value = true
  serverErrors.value = {}
  try {
    await createUnit(props.scopeType, props.scopeId, {
      unitNumber: values.unitNumber,
      floor: values.floor,
      unitType: values.unitType || undefined,
      layout: values.layout || undefined,
      areaSqm: values.areaSqm,
      notes: values.notes.trim() || undefined,
    })
    visible.value = false
    showSuccess(t('property.residents.createSuccess'))
    emit('created')
  } catch (error) {
    serverErrors.value = getFieldErrors(error)
    if (Object.keys(serverErrors.value).length === 0) {
      handleApiError(error, t('property.residents.createFailed'))
    }
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('property.residents.createTitle')"
    :style="{ width: '480px', maxWidth: '95vw' }"
    :breakpoints="{ '640px': '90vw' }"
    @hide="resetForm"
  >
    <form
      class="flex flex-col gap-4"
      data-testid="dwelling-unit-create-form"
      @submit.prevent="submit"
    >
      <div>
        <label class="mb-1 block text-sm font-medium" for="dwelling-unit-number">
          {{ t('property.residents.unitNumber') }}
          <span class="text-red-500" aria-hidden="true">*</span>
        </label>
        <InputText
          id="dwelling-unit-number"
          v-model="unitNumber"
          class="w-full text-base"
          :class="{ 'p-invalid': errors.unitNumber || serverErrors.unitNumber }"
          :maxlength="50"
          :placeholder="t('property.residents.unitNumberPlaceholder')"
          :disabled="submitting"
          :aria-invalid="!!(errors.unitNumber || serverErrors.unitNumber)"
          :aria-describedby="
            errors.unitNumber || serverErrors.unitNumber ? 'dwelling-unit-number-error' : undefined
          "
          data-testid="dwelling-unit-number-input"
        />
        <small
          v-if="errors.unitNumber || serverErrors.unitNumber"
          id="dwelling-unit-number-error"
          class="text-red-500"
          data-testid="dwelling-unit-number-error"
          >{{ errors.unitNumber || serverErrors.unitNumber }}</small
        >
      </div>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium" for="dwelling-unit-floor">{{
            t('property.residents.floor')
          }}</label>
          <InputNumber
            id="dwelling-unit-floor"
            v-model="floor"
            class="w-full text-base"
            :disabled="submitting"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="dwelling-unit-area">{{
            t('property.residents.areaSqm')
          }}</label>
          <InputNumber
            id="dwelling-unit-area"
            v-model="areaSqm"
            class="w-full text-base"
            :disabled="submitting"
          />
        </div>
      </div>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium" for="dwelling-unit-type">{{
            t('property.residents.unitType')
          }}</label>
          <InputText
            id="dwelling-unit-type"
            v-model="unitType"
            class="w-full text-base"
            :class="{ 'p-invalid': errors.unitType || serverErrors.unitType }"
            :maxlength="20"
            :disabled="submitting"
            :aria-invalid="!!(errors.unitType || serverErrors.unitType)"
            :aria-describedby="
              errors.unitType || serverErrors.unitType ? 'dwelling-unit-type-error' : undefined
            "
          />
          <small
            v-if="errors.unitType || serverErrors.unitType"
            id="dwelling-unit-type-error"
            class="text-red-500"
            >{{ errors.unitType || serverErrors.unitType }}</small
          >
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="dwelling-unit-layout">{{
            t('property.residents.layout')
          }}</label>
          <InputText
            id="dwelling-unit-layout"
            v-model="layout"
            class="w-full text-base"
            :class="{ 'p-invalid': errors.layout || serverErrors.layout }"
            :maxlength="20"
            :disabled="submitting"
            :aria-invalid="!!(errors.layout || serverErrors.layout)"
            :aria-describedby="
              errors.layout || serverErrors.layout ? 'dwelling-unit-layout-error' : undefined
            "
          />
          <small
            v-if="errors.layout || serverErrors.layout"
            id="dwelling-unit-layout-error"
            class="text-red-500"
            >{{ errors.layout || serverErrors.layout }}</small
          >
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium" for="dwelling-unit-notes">{{
          t('property.residents.notes')
        }}</label>
        <Textarea
          id="dwelling-unit-notes"
          v-model="notes"
          class="w-full text-base"
          rows="3"
          :disabled="submitting"
        />
      </div>
    </form>
    <template #footer>
      <Button
        :label="t('button.cancel')"
        text
        severity="secondary"
        :disabled="submitting"
        @click="visible = false"
      />
      <Button
        :label="t('property.residents.create')"
        icon="pi pi-plus"
        :loading="submitting"
        :disabled="submitting"
        data-testid="dwelling-unit-create-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
