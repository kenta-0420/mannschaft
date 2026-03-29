<script setup lang="ts">
import type { SocialProfile, CreateSocialProfileRequest } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const socialApi = useSocialProfileApi()
const notification = useNotification()

const profiles = ref<SocialProfile[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingProfile = ref<SocialProfile | null>(null)

const form = ref<CreateSocialProfileRequest>({ handle: '', displayName: '', bio: '' })

async function loadProfiles() {
  loading.value = true
  try {
    const profile = await socialApi.getMyProfile()
    profiles.value = profile ? [profile] : []
  } catch {
    notification.error('プロフィールの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingProfile.value = null
  form.value = { handle: '', displayName: '', bio: '' }
  showDialog.value = true
}

function openEdit(profile: SocialProfile) {
  editingProfile.value = profile
  form.value = { handle: profile.handle, displayName: profile.displayName, bio: profile.bio ?? '' }
  showDialog.value = true
}

async function save() {
  try {
    if (editingProfile.value) {
      await socialApi.updateMyProfile(form.value)
      notification.success('プロフィールを更新しました')
    } else {
      await socialApi.create(form.value)
      notification.success('プロフィールを作成しました')
    }
    showDialog.value = false
    await loadProfiles()
  } catch {
    notification.error('保存に失敗しました')
  }
}

async function handleDelete(_id: number) {
  try {
    await socialApi.deleteMyProfile()
    notification.success('プロフィールを削除しました')
    await loadProfiles()
  } catch {
    notification.error('削除に失敗しました')
  }
}

onMounted(loadProfiles)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">ソーシャルプロフィール</h1>
      <Button v-if="profiles.length < 3" label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <p class="mb-4 text-sm text-surface-500">
      最大3つのプロフィールを作成できます（{{ profiles.length }}/3）
    </p>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <div v-else class="space-y-4">
      <SocialProfileCard
        v-for="profile in profiles"
        :key="profile.id"
        :profile="profile"
        :show-actions="true"
        @edit="openEdit"
        @delete="handleDelete"
      />
      <div v-if="profiles.length === 0" class="py-12 text-center text-surface-500">
        <i class="pi pi-user-plus mb-2 text-4xl" />
        <p>まだプロフィールがありません</p>
      </div>
    </div>

    <Dialog
      v-model:visible="showDialog"
      :header="editingProfile ? 'プロフィール編集' : 'プロフィール作成'"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">ハンドル名 *</label>
          <InputText
            v-model="form.handle"
            class="w-full"
            placeholder="my_handle"
            :disabled="!!editingProfile"
          />
          <p class="mt-1 text-xs text-surface-500">英数字とアンダースコアのみ（変更は30日に1回）</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">表示名 *</label>
          <InputText v-model="form.displayName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">自己紹介</label>
          <Textarea v-model="form.bio" class="w-full" rows="3" :maxlength="300" />
          <p class="mt-1 text-right text-xs text-surface-400">{{ form.bio?.length ?? 0 }}/300</p>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
        <Button
          :label="editingProfile ? '更新' : '作成'"
          icon="pi pi-check"
          :disabled="!form.handle || !form.displayName"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
