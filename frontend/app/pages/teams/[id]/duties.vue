<script setup lang="ts">
import type { DutyRotationResponse, DutyRotationRequest, DutyTodayResponse } from '~/types/duty'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const { listDuties, createDuty, updateDuty, deleteDuty, getTodayDuties } = useDutyApi()
const { showError } = useNotification()

const duties = ref<DutyRotationResponse[]>([])
const todayDuties = ref<DutyTodayResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingDuty = ref<DutyRotationResponse | null>(null)

const form = reactive<DutyRotationRequest>({
  dutyName: '',
  rotationType: 'DAILY',
  memberOrder: [],
  startDate: new Date().toISOString().split('T')[0] ?? '',
  icon: '📋',
  isEnabled: true,
})

const rotationTypes = [
  { label: '日替わり', value: 'DAILY' },
  { label: '週替わり', value: 'WEEKLY' },
  { label: '月替わり', value: 'MONTHLY' },
]

async function load() {
  loading.value = true
  try {
    const [dutiesRes, todayRes] = await Promise.all([listDuties(teamId), getTodayDuties(teamId)])
    duties.value = dutiesRes.data
    todayDuties.value = todayRes.data
  } catch {
    showError('当番情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingDuty.value = null
  Object.assign(form, {
    dutyName: '',
    rotationType: 'DAILY',
    memberOrder: [],
    startDate: new Date().toISOString().split('T')[0] ?? '',
    icon: '📋',
    isEnabled: true,
  })
  showDialog.value = true
}

function openEdit(duty: DutyRotationResponse) {
  editingDuty.value = duty
  Object.assign(form, {
    dutyName: duty.dutyName,
    rotationType: duty.rotationType,
    memberOrder: [...duty.memberOrder],
    startDate: duty.startDate,
    icon: duty.icon,
    isEnabled: duty.enabled,
  })
  showDialog.value = true
}

async function save() {
  try {
    if (editingDuty.value) {
      await updateDuty(teamId, editingDuty.value.id, form)
    } else {
      await createDuty(teamId, form)
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function remove(duty: DutyRotationResponse) {
  if (!confirm(`「${duty.dutyName}」を削除しますか？`)) return
  try {
    await deleteDuty(teamId, duty.id)
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(async () => {
  await loadPermissions()
  await load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="当番管理" />
      <Button v-if="isAdminOrDeputy" label="当番を追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <!-- 今日の当番 -->
    <div v-if="todayDuties.length > 0" class="mb-6">
      <h2 class="mb-2 text-lg font-semibold">今日の当番</h2>
      <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <SectionCard
          v-for="td in todayDuties"
          :key="td.dutyId"
          class="flex items-center gap-3"
        >
          <span class="text-2xl">{{ td.icon }}</span>
          <div>
            <p class="font-semibold">{{ td.dutyName }}</p>
            <p class="text-sm text-surface-500">担当者ID: {{ td.assigneeUserId }}</p>
          </div>
        </SectionCard>
      </div>
    </div>

    <!-- 当番一覧 -->
    <div v-if="!loading" class="flex flex-col gap-3">
      <SectionCard
        v-for="duty in duties"
        :key="duty.id"
      >
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-2xl">{{ duty.icon }}</span>
            <div>
              <p class="font-semibold">{{ duty.dutyName }}</p>
              <div class="flex items-center gap-2 text-sm text-surface-500">
                <Tag :value="duty.rotationType" severity="info" />
                <span>開始日: {{ duty.startDate }}</span>
                <Tag v-if="!duty.enabled" value="無効" severity="warn" />
              </div>
            </div>
          </div>
          <div v-if="isAdminOrDeputy" class="flex gap-2">
            <Button icon="pi pi-pencil" text rounded @click="openEdit(duty)" />
            <Button icon="pi pi-trash" text rounded severity="danger" @click="remove(duty)" />
          </div>
        </div>
      </SectionCard>

      <DashboardEmptyState
        v-if="duties.length === 0 && !loading"
        icon="pi pi-calendar-clock"
        message="当番が登録されていません"
      />
    </div>

    <!-- 作成/編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingDuty ? '当番を編集' : '当番を追加'"
      modal
      class="w-full max-w-lg"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">当番名</label>
          <InputText v-model="form.dutyName" class="w-full" placeholder="例: ゴミ出し" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">ローテーション種別</label>
          <Select
            v-model="form.rotationType"
            :options="rotationTypes"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">開始日</label>
          <InputText v-model="form.startDate" type="date" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">アイコン</label>
          <InputText v-model="form.icon" class="w-20" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isEnabled" :binary="true" input-id="dutyEnabled" />
          <label for="dutyEnabled" class="text-sm">有効</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button :label="editingDuty ? '更新' : '作成'" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
