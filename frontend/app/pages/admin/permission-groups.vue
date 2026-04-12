<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'

definePageMeta({ middleware: 'auth' })

const api = useApi()
const { success, error: showError } = useNotification()

interface Permission {
  key: string
  label: string
}

interface PermissionGroup {
  id: number
  name: string
  description: string | null
  permissionKeys: string[]
  assignedCount: number
  createdAt: string
}

const AVAILABLE_PERMISSIONS: Permission[] = [
  { key: 'post.create', label: '投稿作成' },
  { key: 'post.edit', label: '投稿編集' },
  { key: 'post.delete', label: '投稿削除' },
  { key: 'member.view', label: 'メンバー閲覧' },
  { key: 'member.edit', label: 'メンバー編集' },
  { key: 'member.invite', label: 'メンバー招待' },
  { key: 'schedule.create', label: 'スケジュール作成' },
  { key: 'schedule.edit', label: 'スケジュール編集' },
  { key: 'schedule.delete', label: 'スケジュール削除' },
  { key: 'bulletin.create', label: '掲示板作成' },
  { key: 'bulletin.moderate', label: '掲示板管理' },
  { key: 'file.upload', label: 'ファイルアップロード' },
  { key: 'file.delete', label: 'ファイル削除' },
  { key: 'settings.view', label: '設定閲覧' },
  { key: 'settings.edit', label: '設定編集' },
]

const groups = ref<PermissionGroup[]>([])
const loading = ref(true)
const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const showAssignDialog = ref(false)
const selectedGroup = ref<PermissionGroup | null>(null)
const selectedPermissions = ref<string[]>([])
const assignUserId = ref<number | null>(null)
const saving = ref(false)

const groupSchema = z.object({
  name: z.string().min(1, '名前は必須です').max(100, '100文字以内で入力してください'),
  description: z.string().max(500, '500文字以内で入力してください').optional(),
})

type GroupForm = z.infer<typeof groupSchema>

const { defineField, handleSubmit, resetForm, errors } = useForm<GroupForm>({
  validationSchema: toTypedSchema(groupSchema),
})

const [name, nameAttrs] = defineField('name')
const [description, descriptionAttrs] = defineField('description')

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: PermissionGroup[] }>('/api/v1/admin/permission-groups')
    groups.value = res.data
  } catch {
    showError('権限グループの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  selectedPermissions.value = []
  showCreateDialog.value = true
}

function openEdit(group: PermissionGroup) {
  selectedGroup.value = group
  resetForm({ values: { name: group.name, description: group.description ?? '' } })
  selectedPermissions.value = [...group.permissionKeys]
  showEditDialog.value = true
}

function openAssign(group: PermissionGroup) {
  selectedGroup.value = group
  assignUserId.value = null
  showAssignDialog.value = true
}

async function duplicateGroup(group: PermissionGroup) {
  try {
    await api('/api/v1/admin/permission-groups', {
      method: 'POST',
      body: {
        name: `${group.name} (コピー)`,
        description: group.description,
        permissionKeys: group.permissionKeys,
      },
    })
    success('権限グループを複製しました')
    await load()
  } catch {
    showError('複製に失敗しました')
  }
}

async function deleteGroup(group: PermissionGroup) {
  if (!confirm(`「${group.name}」を削除しますか？`)) return
  try {
    await api(`/api/v1/admin/permission-groups/${group.id}`, { method: 'DELETE' })
    success('権限グループを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

const onCreate = handleSubmit(async (values) => {
  saving.value = true
  try {
    await api('/api/v1/admin/permission-groups', {
      method: 'POST',
      body: { ...values, permissionKeys: selectedPermissions.value },
    })
    success('権限グループを作成しました')
    showCreateDialog.value = false
    await load()
  } catch {
    showError('作成に失敗しました')
  } finally {
    saving.value = false
  }
})

const onEdit = handleSubmit(async (values) => {
  if (!selectedGroup.value) return
  saving.value = true
  try {
    await api(`/api/v1/admin/permission-groups/${selectedGroup.value.id}`, {
      method: 'PUT',
      body: { ...values, permissionKeys: selectedPermissions.value },
    })
    success('権限グループを更新しました')
    showEditDialog.value = false
    await load()
  } catch {
    showError('更新に失敗しました')
  } finally {
    saving.value = false
  }
})

async function assignToUser() {
  if (!selectedGroup.value || !assignUserId.value) return
  saving.value = true
  try {
    await api(`/api/v1/admin/permission-groups/${selectedGroup.value.id}/assign`, {
      method: 'POST',
      body: { userId: assignUserId.value },
    })
    success('ユーザーに権限グループを割り当てました')
    showAssignDialog.value = false
  } catch {
    showError('割り当てに失敗しました')
  } finally {
    saving.value = false
  }
}

function togglePermission(key: string) {
  const idx = selectedPermissions.value.indexOf(key)
  if (idx >= 0) {
    selectedPermissions.value.splice(idx, 1)
  } else {
    selectedPermissions.value.push(key)
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader title="権限グループ管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="groups" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">権限グループがありません</div>
      </template>
      <Column field="name" header="グループ名" />
      <Column header="パーミッション数" style="width: 140px">
        <template #body="{ data }">
          <Tag :value="`${data.permissionKeys.length}件`" severity="info" />
        </template>
      </Column>
      <Column header="割り当て人数" style="width: 120px">
        <template #body="{ data }">
          {{ data.assignedCount }}人
        </template>
      </Column>
      <Column header="作成日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.createdAt).toLocaleDateString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 220px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button label="編集" size="small" severity="info" text @click="openEdit(data)" />
            <Button label="複製" size="small" severity="secondary" text @click="duplicateGroup(data)" />
            <Button label="割り当て" size="small" severity="success" text @click="openAssign(data)" />
            <Button label="削除" size="small" severity="danger" text @click="deleteGroup(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 新規作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="権限グループ作成"
      :style="{ width: '560px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onCreate">
        <div>
          <label class="mb-1 block text-sm font-medium">グループ名 <span class="text-red-500">*</span></label>
          <InputText v-model="name" v-bind="nameAttrs" class="w-full" placeholder="例: コンテンツ編集者" />
          <p v-if="errors.name" class="mt-1 text-xs text-red-500">{{ errors.name }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="description" v-bind="descriptionAttrs" class="w-full" rows="2" />
          <p v-if="errors.description" class="mt-1 text-xs text-red-500">{{ errors.description }}</p>
        </div>
        <div>
          <label class="mb-2 block text-sm font-medium">パーミッション</label>
          <div class="grid grid-cols-2 gap-2 rounded-lg border border-surface-300 p-3">
            <div
              v-for="perm in AVAILABLE_PERMISSIONS"
              :key="perm.key"
              class="flex items-center gap-2"
            >
              <Checkbox
                :input-id="`perm-create-${perm.key}`"
                :model-value="selectedPermissions.includes(perm.key)"
                :binary="true"
                @update:model-value="togglePermission(perm.key)"
              />
              <label :for="`perm-create-${perm.key}`" class="cursor-pointer text-sm">{{ perm.label }}</label>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showCreateDialog = false" />
          <Button label="作成" type="submit" :loading="saving" />
        </div>
      </form>
    </Dialog>

    <!-- 編集ダイアログ -->
    <Dialog
      v-model:visible="showEditDialog"
      header="権限グループ編集"
      :style="{ width: '560px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onEdit">
        <div>
          <label class="mb-1 block text-sm font-medium">グループ名 <span class="text-red-500">*</span></label>
          <InputText v-model="name" v-bind="nameAttrs" class="w-full" />
          <p v-if="errors.name" class="mt-1 text-xs text-red-500">{{ errors.name }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="description" v-bind="descriptionAttrs" class="w-full" rows="2" />
          <p v-if="errors.description" class="mt-1 text-xs text-red-500">{{ errors.description }}</p>
        </div>
        <div>
          <label class="mb-2 block text-sm font-medium">パーミッション</label>
          <div class="grid grid-cols-2 gap-2 rounded-lg border border-surface-300 p-3">
            <div
              v-for="perm in AVAILABLE_PERMISSIONS"
              :key="perm.key"
              class="flex items-center gap-2"
            >
              <Checkbox
                :input-id="`perm-edit-${perm.key}`"
                :model-value="selectedPermissions.includes(perm.key)"
                :binary="true"
                @update:model-value="togglePermission(perm.key)"
              />
              <label :for="`perm-edit-${perm.key}`" class="cursor-pointer text-sm">{{ perm.label }}</label>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showEditDialog = false" />
          <Button label="更新" type="submit" :loading="saving" />
        </div>
      </form>
    </Dialog>

    <!-- ユーザー割り当てダイアログ -->
    <Dialog
      v-model:visible="showAssignDialog"
      :header="`ユーザー割り当て: ${selectedGroup?.name}`"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">ユーザーID</label>
          <InputNumber v-model="assignUserId" class="w-full" placeholder="ユーザーIDを入力" />
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showAssignDialog = false" />
          <Button label="割り当て" :loading="saving" :disabled="!assignUserId" @click="assignToUser" />
        </div>
      </div>
    </Dialog>
  </div>
</template>
