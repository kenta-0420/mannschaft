<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  CreateRecruitmentListingRequest,
  RecruitmentCategoryResponse,
  RecruitmentParticipationType,
  RecruitmentVisibility,
} from '~/types/recruitment'

interface Props {
  initial?: Partial<CreateRecruitmentListingRequest>
  categories: RecruitmentCategoryResponse[]
  submitLabel?: string
  loading?: boolean
}
const props = withDefaults(defineProps<Props>(), {
  initial: () => ({}),
  submitLabel: undefined,
  loading: false,
})

const emit = defineEmits<{
  submit: [value: CreateRecruitmentListingRequest]
}>()

const { t } = useI18n()

const categoryId = ref<number | null>(props.initial.categoryId ?? null)
const title = ref(props.initial.title ?? '')
const description = ref(props.initial.description ?? '')
const participationType = ref<RecruitmentParticipationType>(props.initial.participationType ?? 'INDIVIDUAL')
const startAt = ref(props.initial.startAt ?? '')
const endAt = ref(props.initial.endAt ?? '')
const applicationDeadline = ref(props.initial.applicationDeadline ?? '')
const autoCancelAt = ref(props.initial.autoCancelAt ?? '')
const capacity = ref<number | null>(props.initial.capacity ?? null)
const minCapacity = ref<number | null>(props.initial.minCapacity ?? null)
const paymentEnabled = ref<boolean>(props.initial.paymentEnabled ?? false)
const price = ref<number | null>(props.initial.price ?? null)
const visibility = ref<RecruitmentVisibility>(props.initial.visibility ?? 'SCOPE_ONLY')
const location = ref(props.initial.location ?? '')

const submitButtonLabel = computed(() => props.submitLabel ?? t('recruitment.action.create'))

function onSubmit() {
  if (!categoryId.value || !title.value || !startAt.value || !endAt.value
      || !applicationDeadline.value || !autoCancelAt.value
      || capacity.value == null || minCapacity.value == null) {
    return
  }
  emit('submit', {
    categoryId: categoryId.value,
    title: title.value,
    description: description.value || null,
    participationType: participationType.value,
    startAt: startAt.value,
    endAt: endAt.value,
    applicationDeadline: applicationDeadline.value,
    autoCancelAt: autoCancelAt.value,
    capacity: capacity.value,
    minCapacity: minCapacity.value,
    paymentEnabled: paymentEnabled.value,
    price: paymentEnabled.value ? price.value : null,
    visibility: visibility.value,
    location: location.value || null,
  })
}
</script>

<template>
  <form class="flex flex-col gap-4" @submit.prevent="onSubmit">
    <div class="flex flex-col gap-2">
      <label for="title">{{ t('recruitment.field.title') }}</label>
      <InputText id="title" v-model="title" required />
    </div>

    <div class="flex flex-col gap-2">
      <label for="category">{{ t('recruitment.field.category') }}</label>
      <Select
        id="category"
        v-model="categoryId"
        :options="categories"
        option-label="nameI18nKey"
        option-value="id"
        required
      >
        <template #option="{ option }">
          <span>{{ t(option.nameI18nKey) }}</span>
        </template>
        <template #value="{ value }">
          <span v-if="value">{{ t(categories.find((c) => c.id === value)?.nameI18nKey ?? '') }}</span>
        </template>
      </Select>
    </div>

    <div class="flex flex-col gap-2">
      <label>{{ t('recruitment.field.participationType') }}</label>
      <SelectButton
        v-model="participationType"
        :options="[
          { value: 'INDIVIDUAL', label: t('recruitment.participationType.individual') },
          { value: 'TEAM', label: t('recruitment.participationType.team') },
        ]"
        option-label="label"
        option-value="value"
      />
    </div>

    <div class="flex flex-col gap-2">
      <label for="description">{{ t('recruitment.field.description') }}</label>
      <Textarea id="description" v-model="description" rows="3" />
    </div>

    <div class="grid grid-cols-2 gap-3">
      <div class="flex flex-col gap-2">
        <label for="startAt">{{ t('recruitment.field.startAt') }}</label>
        <InputText id="startAt" v-model="startAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="endAt">{{ t('recruitment.field.endAt') }}</label>
        <InputText id="endAt" v-model="endAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="applicationDeadline">{{ t('recruitment.field.applicationDeadline') }}</label>
        <InputText id="applicationDeadline" v-model="applicationDeadline" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="autoCancelAt">{{ t('recruitment.field.autoCancelAt') }}</label>
        <InputText id="autoCancelAt" v-model="autoCancelAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="capacity">{{ t('recruitment.field.capacity') }}</label>
        <InputNumber id="capacity" v-model="capacity" :min="1" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="minCapacity">{{ t('recruitment.field.minCapacity') }}</label>
        <InputNumber id="minCapacity" v-model="minCapacity" :min="1" required />
      </div>
    </div>

    <div class="flex flex-col gap-2">
      <label for="location">{{ t('recruitment.field.location') }}</label>
      <InputText id="location" v-model="location" />
    </div>

    <div class="flex items-center gap-2">
      <Checkbox v-model="paymentEnabled" input-id="paymentEnabled" :binary="true" />
      <label for="paymentEnabled">{{ t('recruitment.field.paymentEnabled') }}</label>
    </div>

    <div v-if="paymentEnabled" class="flex flex-col gap-2">
      <label for="price">{{ t('recruitment.field.price') }}</label>
      <InputNumber id="price" v-model="price" :min="0" required />
    </div>

    <div class="flex flex-col gap-2">
      <label for="visibility">{{ t('recruitment.field.visibility') }}</label>
      <Select
        id="visibility"
        v-model="visibility"
        :options="[
          { value: 'SCOPE_ONLY', label: t('recruitment.visibility.scopeOnly') },
          { value: 'PUBLIC', label: t('recruitment.visibility.public') },
          { value: 'SUPPORTERS_ONLY', label: t('recruitment.visibility.supportersOnly') },
        ]"
        option-label="label"
        option-value="value"
      />
    </div>

    <Button type="submit" :label="submitButtonLabel" :loading="loading" icon="pi pi-check" />
  </form>
</template>
