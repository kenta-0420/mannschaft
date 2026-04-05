import type { SocialProfile, CreateSocialProfileRequest } from '~/types/social-profile'

export function useAccountSocialProfile() {
  const notification = useNotification()
  const socialApi = useSocialProfileApi()

  const socialProfiles = ref<SocialProfile[]>([])
  const showSocialDialog = ref(false)
  const editingSocialProfile = ref<SocialProfile | null>(null)
  const socialForm = ref<CreateSocialProfileRequest>({ handle: '', displayName: '', bio: '' })

  async function loadSocialProfiles() {
    try {
      const p = await socialApi.getMyProfile()
      socialProfiles.value = p ? [p] : []
    } catch {
      /* silent */
    }
  }

  function openCreateSocial() {
    editingSocialProfile.value = null
    socialForm.value = { handle: '', displayName: '', bio: '' }
    showSocialDialog.value = true
  }

  function openEditSocial(p: SocialProfile) {
    editingSocialProfile.value = p
    socialForm.value = { handle: p.handle, displayName: p.displayName, bio: p.bio ?? '' }
    showSocialDialog.value = true
  }

  async function saveSocial() {
    try {
      if (editingSocialProfile.value) {
        await socialApi.updateMyProfile(socialForm.value)
        notification.success('プロフィールを更新しました')
      } else {
        await socialApi.create(socialForm.value)
        notification.success('プロフィールを作成しました')
      }
      showSocialDialog.value = false
      await loadSocialProfiles()
    } catch {
      notification.error('保存に失敗しました')
    }
  }

  async function handleDeleteSocial(_id: number) {
    try {
      await socialApi.deleteMyProfile()
      notification.success('プロフィールを削除しました')
      await loadSocialProfiles()
    } catch {
      notification.error('削除に失敗しました')
    }
  }

  return {
    socialProfiles,
    showSocialDialog,
    editingSocialProfile,
    socialForm,
    loadSocialProfiles,
    openCreateSocial,
    openEditSocial,
    saveSocial,
    handleDeleteSocial,
  }
}
