<script setup lang="ts">
import type { MemberProfile, MemberProfileField, CreateMemberProfileRequest } from '~/types/member-profile'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const memberProfileApi = useMemberProfileApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const profiles = ref<MemberProfile[]>([])
const fields = ref<MemberProfileField[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingProfile = ref<MemberProfile | null>(null)

const form = ref<CreateMemberProfileRequest & { customFieldValues: Record<string, string> }>({
  displayName: '',
  memberNumber: '',
  bio: '',
  position: '',
  customFieldValues: {},
})

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const [profilesResult, fieldsResult] = await Promise.all([
      memberProfileApi.listMembers('team', teamId.value),
      memberProfileApi.listFields('team', teamId.value),
    ])
    profiles.value = profilesResult
    fields.value = fieldsResult
  } catch {
    notification.error('メンバー情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingProfile.value = null
  form.value = { displayName: '', memberNumber: '', bio: '', position: '', customFieldValues: {} }
  showDialog.value = true
}

function openEdit(profile: MemberProfile) {
  editingProfile.value = profile
  form.value = {
    displayName: profile.displayName,
    memberNumber: profile.memberNumber ?? '',
    bio: profile.bio ?? '',
    position: profile.position ?? '',
    customFieldValues: { ...(profile.customFields ?? {}) },
  }
  showDialog.value = true
}

async function save() {
  try {
    const { customFieldValues, ...baseFields } = form.value
    const body: CreateMemberProfileRequest = {
      ...baseFields,
      customFields: customFieldValues,
    }
    if (editingProfile.value) {
      await memberProfileApi.updateMember('team', teamId.value, editingProfile.value.id, body)
      notification.success('メンバーを更新しました')
    } else {
      await memberProfileApi.createMember('team', teamId.value, body)
      notification.success('メンバーを追加しました')
    }
    showDialog.value = false
    await loadData()
  } catch {
    notification.error('保存に失敗しました')
  }
}

async function handleDelete(id: number) {
  try {
    await memberProfileApi.deleteMember(id)
    notification.success('メンバーを削除しました')
    await loadData()
  } catch {
    notification.error('削除に失敗しました')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center gap-3">
      <BackButton />
      <PageHeader title="メンバー紹介" />
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <MemberProfileList
        :profiles="profiles"
        :editable="isAdmin"
        @create="openCreate"
        @edit="openEdit"
        @delete="handleDelete"
      />

      <!-- 拡張フィールド列（DataTable での表示用） -->
      <DataTable v-if="fields.length > 0" :value="profiles" class="mt-6 w-full">
        <Column field="displayName" header="名前" />
        <Column v-for="field in fields" :key="field.id" :header="field.fieldName">
          <template #body="{ data }">{{ data.customFields?.[String(field.id)] ?? '-' }}</template>
        </Column>
      </DataTable>
    </template>

    <Dialog
      v-model:visible="showDialog"
      :header="editingProfile ? 'メンバー編集' : 'メンバー追加'"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">名前 *</label>
          <InputText v-model="form.displayName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">背番号・番号</label>
          <InputText v-model="form.memberNumber" class="w-full" placeholder="例: 10" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">役職・ポジション</label>
          <InputText v-model="form.position" class="w-full" placeholder="例: FW" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">自己紹介</label>
          <Textarea v-model="form.bio" class="w-full" rows="3" />
        </div>

        <!-- 拡張フィールド -->
        <template v-for="field in fields" :key="field.id">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">
              {{ field.fieldName }}<span v-if="field.isRequired" class="text-red-500">*</span>
            </label>
            <InputText
              v-if="field.fieldType === 'TEXT'"
              v-model="form.customFieldValues[String(field.id)]"
              class="w-full"
            />
            <InputText
              v-else-if="field.fieldType === 'NUMBER'"
              v-model="form.customFieldValues[String(field.id)]"
              type="number"
              class="w-full"
            />
            <DatePicker
              v-else-if="field.fieldType === 'DATE'"
              v-model="form.customFieldValues[String(field.id)]"
              date-format="yy/mm/dd"
              class="w-full"
            />
            <Textarea
              v-else-if="field.fieldType === 'TEXTAREA'"
              v-model="form.customFieldValues[String(field.id)]"
              class="w-full"
              rows="3"
            />
            <Select
              v-else-if="field.fieldType === 'SELECT'"
              v-model="form.customFieldValues[String(field.id)]"
              :options="field.options ?? []"
              class="w-full"
            />
            <Checkbox
              v-else-if="field.fieldType === 'CHECKBOX'"
              v-model="form.customFieldValues[String(field.id)]"
              :binary="true"
            />
          </div>
        </template>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
        <Button
          :label="editingProfile ? '更新' : '追加'"
          icon="pi pi-check"
          :disabled="!form.displayName"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
