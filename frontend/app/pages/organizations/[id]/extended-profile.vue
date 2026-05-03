<script setup lang="ts">
import type {
  OrganizationProfileResponse,
  OfficerResponse,
  CustomFieldResponse,
  ProfileVisibility,
  EstablishedDatePrecision,
} from '~/types/organization'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const api = useOrgExtendedProfileApi()
const notification = useNotification()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

// ----- プロフィール -----
const profileLoading = ref(true)
const profileForm = ref<{
  homepage_url: string
  established_date: string
  established_date_precision: EstablishedDatePrecision | null
  philosophy: string
}>({
  homepage_url: '',
  established_date: '',
  established_date_precision: null,
  philosophy: '',
})
const visibilityForm = ref<ProfileVisibility>({
  homepage_url: false,
  established_date: false,
  philosophy: false,
  officers: false,
  custom_fields: false,
})

const philosophyRemaining = computed(() => 2000 - (profileForm.value.philosophy?.length ?? 0))

const precisionOptions = computed(() => [
  { label: t('extended_profile.precision_year'), value: 'YEAR' as EstablishedDatePrecision },
  { label: t('extended_profile.precision_year_month'), value: 'YEAR_MONTH' as EstablishedDatePrecision },
  { label: t('extended_profile.precision_full'), value: 'FULL' as EstablishedDatePrecision },
])

function applyProfile(data: OrganizationProfileResponse) {
  profileForm.value.homepage_url = data.homepage_url ?? ''
  profileForm.value.established_date = data.established_date ?? ''
  profileForm.value.established_date_precision = data.established_date_precision ?? null
  profileForm.value.philosophy = data.philosophy ?? ''
  visibilityForm.value = {
    homepage_url: data.profile_visibility?.homepage_url ?? false,
    established_date: data.profile_visibility?.established_date ?? false,
    philosophy: data.profile_visibility?.philosophy ?? false,
    officers: data.profile_visibility?.officers ?? false,
    custom_fields: data.profile_visibility?.custom_fields ?? false,
  }
}

async function saveProfile() {
  try {
    const res = await api.updateProfile(orgId.value, {
      homepage_url: profileForm.value.homepage_url || null,
      established_date: profileForm.value.established_date || null,
      established_date_precision: profileForm.value.established_date_precision,
      philosophy: profileForm.value.philosophy || null,
      profile_visibility: visibilityForm.value,
    })
    applyProfile(res.data)
    notification.success(t('extended_profile.profile_saved'))
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

// ----- 役員 -----
const officers = ref<OfficerResponse[]>([])
const officerDragIndex = ref<number | null>(null)
const officerDropTarget = ref<number | null>(null)
const officerOrderDirty = ref(false)

const showOfficerDialog = ref(false)
const editingOfficer = ref<OfficerResponse | null>(null)
const officerForm = ref<{ name: string; title: string; is_visible: boolean }>({
  name: '',
  title: '',
  is_visible: true,
})

function openCreateOfficer() {
  editingOfficer.value = null
  officerForm.value = { name: '', title: '', is_visible: true }
  showOfficerDialog.value = true
}

function openEditOfficer(officer: OfficerResponse) {
  editingOfficer.value = officer
  officerForm.value = {
    name: officer.name,
    title: officer.title,
    is_visible: officer.is_visible,
  }
  showOfficerDialog.value = true
}

async function saveOfficer() {
  try {
    if (editingOfficer.value) {
      const res = await api.updateOfficer(orgId.value, editingOfficer.value.id, officerForm.value)
      const idx = officers.value.findIndex((o) => o.id === editingOfficer.value!.id)
      if (idx !== -1) officers.value[idx] = res.data
      notification.success(t('extended_profile.officer_updated'))
    } else {
      const res = await api.createOfficer(orgId.value, officerForm.value)
      officers.value.push(res.data)
      notification.success(t('extended_profile.officer_added'))
    }
    showOfficerDialog.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

async function deleteOfficer(officer: OfficerResponse) {
  try {
    await api.deleteOfficer(orgId.value, officer.id)
    officers.value = officers.value.filter((o) => o.id !== officer.id)
    notification.success(t('extended_profile.officer_deleted'))
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

function onOfficerDragStart(index: number, e: DragEvent) {
  officerDragIndex.value = index
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onOfficerDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  officerDropTarget.value = index
}

function onOfficerDrop(index: number) {
  if (officerDragIndex.value !== null && officerDragIndex.value !== index) {
    const arr = [...officers.value]
    const [moved] = arr.splice(officerDragIndex.value, 1)
    if (moved) arr.splice(index, 0, moved)
    officers.value = arr
    officerOrderDirty.value = true
  }
  officerDragIndex.value = null
  officerDropTarget.value = null
}

function onOfficerDragEnd() {
  officerDragIndex.value = null
  officerDropTarget.value = null
}

async function saveOfficerOrder() {
  try {
    const orders = officers.value.map((o, idx) => ({ id: o.id, displayOrder: idx + 1 }))
    await api.reorderOfficers(orgId.value, { orders })
    officers.value = officers.value.map((o, idx) => ({ ...o, display_order: idx + 1 }))
    notification.success(t('extended_profile.order_saved'))
    officerOrderDirty.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

// ----- カスタムフィールド -----
const customFields = ref<CustomFieldResponse[]>([])
const cfDragIndex = ref<number | null>(null)
const cfDropTarget = ref<number | null>(null)
const cfOrderDirty = ref(false)

const showCfDialog = ref(false)
const editingCf = ref<CustomFieldResponse | null>(null)
const cfForm = ref<{ label: string; value: string; is_visible: boolean }>({
  label: '',
  value: '',
  is_visible: true,
})

function openCreateCf() {
  editingCf.value = null
  cfForm.value = { label: '', value: '', is_visible: true }
  showCfDialog.value = true
}

function openEditCf(field: CustomFieldResponse) {
  editingCf.value = field
  cfForm.value = {
    label: field.label,
    value: field.value,
    is_visible: field.is_visible,
  }
  showCfDialog.value = true
}

async function saveCf() {
  try {
    if (editingCf.value) {
      const res = await api.updateCustomField(orgId.value, editingCf.value.id, cfForm.value)
      const idx = customFields.value.findIndex((f) => f.id === editingCf.value!.id)
      if (idx !== -1) customFields.value[idx] = res.data
      notification.success(t('extended_profile.custom_field_updated'))
    } else {
      const res = await api.createCustomField(orgId.value, cfForm.value)
      customFields.value.push(res.data)
      notification.success(t('extended_profile.custom_field_added'))
    }
    showCfDialog.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

async function deleteCf(field: CustomFieldResponse) {
  try {
    await api.deleteCustomField(orgId.value, field.id)
    customFields.value = customFields.value.filter((f) => f.id !== field.id)
    notification.success(t('extended_profile.custom_field_deleted'))
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

function onCfDragStart(index: number, e: DragEvent) {
  cfDragIndex.value = index
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onCfDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  cfDropTarget.value = index
}

function onCfDrop(index: number) {
  if (cfDragIndex.value !== null && cfDragIndex.value !== index) {
    const arr = [...customFields.value]
    const [moved] = arr.splice(cfDragIndex.value, 1)
    if (moved) arr.splice(index, 0, moved)
    customFields.value = arr
    cfOrderDirty.value = true
  }
  cfDragIndex.value = null
  cfDropTarget.value = null
}

function onCfDragEnd() {
  cfDragIndex.value = null
  cfDropTarget.value = null
}

async function saveCfOrder() {
  try {
    const orders = customFields.value.map((f, idx) => ({ id: f.id, displayOrder: idx + 1 }))
    await api.reorderCustomFields(orgId.value, { orders })
    customFields.value = customFields.value.map((f, idx) => ({ ...f, display_order: idx + 1 }))
    notification.success(t('extended_profile.order_saved'))
    cfOrderDirty.value = false
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  }
}

// ----- 初期ロード -----
async function loadData() {
  profileLoading.value = true
  try {
    await loadPermissions()
    const [profileRes, officersRes, fieldsRes] = await Promise.all([
      api.getProfile(orgId.value),
      api.getOfficers(orgId.value, true),
      api.getCustomFields(orgId.value, true),
    ])
    applyProfile(profileRes.data)
    officers.value = officersRes.data.sort((a, b) => a.display_order - b.display_order)
    customFields.value = fieldsRes.data.sort((a, b) => a.display_order - b.display_order)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    notification.error(t('dialog.error') + ': ' + msg)
  } finally {
    profileLoading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-6 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="$t('extended_profile.title')" />
    </div>

    <PageLoading v-if="profileLoading" />

    <template v-else-if="isAdminOrDeputy">
      <!-- 基本プロフィール -->
      <section class="mb-8 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900">
        <h2 class="mb-4 text-lg font-semibold text-surface-700 dark:text-surface-200">
          {{ $t('extended_profile.section_profile') }}
        </h2>
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.homepage_url') }}</label>
            <InputText
              v-model="profileForm.homepage_url"
              class="w-full"
              placeholder="https://..."
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.established_date') }}</label>
            <InputText
              v-model="profileForm.established_date"
              class="w-full"
              placeholder="YYYY-MM-DD"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.established_date_precision') }}</label>
            <Select
              v-model="profileForm.established_date_precision"
              :options="precisionOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.philosophy') }}</label>
            <Textarea
              v-model="profileForm.philosophy"
              class="w-full"
              :rows="6"
              :maxlength="2000"
            />
            <p class="mt-1 text-right text-xs text-surface-400">
              {{ philosophyRemaining }} / 2000
            </p>
          </div>
          <div class="flex justify-end">
            <Button :label="$t('extended_profile.save_profile')" icon="pi pi-check" @click="saveProfile" />
          </div>
        </div>
      </section>

      <!-- 公開設定 -->
      <section class="mb-8 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900">
        <h2 class="mb-4 text-lg font-semibold text-surface-700 dark:text-surface-200">
          {{ $t('extended_profile.section_visibility') }}
        </h2>
        <div class="space-y-3">
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('extended_profile.visibility_homepage_url') }}</label>
            <ToggleSwitch v-model="visibilityForm.homepage_url" />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('extended_profile.visibility_established_date') }}</label>
            <ToggleSwitch v-model="visibilityForm.established_date" />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('extended_profile.visibility_philosophy') }}</label>
            <ToggleSwitch v-model="visibilityForm.philosophy" />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('extended_profile.visibility_officers') }}</label>
            <ToggleSwitch v-model="visibilityForm.officers" />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('extended_profile.visibility_custom_fields') }}</label>
            <ToggleSwitch v-model="visibilityForm.custom_fields" />
          </div>
          <div class="flex justify-end pt-2">
            <Button :label="$t('extended_profile.save_profile')" icon="pi pi-check" @click="saveProfile" />
          </div>
        </div>
      </section>

      <!-- 役員セクション -->
      <section class="mb-8 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900">
        <h2 class="mb-4 text-lg font-semibold text-surface-700 dark:text-surface-200">
          {{ $t('extended_profile.section_officers') }}
        </h2>

        <div class="mb-4 space-y-2">
          <div
            v-for="(officer, index) in officers"
            :key="officer.id"
            class="flex cursor-grab items-center gap-3 rounded-lg border border-surface-200 p-3 transition-colors dark:border-surface-700"
            :class="{
              'bg-primary/5': officerDropTarget === index && officerDragIndex !== index,
              'opacity-50': officerDragIndex === index,
            }"
            draggable="true"
            @dragstart="onOfficerDragStart(index, $event)"
            @dragover="onOfficerDragOver(index, $event)"
            @drop.prevent="onOfficerDrop(index)"
            @dragend="onOfficerDragEnd"
          >
            <i class="pi pi-bars cursor-grab text-surface-400 active:cursor-grabbing" />
            <div class="flex-1">
              <span class="font-medium">{{ officer.name }}</span>
              <span class="ml-2 text-sm text-surface-500">{{ officer.title }}</span>
            </div>
            <Tag
              :value="officer.is_visible ? $t('extended_profile.officer_visible') : $t('button.close')"
              :severity="officer.is_visible ? 'success' : 'secondary'"
            />
            <Button
              icon="pi pi-pencil"
              size="small"
              text
              severity="secondary"
              @click="openEditOfficer(officer)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              text
              severity="danger"
              @click="deleteOfficer(officer)"
            />
          </div>
        </div>

        <div class="flex gap-2">
          <Button
            :label="$t('extended_profile.add_officer')"
            icon="pi pi-plus"
            severity="secondary"
            @click="openCreateOfficer"
          />
          <Button
            :label="$t('extended_profile.save_order')"
            icon="pi pi-sort"
            :disabled="!officerOrderDirty"
            @click="saveOfficerOrder"
          />
        </div>
      </section>

      <!-- カスタムフィールドセクション -->
      <section class="mb-8 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900">
        <h2 class="mb-4 text-lg font-semibold text-surface-700 dark:text-surface-200">
          {{ $t('extended_profile.section_custom_fields') }}
        </h2>

        <div class="mb-4 space-y-2">
          <div
            v-for="(field, index) in customFields"
            :key="field.id"
            class="flex cursor-grab items-center gap-3 rounded-lg border border-surface-200 p-3 transition-colors dark:border-surface-700"
            :class="{
              'bg-primary/5': cfDropTarget === index && cfDragIndex !== index,
              'opacity-50': cfDragIndex === index,
            }"
            draggable="true"
            @dragstart="onCfDragStart(index, $event)"
            @dragover="onCfDragOver(index, $event)"
            @drop.prevent="onCfDrop(index)"
            @dragend="onCfDragEnd"
          >
            <i class="pi pi-bars cursor-grab text-surface-400 active:cursor-grabbing" />
            <div class="flex-1">
              <span class="font-medium">{{ field.label }}</span>
              <span class="ml-2 text-sm text-surface-500">{{ field.value }}</span>
            </div>
            <Tag
              :value="field.is_visible ? $t('extended_profile.officer_visible') : $t('button.close')"
              :severity="field.is_visible ? 'success' : 'secondary'"
            />
            <Button
              icon="pi pi-pencil"
              size="small"
              text
              severity="secondary"
              @click="openEditCf(field)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              text
              severity="danger"
              @click="deleteCf(field)"
            />
          </div>
        </div>

        <div class="flex gap-2">
          <Button
            :label="$t('extended_profile.add_custom_field')"
            icon="pi pi-plus"
            severity="secondary"
            @click="openCreateCf"
          />
          <Button
            :label="$t('extended_profile.save_order')"
            icon="pi pi-sort"
            :disabled="!cfOrderDirty"
            @click="saveCfOrder"
          />
        </div>
      </section>
    </template>

    <!-- 役員ダイアログ -->
    <Dialog
      v-model:visible="showOfficerDialog"
      :header="editingOfficer ? $t('extended_profile.edit_officer') : $t('extended_profile.add_officer')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.officer_name') }}</label>
          <InputText v-model="officerForm.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.officer_title') }}</label>
          <InputText v-model="officerForm.title" class="w-full" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ $t('extended_profile.officer_visible') }}</label>
          <ToggleSwitch v-model="officerForm.is_visible" />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" severity="secondary" @click="showOfficerDialog = false" />
        <Button
          :label="editingOfficer ? $t('button.save') : $t('button.create')"
          icon="pi pi-check"
          :disabled="!officerForm.name || !officerForm.title"
          @click="saveOfficer"
        />
      </template>
    </Dialog>

    <!-- カスタムフィールドダイアログ -->
    <Dialog
      v-model:visible="showCfDialog"
      :header="editingCf ? $t('extended_profile.edit_custom_field') : $t('extended_profile.add_custom_field')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.field_label') }}</label>
          <InputText v-model="cfForm.label" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('extended_profile.field_value') }}</label>
          <InputText v-model="cfForm.value" class="w-full" />
        </div>
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ $t('extended_profile.officer_visible') }}</label>
          <ToggleSwitch v-model="cfForm.is_visible" />
        </div>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" severity="secondary" @click="showCfDialog = false" />
        <Button
          :label="editingCf ? $t('button.save') : $t('button.create')"
          icon="pi pi-check"
          :disabled="!cfForm.label || !cfForm.value"
          @click="saveCf"
        />
      </template>
    </Dialog>
  </div>
</template>
