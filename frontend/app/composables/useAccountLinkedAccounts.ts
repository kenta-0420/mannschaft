import type { OAuthProviderResponse, UserLineStatusResponse } from '~/types/user-settings'

export function useAccountLinkedAccounts() {
  const notification = useNotification()
  const { getOAuthProviders, unlinkOAuthProvider, getLineStatus, unlinkLine } =
    useUserSettingsApi()

  const oauthProviders = ref<OAuthProviderResponse[]>([])
  const lineStatus = ref<UserLineStatusResponse | null>(null)

  async function loadLinkedAccounts() {
    try {
      const [oauthRes, lineRes] = await Promise.all([getOAuthProviders(), getLineStatus()])
      oauthProviders.value = oauthRes.data
      lineStatus.value = lineRes.data
    } catch {
      /* silent */
    }
  }

  async function handleUnlinkOAuth(provider: string) {
    try {
      await unlinkOAuthProvider(provider)
      oauthProviders.value = oauthProviders.value.filter((p) => p.provider !== provider)
      notification.success(`${providerLabel(provider)}の連携を解除しました`)
    } catch {
      notification.error('連携解除に失敗しました')
    }
  }

  async function handleUnlinkLine() {
    try {
      await unlinkLine()
      lineStatus.value = {
        ...lineStatus.value!,
        isLinked: false,
        lineUserId: null,
        displayName: null,
        pictureUrl: null,
      }
      notification.success('LINE連携を解除しました')
    } catch {
      notification.error('LINE連携の解除に失敗しました')
    }
  }

  function providerLabel(provider: string) {
    return (
      (
        {
          google: 'Google',
          apple: 'Apple',
          github: 'GitHub',
          microsoft: 'Microsoft',
          line: 'LINE',
        } as Record<string, string>
      )[provider.toLowerCase()] || provider
    )
  }

  function providerIcon(provider: string) {
    return (
      (
        {
          google: 'pi pi-google',
          apple: 'pi pi-apple',
          github: 'pi pi-github',
          microsoft: 'pi pi-microsoft',
        } as Record<string, string>
      )[provider.toLowerCase()] || 'pi pi-link'
    )
  }

  return {
    oauthProviders,
    lineStatus,
    loadLinkedAccounts,
    handleUnlinkOAuth,
    handleUnlinkLine,
    providerLabel,
    providerIcon,
  }
}
