<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const notification = useNotification()
const { resolveMessage } = useErrorHandler()
const { getProfile, changePassword, setupPassword } = useUserSettingsApi()

const loading = ref(true)
const submitting = ref(false)
const hasPassword = ref(true)

const { redirecting, triggerRedirect } = usePasswordChangeRedirect()

const form = ref({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

onMounted(async () => {
  try {
    const res = await getProfile()
    hasPassword.value = res.data.hasPassword
  } catch {
    notification.error(t('settings.password.load_error'))
  } finally {
    loading.value = false
  }
})

// 登録時（register.vue）と同一ポリシー: 大文字/小文字/数字/記号のうち3種以上
// utils/passwordPolicy.ts に移管済み
const meetsPolicy = computed(
  () => meetsPasswordPolicy(form.value.newPassword),
)

const passwordError = computed(() => {
  if (form.value.newPassword && form.value.newPassword.length < 8) {
    return t('settings.password.length_error')
  }
  if (form.value.newPassword && countCharTypes(form.value.newPassword) < 3) {
    return t('settings.password.policy_violation')
  }
  if (form.value.confirmPassword && form.value.newPassword !== form.value.confirmPassword) {
    return t('settings.password.mismatch_error')
  }
  return null
})

const canSubmit = computed(() => {
  const baseValid = meetsPolicy.value && form.value.newPassword === form.value.confirmPassword
  if (hasPassword.value) {
    return !!form.value.currentPassword && baseValid
  }
  return baseValid
})

function notifyError(e: unknown) {
  const code = (e as { data?: { error?: { code?: string; message?: string } } })?.data?.error?.code
  if (code) {
    // error.${code} キーで6言語解決（AUTH_008/009/010/011 等）
    const message = resolveMessage(
      code,
      (e as { data?: { error?: { message?: string } } })?.data?.error?.message,
    )
    notification.error(message)
    return
  }
  // コードが取れない場合のみ汎用文言にフォールバック
  notification.error(
    hasPassword.value
      ? t('settings.password.toast.change_error')
      : t('settings.password.toast.setup_error'),
  )
}

async function handleSubmit() {
  submitting.value = true
  try {
    if (hasPassword.value) {
      await changePassword({
        currentPassword: form.value.currentPassword,
        newPassword: form.value.newPassword,
      })
      form.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
      // パスワード変更後はBEが全リフレッシュトークンを失効させるため、
      // 先手を打ってログアウトし /login へ誘導する（大量の401/429発生を防ぐ）。
      // トーストは短時間表示されてからログイン画面に切り替わる。
      notification.success(t('settings.password.toast.change_success'))
      // overlay フェードイン → 900ms 保持 → logout（/login へ遷移）
      await triggerRedirect()
    } else {
      await setupPassword(form.value.newPassword)
      hasPassword.value = true
      form.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
      notification.success(t('settings.password.toast.setup_success'))
    }
  } catch (e) {
    notifyError(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <!-- パスワード変更後のリダイレクト中オーバーレイ（フェードイン表示） -->
    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="redirecting"
          class="fixed inset-0 z-[9999] flex flex-col items-center justify-center gap-4 bg-surface-0 dark:bg-surface-900"
        >
          <LoadingBounce />
          <p class="text-sm font-medium text-surface-600 dark:text-surface-300">
            {{ t('settings.password.redirecting') }}
          </p>
        </div>
      </Transition>
    </Teleport>

    <PageHeader
      :title="hasPassword ? t('settings.password.section_title_change') : t('settings.password.section_title_set')"
    />

    <PageLoading v-if="loading" />

    <SectionCard v-else class="fade-in">
      <div class="space-y-4">
        <div v-if="hasPassword">
          <label class="mb-1 block text-sm font-medium">{{
            t('settings.password.current_password')
          }}</label>
          <Password
            v-model="form.currentPassword"
            :feedback="false"
            toggle-mask
            class="w-full"
            input-class="w-full"
          />
        </div>

        <div v-if="!hasPassword">
          <p class="mb-4 text-sm text-surface-500">
            {{ t('settings.password.no_password_description') }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{
            t('settings.password.new_password')
          }}</label>
          <Password v-model="form.newPassword" toggle-mask class="w-full" input-class="w-full" />
          <p class="mt-1 text-xs text-surface-500">{{ t('settings.password.policy_hint') }}</p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{
            t('settings.password.confirm_password')
          }}</label>
          <Password
            v-model="form.confirmPassword"
            :feedback="false"
            toggle-mask
            class="w-full"
            input-class="w-full"
          />
        </div>

        <p v-if="passwordError" class="text-sm text-red-500">{{ passwordError }}</p>

        <div class="flex justify-end">
          <Button
            :label="hasPassword ? t('settings.password.change_button') : t('settings.password.set_button')"
            icon="pi pi-lock"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          />
        </div>
      </div>
    </SectionCard>
  </div>
</template>
