<script setup lang="ts">
import type { MemberProfile, CreateMemberProfileRequest } from '~/types/member-profile'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const memberProfileApi = useMemberProfileApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

const profiles = ref<MemberProfile[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingProfile = ref<MemberProfile | null>(null)

const form = ref<CreateMemberProfileRequest>({
  displayName: '',
  memberNumber: '',
  bio: '',
  position: '',
})

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    profiles.value = await memberProfileApi.listMembers('organization', orgId.value)
  } catch {
    notification.error('メンバー情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingProfile.value = null
  form.value = { displayName: '', memberNumber: '', bio: '', position: '' }
  showDialog.value = true
}

function openEdit(profile: MemberProfile) {
  editingProfile.value = profile
  form.value = {
    displayName: profile.displayName,
    memberNumber: profile.memberNumber ?? '',
    bio: profile.bio ?? '',
    position: profile.position ?? '',
  }
  showDialog.value = true
}

async function save() {
  try {
    if (editingProfile.value) {
      await memberProfileApi.updateMember(
        'organization',
        orgId.value,
        editingProfile.value.id,
        form.value,
      )
      notification.success('メンバーを更新しました')
    } else {
      await memberProfileApi.createMember('organization', orgId.value, form.value)
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
          <InputText v-model="form.memberNumber" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">役職・ポジション</label>
          <InputText v-model="form.position" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">自己紹介</label>
          <Textarea v-model="form.bio" class="w-full" rows="3" />
        </div>
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
