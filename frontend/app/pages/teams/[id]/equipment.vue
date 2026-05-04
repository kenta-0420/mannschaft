<script setup lang="ts">
import type { EquipmentType } from '~/types/equipment'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const { createEquipment } = useEquipmentApi()
const notification = useNotification()

const loading = ref(true)
const showCreateDialog = ref(false)
const saving = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

const form = ref({
  name: '',
  quantity: 1,
  equipmentType: 'REUSABLE' as EquipmentType,
  category: '',
  description: '',
})

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})

function openCreateDialog() {
  form.value = { name: '', quantity: 1, equipmentType: 'REUSABLE', category: '', description: '' }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.name.trim()) return
  saving.value = true
  try {
    await createEquipment('team', teamId, {
      name: form.value.name,
      quantity: form.value.quantity,
      equipmentType: form.value.equipmentType,
      category: form.value.category || undefined,
      description: form.value.description || undefined,
    })
    notification.success('備品を登録しました')
    showCreateDialog.value = false
    listRef.value?.refresh()
  } catch {
    notification.error('備品の登録に失敗しました')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <!-- デスクトップ: サイドパネルレイアウト -->
    <div class="flex gap-4">
      <div class="min-w-0 flex-1">
        <EquipmentList
          ref="listRef"
          scope-type="team"
          :scope-id="teamId"
          :can-manage="isAdminOrDeputy"
          @create="openCreateDialog"
        />
      </div>
      <!-- デスクトップのみサイドパネル表示 -->
      <aside class="hidden w-80 shrink-0 lg:block">
        <EquipmentTrending :team-id="teamId" />
      </aside>
    </div>
    <!-- モバイル・タブレット: 下部表示 -->
    <div class="mt-4 lg:hidden">
      <EquipmentTrending :team-id="teamId" />
    </div>

    <Dialog v-model:visible="showCreateDialog" modal header="備品を登録" :style="{ width: '28rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">備品名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" placeholder="例: サッカーボール" class="w-full" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">数量 <span class="text-red-500">*</span></label>
            <InputNumber v-model="form.quantity" :min="1" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">種別</label>
            <Select
              v-model="form.equipmentType"
              :options="[
                { label: '再利用可能', value: 'REUSABLE' },
                { label: '消耗品', value: 'CONSUMABLE' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">カテゴリ</label>
          <InputText v-model="form.category" placeholder="例: ボール類" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="2" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text :disabled="saving" @click="showCreateDialog = false" />
        <Button label="登録" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
