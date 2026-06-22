export function useAccountProfile() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()
  const { resolveMessage } = useErrorHandler()
  const { changeLocale } = useLocale()
  const authStore = useAuthStore()
  const {
    getProfile,
    updateProfile,
    changeEmail,
    changePassword,
    setupPassword,
  } = useUserSettingsApi()

  const savingProfile = ref(false)
  const profile = ref({
    nickname: '',
    email: '',
    phoneNumber: '',
    postalCode: '',
    avatarUrl: null as string | null,
    isSearchable: false,
    locale: 'ja',
    timezone: 'Asia/Tokyo',
    hasPassword: true,
  })

  const emailForm = ref({ newEmail: '', currentPassword: '' })
  const submittingEmail = ref(false)
  const emailSent = ref(false)

  const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const submittingPassword = ref(false)

  const savingLocale = ref(false)

  // 金型（settings/password.vue）と同一ロジック。utils/passwordPolicy.ts を共用。
  const passwordError = computed(() => {
    if (passwordForm.value.newPassword && passwordForm.value.newPassword.length < 8)
      return t('settings.password.length_error')
    if (passwordForm.value.newPassword && countCharTypes(passwordForm.value.newPassword) < 3)
      return t('settings.password.policy_violation')
    if (
      passwordForm.value.confirmPassword &&
      passwordForm.value.newPassword !== passwordForm.value.confirmPassword
    )
      return t('settings.password.mismatch_error')
    return null
  })

  const canSubmitPassword = computed(() => {
    const baseValid =
      meetsPasswordPolicy(passwordForm.value.newPassword) &&
      passwordForm.value.newPassword === passwordForm.value.confirmPassword
    if (profile.value.hasPassword)
      return !!(passwordForm.value.currentPassword && baseValid)
    return baseValid
  })

  const canSubmitEmail = computed(
    () =>
      !!(
        emailForm.value.newEmail &&
        emailForm.value.currentPassword &&
        emailForm.value.newEmail !== profile.value.email
      ),
  )

  async function loadProfile() {
    try {
      const res = await getProfile()
      const d = res.data
      profile.value = {
        nickname: d.nickname,
        email: d.email,
        phoneNumber: d.phoneNumber,
        postalCode: '',
        avatarUrl: d.avatarUrl,
        isSearchable: d.isSearchable,
        locale: d.locale || 'ja',
        timezone: d.timezone || 'Asia/Tokyo',
        hasPassword: d.hasPassword,
      }
    } catch {
      /* silent */
    }
  }

  async function saveProfile() {
    savingProfile.value = true
    try {
      await updateProfile({
        nickname: profile.value.nickname,
        phoneNumber: profile.value.phoneNumber,
        postalCode: profile.value.postalCode || undefined,
        isSearchable: profile.value.isSearchable,
      })
      notification.success(t('settings.profile.toast.save_success'))
    } catch {
      notification.error(t('settings.profile.toast.save_error'))
    } finally {
      savingProfile.value = false
    }
  }

  async function uploadAvatar(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0]
    if (!file) return
    if (file.size > 5 * 1024 * 1024) {
      notification.error(t('settings.profile.toast.avatar_size_error'))
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
      notification.success(t('settings.profile.toast.avatar_success'))
    } catch {
      notification.error(t('settings.profile.toast.avatar_error'))
    }
  }

  async function saveLocale() {
    savingLocale.value = true
    try {
      await updateProfile({ locale: profile.value.locale, timezone: profile.value.timezone })
      await changeLocale(profile.value.locale)
      // authStore の user.locale を更新して localStorage と同期する。
      // これにより次回リロード時に locale.client.ts が正しいロケールを復元できる。
      if (authStore.user) {
        await authStore.setUser({ ...authStore.user, locale: profile.value.locale })
      }
      notification.success(t('settings.locale.toast.save_success'))
    } catch {
      notification.error(t('settings.locale.toast.save_error'))
    } finally {
      savingLocale.value = false
    }
  }

  async function handleEmailChange() {
    submittingEmail.value = true
    try {
      await changeEmail({
        newEmail: emailForm.value.newEmail,
        currentPassword: emailForm.value.currentPassword,
      })
      emailSent.value = true
      notification.success(t('settings.email.toast.send_success'))
    } catch {
      notification.error(t('settings.email.toast.send_error'))
    } finally {
      submittingEmail.value = false
    }
  }

  async function handlePasswordChange() {
    submittingPassword.value = true
    // 変更前に hasPassword を記録しておく（成功後に true へ書き換わるため）
    const wasHavingPassword = profile.value.hasPassword
    try {
      if (profile.value.hasPassword) {
        await changePassword({
          currentPassword: passwordForm.value.currentPassword,
          newPassword: passwordForm.value.newPassword,
        })
      } else {
        await setupPassword(passwordForm.value.newPassword)
        profile.value.hasPassword = true
      }
      passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
      notification.success(
        wasHavingPassword
          ? t('settings.password.toast.change_success')
          : t('settings.password.toast.setup_success'),
      )
    } catch (e) {
      // error.code が取れれば resolveMessage で解決（AUTH_008/009/010/011 等）
      const code = (e as { data?: { error?: { code?: string; message?: string } } })?.data?.error
        ?.code
      if (code) {
        notification.error(
          resolveMessage(
            code,
            (e as { data?: { error?: { message?: string } } })?.data?.error?.message,
          ),
        )
      } else {
        notification.error(
          wasHavingPassword
            ? t('settings.password.toast.change_error')
            : t('settings.password.toast.setup_error'),
        )
      }
    } finally {
      submittingPassword.value = false
    }
  }

  return {
    profile,
    savingProfile,
    emailForm,
    submittingEmail,
    emailSent,
    passwordForm,
    submittingPassword,
    savingLocale,
    passwordError,
    canSubmitPassword,
    canSubmitEmail,
    loadProfile,
    saveProfile,
    uploadAvatar,
    saveLocale,
    handleEmailChange,
    handlePasswordChange,
  }
}
