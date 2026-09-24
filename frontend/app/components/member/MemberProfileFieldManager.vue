<script setup lang="ts">
import type { FieldType, MemberProfileField } from '~/types/member-profile'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const { t } = useI18n()
const notification = useNotification()
const { listFields, createField } = useMemberProfileApi()

const fields = ref<MemberProfileField[]>([])
const loading = ref(true)
const loadError = ref(false)
const showDialog = ref(false)
const saving = ref(false)
const form = ref({ fieldName: '', fieldType: 'TEXT' as FieldType, isRequired: false, options: '' })

const fieldTypes = computed(() => [
  { label: t('memberProfile.fields.typeText'), value: 'TEXT' },
  { label: t('memberProfile.fields.typeNumber'), value: 'NUMBER' },
  { label: t('memberProfile.fields.typeDate'), value: 'DATE' },
  { label: t('memberProfile.fields.typeSelect'), value: 'SELECT' },
])

const selectOptions = computed(() => form.value.options.split('\n').map((option) => option.trim()).filter(Boolean))
const canSave = computed(() => form.value.fieldName.trim().length > 0
  && (form.value.fieldType !== 'SELECT' || selectOptions.value.length > 0))

function fieldTypeLabel(type: FieldType) {
  return fieldTypes.value.find((item) => item.value === type)?.label ?? type
}

async function loadFields() {
  loading.value = true
  loadError.value = false
  try {
    fields.value = await listFields(
      props.scopeType === 'team' ? props.scopeId : undefined,
      props.scopeType === 'organization' ? props.scopeId : undefined,
    )
  } catch {
    loadError.value = true
    notification.error(t('memberProfile.fields.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { fieldName: '', fieldType: 'TEXT', isRequired: false, options: '' }
  showDialog.value = true
}

async function save() {
  if (!canSave.value || saving.value) return
  saving.value = true
  try {
    await createField({
      teamId: props.scopeType === 'team' ? props.scopeId : undefined,
      organizationId: props.scopeType === 'organization' ? props.scopeId : undefined,
      fieldName: form.value.fieldName.trim(),
      fieldType: form.value.fieldType,
      isRequired: form.value.isRequired,
      options: form.value.fieldType === 'SELECT' ? JSON.stringify(selectOptions.value) : undefined,
    })
    notification.success(t('memberProfile.fields.createSuccess'))
    showDialog.value = false
    await loadFields()
  } catch {
    notification.error(t('memberProfile.fields.createFailed'))
  } finally {
    saving.value = false
  }
}

watch(() => [props.scopeType, props.scopeId], loadFields)
onMounted(loadFields)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-3">
      <h2 class="text-xl font-semibold">{{ t('memberProfile.fields.title') }}</h2>
      <Button :label="t('memberProfile.fields.add')" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />
    <div v-else-if="loadError" class="py-8 text-center">
      <p class="mb-3">{{ t('memberProfile.fields.loadFailed') }}</p>
      <Button :label="t('memberProfile.fields.retry')" @click="loadFields" />
    </div>
    <DataTable v-else :value="fields" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-id-card" :message="t('memberProfile.fields.empty')" />
      </template>
      <Column field="fieldName" :header="t('memberProfile.fields.name')" />
      <Column :header="t('memberProfile.fields.type')">
        <template #body="{ data }">
          <Tag :value="fieldTypeLabel(data.fieldType)" severity="info" />
        </template>
      </Column>
      <Column :header="t('memberProfile.fields.required')">
        <template #body="{ data }">{{ data.isRequired ? t('memberProfile.fields.yes') : t('memberProfile.fields.no') }}</template>
      </Column>
      <Column :header="t('memberProfile.fields.active')">
        <template #body="{ data }">{{ data.isActive ? t('memberProfile.fields.yes') : t('memberProfile.fields.no') }}</template>
      </Column>
      <Column field="sortOrder" :header="t('memberProfile.fields.order')" />
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="t('memberProfile.fields.add')" :style="{ width: '440px' }" modal>
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium" for="member-field-name">{{ t('memberProfile.fields.name') }}</label>
          <InputText id="member-field-name" v-model="form.fieldName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="member-field-type">{{ t('memberProfile.fields.type') }}</label>
          <Select id="member-field-type" v-model="form.fieldType" :options="fieldTypes" option-label="label" option-value="value" class="w-full" />
        </div>
        <div v-if="form.fieldType === 'SELECT'">
          <label class="mb-1 block text-sm font-medium" for="member-field-options">{{ t('memberProfile.fields.options') }}</label>
          <Textarea id="member-field-options" v-model="form.options" class="w-full" rows="4" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isRequired" :binary="true" input-id="member-field-required" />
          <label for="member-field-required">{{ t('memberProfile.fields.required') }}</label>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" text @click="showDialog = false" />
        <Button :label="t('memberProfile.fields.add')" :loading="saving" :disabled="!canSave" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
