<script setup lang="ts">
import type {
  CreatePersonalTimetableInput,
  PersonalPeriodTemplateKind,
  PersonalTimetable,
} from '~/types/personal-timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const router = useRouter()
const api = useMyPersonalTimetableApi()
const { success, error } = useNotification()

const items = ref<PersonalTimetable[]>([])
const loading = ref(true)

const showCreateDialog = ref(false)
const createForm = ref<CreatePersonalTimetableInput>({
  name: '',
  effective_from: new Date().toISOString().slice(0, 10),
  effective_until: null,
  visibility: 'PRIVATE',
  week_pattern_enabled: false,
  init_period_template: 'CUSTOM',
})
const submitting = ref(false)

const TEMPLATE_OPTIONS: { value: PersonalPeriodTemplateKind; labelKey: string }[] = [
  { value: 'CUSTOM', labelKey: 'personalTimetable.template_custom' },
  { value: 'ELEMENTARY', labelKey: 'personalTimetable.template_elementary' },
  { value: 'JUNIOR_HIGH', labelKey: 'personalTimetable.template_junior_high' },
  { value: 'HIGH_SCHOOL', labelKey: 'personalTimetable.template_high_school' },
  { value: 'UNIV_90MIN', labelKey: 'personalTimetable.template_univ_90min' },
  { value: 'UNIV_100MIN', labelKey: 'personalTimetable.template_univ_100min' },
]

async function load() {
  loading.value = true
  try {
    items.value = await api.list()
  }
  catch (e) {
    error(t('personalTimetable.load_error'), String(e))
    items.value = []
  }
  finally {
    loading.value = false
  }
}

async function submitCreate() {
  if (!createForm.value.name || !createForm.value.effective_from) return
  submitting.value = true
  try {
    const created = await api.create(createForm.value)
    success(t('personalTimetable.create_success'))
    showCreateDialog.value = false
    await router.push(`/me/personal-timetable/${created.id}`)
  }
  catch (e) {
    error(t('personalTimetable.create_error'), String(e))
  }
  finally {
    submitting.value = false
  }
}

async function onActivate(id: number) {
  try {
    await api.activate(id)
    success(t('personalTimetable.activate_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.activate_error'), String(e))
  }
}

async function onArchive(id: number) {
  try {
    await api.archive(id)
    success(t('personalTimetable.archive_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.archive_error'), String(e))
  }
}

async function onRevert(id: number) {
  try {
    await api.revertToDraft(id)
    success(t('personalTimetable.revert_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.revert_error'), String(e))
  }
}

async function onDuplicate(id: number) {
  try {
    await api.duplicate(id)
    success(t('personalTimetable.duplicate_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.duplicate_error'), String(e))
  }
}

async function onDelete(id: number) {
  if (!window.confirm(t('personalTimetable.delete_confirm'))) return
  try {
    await api.remove(id)
    success(t('personalTimetable.delete_success'))
    await load()
  }
  catch (e) {
    error(t('personalTimetable.delete_error'), String(e))
  }
}

function statusSeverity(s: string) {
  return ({ DRAFT: 'warning', ACTIVE: 'success', ARCHIVED: 'secondary' } as const)[
    s as 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  ] ?? 'info'
}

function statusLabel(s: string) {
  return (
    {
      DRAFT: t('personalTimetable.status_draft'),
      ACTIVE: t('personalTimetable.status_active'),
      ARCHIVED: t('personalTimetable.status_archived'),
    }[s] ?? s
  )
}

onMounted(load)
</script>

<template>
  <div class="p-4 md:p-6 max-w-5xl mx-auto">
    <header class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold">
          {{ t('personalTimetable.page_title') }}
        </h1>
        <p class="text-sm text-gray-500 mt-1">
          {{ t('personalTimetable.page_subtitle') }}
        </p>
      </div>
      <Button
        :label="t('personalTimetable.btn_new')"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </header>

    <div v-if="loading" class="text-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="items.length === 0" class="text-center py-12 text-gray-500">
      {{ t('personalTimetable.list_empty') }}
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <Card v-for="t_ in items" :key="t_.id" class="cursor-pointer">
        <template #title>
          <div class="flex items-center justify-between">
            <NuxtLink :to="`/me/personal-timetable/${t_.id}`" class="hover:underline">
              {{ t_.name }}
            </NuxtLink>
            <Tag :severity="statusSeverity(t_.status)" :value="statusLabel(t_.status)" />
          </div>
        </template>
        <template #subtitle>
          <span class="text-xs text-gray-500">
            {{ t_.effective_from }} 〜 {{ t_.effective_until || '—' }}
          </span>
        </template>
        <template #content>
          <div v-if="t_.term_label" class="text-sm">{{ t_.term_label }}</div>
          <div v-if="t_.notes" class="text-xs text-gray-600 mt-1">{{ t_.notes }}</div>
        </template>
        <template #footer>
          <div class="flex flex-wrap gap-2">
            <Button
              v-if="t_.status === 'DRAFT'"
              :label="t('personalTimetable.btn_activate')"
              icon="pi pi-check"
              size="small"
              severity="success"
              @click="onActivate(t_.id)"
            />
            <Button
              v-if="t_.status === 'ACTIVE'"
              :label="t('personalTimetable.btn_archive')"
              icon="pi pi-inbox"
              size="small"
              severity="secondary"
              @click="onArchive(t_.id)"
            />
            <Button
              v-if="t_.status === 'ARCHIVED'"
              :label="t('personalTimetable.btn_revert_to_draft')"
              icon="pi pi-undo"
              size="small"
              severity="secondary"
              @click="onRevert(t_.id)"
            />
            <Button
              :label="t('personalTimetable.btn_duplicate')"
              icon="pi pi-copy"
              size="small"
              severity="secondary"
              outlined
              @click="onDuplicate(t_.id)"
            />
            <Button
              :label="t('personalTimetable.btn_delete')"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              outlined
              @click="onDelete(t_.id)"
            />
          </div>
        </template>
      </Card>
    </div>

    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('personalTimetable.btn_new')"
      modal
      :style="{ width: '480px' }"
    >
      <div class="space-y-3">
        <div>
          <label class="block text-sm mb-1">{{ t('personalTimetable.field_name') }}</label>
          <InputText v-model="createForm.name" class="w-full" />
        </div>
        <div>
          <label class="block text-sm mb-1">{{ t('personalTimetable.field_effective_from') }}</label>
          <InputText v-model="createForm.effective_from" type="date" class="w-full" />
        </div>
        <div>
          <label class="block text-sm mb-1">{{ t('personalTimetable.field_effective_until') }}</label>
          <InputText v-model="createForm.effective_until" type="date" class="w-full" />
        </div>
        <div>
          <label class="block text-sm mb-1">{{ t('personalTimetable.field_term_label') }}</label>
          <InputText v-model="createForm.term_label" class="w-full" />
        </div>
        <div>
          <label class="block text-sm mb-1">{{ t('personalTimetable.field_init_template') }}</label>
          <Select
            v-model="createForm.init_period_template"
            :options="TEMPLATE_OPTIONS"
            option-label="labelKey"
            option-value="value"
            class="w-full"
          >
            <template #value="{ value }">
              {{ t(TEMPLATE_OPTIONS.find((o) => o.value === value)?.labelKey ?? '') }}
            </template>
            <template #option="{ option }">
              {{ t(option.labelKey) }}
            </template>
          </Select>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('personalTimetable.btn_cancel')"
          severity="secondary"
          text
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('personalTimetable.btn_create')"
          :loading="submitting"
          @click="submitCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
