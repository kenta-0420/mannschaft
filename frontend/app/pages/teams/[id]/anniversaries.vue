<script setup lang="ts">
import type { AnniversaryResponse, AnniversaryRequest } from '~/types/anniversary'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const anniversaryApi = useAnniversaryApi()
const { showError } = useNotification()

const anniversaries = ref<AnniversaryResponse[]>([])
const upcoming = ref<AnniversaryResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<AnniversaryResponse | null>(null)

const form = reactive<AnniversaryRequest>({
  name: '',
  date: new Date().toISOString().split('T')[0],
  repeatAnnually: true,
  notifyDaysBefore: 7,
})

async function load() {
  loading.value = true
  try {
    const [allRes, upcomingRes] = await Promise.all([
      anniversaryApi.listAnniversaries(teamId),
      anniversaryApi.getUpcoming(teamId),
    ])
    anniversaries.value = allRes.data
    upcoming.value = upcomingRes.data
  } catch {
    showError('記念日情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  Object.assign(form, {
    name: '',
    date: new Date().toISOString().split('T')[0],
    repeatAnnually: true,
    notifyDaysBefore: 7,
  })
  showDialog.value = true
}

function openEdit(item: AnniversaryResponse) {
  editingItem.value = item
  Object.assign(form, {
    name: item.name,
    date: item.date,
    repeatAnnually: item.repeatAnnually,
    notifyDaysBefore: item.notifyDaysBefore ?? 7,
  })
  showDialog.value = true
}

async function save() {
  try {
    if (editingItem.value) {
      await anniversaryApi.updateAnniversary(teamId, editingItem.value.id, form)
    } else {
      await anniversaryApi.createAnniversary(teamId, form)
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function remove(item: AnniversaryResponse) {
  if (!confirm(`「${item.name}」を削除しますか？`)) return
  try {
    await anniversaryApi.deleteAnniversary(teamId, item.id)
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

function daysUntil(dateStr: string): number {
  const today = new Date()
  const target = new Date(dateStr)
  target.setFullYear(today.getFullYear())
  if (target < today) target.setFullYear(today.getFullYear() + 1)
  return Math.ceil((target.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
}

onMounted(async () => {
  await loadPermissions()
  await load()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="記念日" />
      <Button v-if="isAdminOrDeputy" label="記念日を追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <!-- 近日の記念日 -->
    <div v-if="upcoming.length > 0" class="mb-6">
      <h2 class="mb-2 text-lg font-semibold">近日の記念日</h2>
      <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="item in upcoming"
          :key="'up-' + item.id"
          class="rounded-xl border border-primary/30 bg-primary/5 p-4"
        >
          <div class="flex items-center justify-between">
            <p class="font-semibold">{{ item.name }}</p>
            <Tag :value="`あと${daysUntil(item.date)}日`" severity="warn" />
          </div>
          <p class="mt-1 text-sm text-surface-500">{{ item.date }}</p>
        </div>
      </div>
    </div>

    <!-- 全記念日一覧 -->
    <div v-if="!loading" class="flex flex-col gap-2">
      <SectionCard
        v-for="item in anniversaries"
        :key="item.id"
      >
        <div class="flex items-center justify-between">
          <div>
            <p class="font-semibold">{{ item.name }}</p>
            <div class="flex items-center gap-2 text-sm text-surface-500">
              <span>{{ item.date }}</span>
              <Tag v-if="item.repeatAnnually" value="毎年" severity="info" class="text-xs" />
              <span v-if="item.notifyDaysBefore">{{ item.notifyDaysBefore }}日前に通知</span>
            </div>
          </div>
          <div v-if="isAdminOrDeputy" class="flex gap-2">
            <Button icon="pi pi-pencil" text rounded @click="openEdit(item)" />
            <Button icon="pi pi-trash" text rounded severity="danger" @click="remove(item)" />
          </div>
        </div>
      </SectionCard>
      <DashboardEmptyState
        v-if="anniversaries.length === 0 && !loading"
        icon="pi pi-gift"
        message="記念日が登録されていません"
      />
    </div>

    <!-- 作成/編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? '記念日を編集' : '記念日を追加'"
      modal
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">名前</label>
          <InputText v-model="form.name" class="w-full" placeholder="例: 創立記念日" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">日付</label>
          <InputText v-model="form.date" type="date" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.repeatAnnually" :binary="true" input-id="repeatAnnually" />
          <label for="repeatAnnually" class="text-sm">毎年繰り返す</label>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">通知（日前）</label>
          <InputNumber v-model="form.notifyDaysBefore" :min="0" :max="90" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showDialog = false" />
        <Button :label="editingItem ? '更新' : '作成'" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
