export function useAccountProfile() {
  const api = useApi()
  const notification = useNotification()
  const { changeLocale } = useLocale()
  const {
    getProfile,
    updateProfile,
    changeEmail,
    changePassword,
    setupPassword,
  } = useUserSettingsApi()

  const savingProfile = ref(false)
  const profile = ref({
    displayName: '',
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

  const passwordError = computed(() => {
    if (passwordForm.value.newPassword && passwordForm.value.newPassword.length < 8)
      return 'パスワードは8文字以上で入力してください'
    if (
      passwordForm.value.confirmPassword &&
      passwordForm.value.newPassword !== passwordForm.value.confirmPassword
    )
      return 'パスワードが一致しません'
    return null
  })

  const canSubmitPassword = computed(() => {
    if (profile.value.hasPassword)
      return !!(
        passwordForm.value.currentPassword &&
        passwordForm.value.newPassword.length >= 8 &&
        passwordForm.value.newPassword === passwordForm.value.confirmPassword
      )
    return (
      passwordForm.value.newPassword.length >= 8 &&
      passwordForm.value.newPassword === passwordForm.value.confirmPassword
    )
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
        displayName: d.displayName,
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
        displayName: profile.value.displayName,
        phoneNumber: profile.value.phoneNumber,
        postalCode: profile.value.postalCode || undefined,
        isSearchable: profile.value.isSearchable,
      })
      notification.success('プロフィールを更新しました')
    } catch {
      notification.error('プロフィールの更新に失敗しました')
    } finally {
      savingProfile.value = false
    }
  }

  async function uploadAvatar(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0]
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

  async function saveLocale() {
    savingLocale.value = true
    try {
      await updateProfile({ locale: profile.value.locale, timezone: profile.value.timezone })
      await changeLocale(profile.value.locale)
      notification.success('言語・タイムゾーンを更新しました')
    } catch {
      notification.error('言語・タイムゾーンの更新に失敗しました')
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
      notification.success('確認メールを送信しました')
    } catch {
      notification.error('メールアドレスの変更リクエストに失敗しました')
    } finally {
      submittingEmail.value = false
    }
  }

  async function handlePasswordChange() {
    submittingPassword.value = true
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
        profile.value.hasPassword ? 'パスワードを変更しました' : 'パスワードを設定しました',
      )
    } catch {
      notification.error(
        profile.value.hasPassword
          ? 'パスワードの変更に失敗しました。現在のパスワードを確認してください'
          : 'パスワードの設定に失敗しました',
      )
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
