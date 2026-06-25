import type { OAuthProviderResponse, UserLineStatusResponse } from '~/types/user-settings'

export function useAccountLinkedAccounts() {
  const notification = useNotification()
  const { t } = useI18n()
  const { getOAuthProviders, unlinkOAuthProvider, getLineStatus, unlinkLine, getOAuthLinkAuthUrl } =
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
      notification.success(t('settings.linked_accounts.toast.unlink_success', { provider: providerLabel(provider) }))
    } catch {
      notification.error(t('settings.linked_accounts.toast.unlink_error'))
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
      notification.success(t('settings.linked_accounts.toast.line_unlink_success'))
    } catch {
      notification.error(t('settings.linked_accounts.toast.line_unlink_error'))
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

  async function handleLinkOAuth(provider: string) {
    try {
      const res = await getOAuthLinkAuthUrl(provider)
      window.location.href = res.data.authUrl
    } catch {
      notification.error(t('settings.linked_accounts.toast.link_error'))
    }
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
    handleLinkOAuth,
    providerLabel,
    providerIcon,
  }
}
