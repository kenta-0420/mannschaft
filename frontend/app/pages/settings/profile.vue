<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const api = useApi()
const notification = useNotification()
const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const saving = ref(false)

const handle = ref('')
const handleAvailable = ref<boolean | null>(null)
const savingHandle = ref(false)
const currentHandle = ref<string | null>(null)

async function fetchHandle() {
  try {
    const result = await contactApi.getMyHandle()
    currentHandle.value = result.data.contactHandle
    handle.value = result.data.contactHandle ?? ''
  } catch (e) {
    captureQuiet(e, { context: 'ProfileSettings: ハンドル取得' })
  }
}

async function saveHandle() {
  if (!handle.value || handleAvailable.value === false) return
  savingHandle.value = true
  try {
    const result = await contactApi.updateMyHandle(handle.value)
    currentHandle.value = result.data.contactHandle
    notification.success('@ハンドルを設定しました')
  } catch (e) {
    captureQuiet(e, { context: 'ProfileSettings: ハンドル保存' })
    notification.error('ハンドルの設定に失敗しました')
  } finally {
    savingHandle.value = false
  }
}

const profile = ref({
  displayName: '',
  email: '',
  phoneNumber: '',
  avatarUrl: null as string | null,
})

onMounted(async () => {
  try {
    const res = await api<{ data: typeof profile.value }>('/api/v1/users/me')
    profile.value = res.data
  } catch {
    notification.error('プロフィール情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
  await fetchHandle()
})

async function saveProfile() {
  saving.value = true
  try {
    await api('/api/v1/users/me', {
      method: 'PATCH',
      body: {
        displayName: profile.value.displayName,
        phoneNumber: profile.value.phoneNumber,
      },
    })
    notification.success('プロフィールを更新しました')
  } catch {
    notification.error('プロフィールの更新に失敗しました')
  } finally {
    saving.value = false
  }
}

async function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  if (file.size > 5 * 1024 * 1024) {
    notification.error('ファイルサイズは5MB以下にしてください')
    return
  }

  const formData = new FormData()
  formData.append('file', file)

  try {
    const res = await api<{ data: { avatarUrl: string } }>('/api/v1/users/me/avatar', {
      method: 'POST',
      body: formData,
    })
    profile.value.avatarUrl = res.data.avatarUrl
    notification.success('アバターを更新しました')
  } catch {
    notification.error('アバターのアップロードに失敗しました')
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <BackButton to="/settings" />
    <PageHeader title="プロフィール設定" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <SectionCard title="プロフィール情報">
        <div class="space-y-4">
          <div class="flex items-center gap-4">
            <div class="relative">
              <img
                v-if="profile.avatarUrl"
                :src="profile.avatarUrl"
                alt="アバター"
                class="h-20 w-20 rounded-full object-cover"
              >
              <div
                v-else
                class="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 text-2xl text-primary"
              >
                <i class="pi pi-user" />
              </div>
            </div>
            <div>
              <label class="cursor-pointer">
                <input type="file" accept="image/*" class="hidden" @change="uploadAvatar" >
                <Button
                  label="画像を変更"
                  icon="pi pi-upload"
                  severity="secondary"
                  size="small"
                  as="span"
                />
              </label>
              <p class="mt-1 text-xs text-surface-500">5MB以下のJPG, PNG</p>
            </div>
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">表示名</label>
            <InputText v-model="profile.displayName" class="w-full" />
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">メールアドレス</label>
            <InputText :model-value="profile.email" class="w-full" disabled />
            <p class="mt-1 text-xs text-surface-500">
              メールアドレスの変更はサポートにお問い合わせください
            </p>
          </div>

          <div>
            <label class="mb-1 block text-sm font-medium">電話番号</label>
            <InputText v-model="profile.phoneNumber" class="w-full" placeholder="090-0000-0000" />
          </div>

          <div class="flex justify-end">
            <Button label="保存" icon="pi pi-check" :loading="saving" @click="saveProfile" />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="@ハンドル">
        <div class="space-y-3">
          <p class="text-sm text-gray-500">
            @ハンドルを設定すると、他のユーザーがあなたを検索して連絡先に追加できます。
          </p>
          <UserHandleInput v-model="handle" @availability-change="handleAvailable = $event" />
          <div class="flex justify-end">
            <Button
              label="保存"
              icon="pi pi-check"
              :loading="savingHandle"
              :disabled="!handle || handleAvailable === false"
              @click="saveHandle"
            />
          </div>
        </div>
      </SectionCard>

      <SettingsPasswordChange />

      <SettingsAccountDelete />
    </div>
  </div>
</template>
