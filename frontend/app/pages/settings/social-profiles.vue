<script setup lang="ts">
import type { SocialProfile, CreateSocialProfileRequest, FollowListVisibility } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const socialApi = useSocialProfileApi()
const notification = useNotification()
const { t } = useI18n()

const profiles = ref<SocialProfile[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingProfile = ref<SocialProfile | null>(null)
const followListVisibility = ref<FollowListVisibility>('PUBLIC')
const savingVisibility = ref(false)

const form = ref<CreateSocialProfileRequest>({ handle: '', displayName: '', bio: '' })

const visibilityOptions = computed(() => [
  { label: t('label.visibilityPublic'), value: 'PUBLIC' as FollowListVisibility },
  { label: t('label.visibilityFriendsOnly'), value: 'FRIENDS_ONLY' as FollowListVisibility },
  { label: t('label.visibilityPrivate'), value: 'PRIVATE' as FollowListVisibility },
])

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

async function loadFollowListVisibility() {
  try {
    followListVisibility.value = await socialApi.getFollowListVisibility()
  } catch {
    // 取得失敗時はデフォルト値のまま
  }
}

async function onVisibilityChange() {
  savingVisibility.value = true
  try {
    await socialApi.updateFollowListVisibility(followListVisibility.value)
    notification.success(t('label.followListVisibility') + 'を更新しました')
  } catch {
    notification.error('公開設定の更新に失敗しました')
  } finally {
    savingVisibility.value = false
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

onMounted(async () => {
  await Promise.all([loadProfiles(), loadFollowListVisibility()])
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <BackButton to="/settings" />
        <PageHeader title="ソーシャルプロフィール" />
      </div>
      <Button v-if="profiles.length < 3" label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <p class="mb-4 text-sm text-surface-500">
      最大3つのプロフィールを作成できます（{{ profiles.length }}/3）
    </p>

    <!-- フォロー一覧公開設定 -->
    <SectionCard :title="$t('label.followListVisibility')" class="mb-6">
      <div class="space-y-3">
        <p class="text-sm text-surface-500">
          {{ $t('label.following') }}・{{ $t('label.followers') }} 一覧を誰に見せるか設定します
        </p>
        <Select
          v-model="followListVisibility"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          :loading="savingVisibility"
          @change="onVisibilityChange"
        />
      </div>
    </SectionCard>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-4">
      <SocialProfileCard
        v-for="profile in profiles"
        :key="profile.id"
        :profile="profile"
        :show-actions="true"
        @edit="openEdit"
        @delete="handleDelete"
      />
      <DashboardEmptyState v-if="profiles.length === 0" icon="pi-user-plus" message="まだプロフィールがありません" />
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
