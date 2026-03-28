<script setup lang="ts">
import type { CheckinLocation } from '~/types/member-card'

const props = defineProps<{
  teamId: number
}>()

const memberCardApi = useMemberCardApi()
const notification = useNotification()

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
  try {
    locations.value = await memberCardApi.listLocations(props.teamId)
  } catch {
    notification.error('拠点一覧の取得に失敗しました')
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
      notification.success('拠点を更新しました')
    } else {
      await memberCardApi.createLocation(props.teamId, form.value)
      notification.success('拠点を作成しました')
    }
    showDialog.value = false
    await loadLocations()
  } catch {
    notification.error('拠点の保存に失敗しました')
  }
}

async function deleteLocation(id: number) {
  try {
    await memberCardApi.deleteLocation(props.teamId, id)
    notification.success('拠点を削除しました')
    await loadLocations()
  } catch {
    notification.error('拠点の削除に失敗しました')
  }
}

onMounted(loadLocations)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">セルフチェックイン拠点</h3>
      <Button label="拠点を追加" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>

    <DataTable :value="locations" :loading="loading" data-key="id" striped-rows>
      <template #empty>
        <div class="py-8 text-center text-surface-500">拠点が登録されていません</div>
      </template>
      <Column field="name" header="拠点名" />
      <Column header="ステータス">
        <template #body="{ data }">
          <Badge :value="data.isActive ? '有効' : '無効'" :severity="data.isActive ? 'success' : 'warn'" />
        </template>
      </Column>
      <Column header="予約自動完了">
        <template #body="{ data }">
          <i :class="data.autoCompleteReservation ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-400'" />
        </template>
      </Column>
      <Column header="操作" style="width: 120px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button icon="pi pi-pencil" severity="secondary" size="small" text @click="openEdit(data)" />
            <Button icon="pi pi-trash" severity="danger" size="small" text @click="deleteLocation(data.id)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showDialog" :header="editingLocation ? '拠点を編集' : '拠点を追加'" :modal="true" class="w-full max-w-md">
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">拠点名</label>
          <InputText v-model="form.name" class="w-full" placeholder="例: 本店受付" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.autoCompleteReservation" />
          <label class="text-sm">チェックイン時に予約を自動完了</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
        <Button :label="editingLocation ? '更新' : '作成'" icon="pi pi-check" :disabled="!form.name" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
