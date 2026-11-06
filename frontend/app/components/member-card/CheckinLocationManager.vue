<script setup lang="ts">
import type { CheckinLocation } from '~/types/member-card'

const props = defineProps<{
  teamId: string
}>()

const memberCardApi = useMemberCardApi()
const notification = useNotification()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()

const loadError = shallowRef<unknown>(null)
const locations = ref<CheckinLocation[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingLocation = ref<CheckinLocation | null>(null)

const form = ref({
  name: '',
  autoCompleteReservation: false,
})

async function loadLocations() {
  loading.value = true
  loadError.value = null
  try {
    locations.value = await memberCardApi.listLocations(props.teamId)
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingLocation.value = null
  form.value = { name: '', autoCompleteReservation: false }
  showDialog.value = true
}

function openEdit(location: CheckinLocation) {
  editingLocation.value = location
  form.value = { name: location.name, autoCompleteReservation: location.autoCompleteReservation }
  showDialog.value = true
}

async function save() {
  try {
    if (editingLocation.value) {
      await memberCardApi.updateLocation(props.teamId, editingLocation.value.id, form.value)
      notification.success(t('checkinLocationManager.updated'))
    } else {
      await memberCardApi.createLocation(props.teamId, form.value)
      notification.success(t('checkinLocationManager.created'))
    }
    showDialog.value = false
    await loadLocations()
  } catch (error) {
    handleApiError(error)
  }
}

async function deleteLocation(id: number) {
  try {
    await memberCardApi.deleteLocation(props.teamId, id)
    notification.success(t('checkinLocationManager.deleted'))
    await loadLocations()
  } catch (error) {
    handleApiError(error)
  }
}

onMounted(loadLocations)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">{{ t('checkinLocationManager.title') }}</h3>
      <Button
        :label="t('checkinLocationManager.add')"
        icon="pi pi-plus"
        size="small"
        class="min-h-11"
        @click="openCreate"
      />
    </div>

    <DashboardErrorState v-if="loadError" :error="loadError" @retry="loadLocations" />
    <DataTable v-else :value="locations" :loading="loading" data-key="id" striped-rows>
      <template #empty>
        <DashboardEmptyState :message="t('checkinLocationManager.empty')" />
      </template>
      <Column field="name" :header="t('checkinLocationManager.name')" />
      <Column :header="t('checkinLocationManager.status')">
        <template #body="{ data }">
          <Badge
            :value="
              t(data.isActive ? 'checkinLocationManager.active' : 'checkinLocationManager.inactive')
            "
            :severity="data.isActive ? 'success' : 'warn'"
          />
        </template>
      </Column>
      <Column :header="t('checkinLocationManager.autoComplete')">
        <template #body="{ data }">
          <i
            :class="
              data.autoCompleteReservation
                ? 'pi pi-check text-green-500'
                : 'pi pi-minus text-surface-400'
            "
          />
        </template>
      </Column>
      <Column :header="t('checkinLocationManager.actions')" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              icon="pi pi-pencil"
              class="min-h-11 min-w-11 shrink-0"
              :aria-label="t('checkinLocationManager.edit')"
              severity="secondary"
              size="small"
              text
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              class="min-h-11 min-w-11 shrink-0"
              :aria-label="t('checkinLocationManager.delete')"
              severity="danger"
              size="small"
              text
              @click="deleteLocation(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="t(editingLocation ? 'checkinLocationManager.edit' : 'checkinLocationManager.add')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            t('checkinLocationManager.name')
          }}</label>
          <InputText
            v-model="form.name"
            class="w-full"
            :placeholder="t('checkinLocationManager.placeholder')"
          />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.autoCompleteReservation" />
          <label class="text-sm">{{ t('checkinLocationManager.autoCompleteHint') }}</label>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('checkinLocationManager.cancel')"
          class="min-h-11"
          severity="secondary"
          @click="showDialog = false"
        />
        <Button
          :label="
            t(editingLocation ? 'checkinLocationManager.update' : 'checkinLocationManager.create')
          "
          icon="pi pi-check"
          class="min-h-11"
          :disabled="!form.name"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
