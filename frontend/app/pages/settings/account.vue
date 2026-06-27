<script setup lang="ts">
import type { ScopeDefault } from '~/types/seal'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const showDeletionPreviewDialog = ref(false)
const api = useApi()
const notification = useNotification()

async function handleDeleteAccount(currentPassword: string | null) {
  try {
    await api('/api/v1/users/me', {
      method: 'DELETE',
      body: { currentPassword: currentPassword ?? null },
    })
    notification.success(t('settings.delete_account.delete_success'))
    authStore.logout()
    setTimeout(() => navigateTo('/login'), 2000)
  } catch {
    notification.error(t('settings.delete_account.delete_error'))
  }
}

const authStore = useAuthStore()
const appearanceStore = useAppearanceStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

const loading = ref(true)
// SSR/CSR 初期マークアップを一致させるためのマウントフラグ。
// SSR では authStore.user が未復元で userId が undefined になるため、
// マウント前は全コンテンツをローディング表示に統一して Hydration mismatch を防ぐ。
const isMounted = ref(false)
const userId = computed(() => authStore.user?.id)

const {
  profile,
  savingProfile,
  emailForm,
  submittingEmail,
  emailSent,
  canSubmitEmail,
  passwordForm,
  submittingPassword,
  redirecting,
  passwordError,
  canSubmitPassword,
  savingLocale,
  loadProfile,
  saveProfile,
  uploadAvatar,
  saveLocale,
  handleEmailChange,
  handlePasswordChange,
} = useAccountProfile()

const {
  sessions,
  credentials,
  totpSetup,
  backupCodes,
  showBackupCodesDialog,
  setting2fa,
  regenerating,
  renameDialog,
  newDeviceName,
  loginHistory,
  loginHistoryNextCursor,
  loginHistoryHasNext,
  loadingMoreHistory,
  loadSecurity,
  loadLoginHistory,
  handleSetup2fa,
  handleRegenerateBackupCodes,
  handleRevokeSession,
  handleRevokeAllSessions,
  handleDeleteCredential,
  openRenameDialog,
  handleRenameCredential,
} = useAccountSecurity()

const {
  oauthProviders,
  lineStatus,
  loadLinkedAccounts,
  handleUnlinkOAuth,
  handleUnlinkLine,
  handleLinkOAuth,
} = useAccountLinkedAccounts()

const {
  memberCards,
  selectedCard,
  memberCardActiveTab,
  loadMemberCards,
  handleSuspendCard,
  handleReactivateCard,
  handleSelectCard,
} = useAccountMemberCards()

const {
  socialProfiles,
  showSocialDialog,
  editingSocialProfile,
  socialForm,
  loadSocialProfiles,
  openCreateSocial,
  openEditSocial,
  saveSocial,
  handleDeleteSocial,
} = useAccountSocialProfile()

const {
  seals,
  scopeDefaults,
  regeneratingSeals,
  sealActiveTab,
  loadSeals,
  handleRegenerateSeals,
  handleSaveDefaults,
} = useAccountSeals()

const {
  gcalStatus,
  gcalSyncSettings,
  gcalSyncing,
  loadGcal,
  connectGoogle,
  disconnectGoogle,
  saveGcalSettings,
  manualGcalSync,
  toggleTeamSync,
  toggleOrgSync,
} = useAccountGcal()

onMounted(async () => {
  isMounted.value = true
  await Promise.allSettled([
    loadProfile(),
    loadSecurity(),
    loadLinkedAccounts(),
    loadLoginHistory(),
    loadMemberCards(),
    loadSocialProfiles(),
    loadSeals(userId.value),
    loadGcal(),
    appearanceStore.loadFromServer(),
    teamStore.fetchMyTeams(),
    orgStore.fetchMyOrganizations(),
  ])
  loading.value = false
})
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
            {{ $t('settings.password.redirecting') }}
          </p>
        </div>
      </Transition>
    </Teleport>

    <PageHeader :title="$t('settings.account.page_title')" />

    <PageLoading v-if="!isMounted || loading" />

    <div v-else class="fade-in space-y-8">
      <SettingsProfileSection
        v-model:profile="profile"
        :saving-profile="savingProfile"
        @save="saveProfile"
        @upload-avatar="uploadAvatar"
      />

      <SettingsEmailSection
        v-model:email-form="emailForm"
        :current-email="profile.email"
        :submitting-email="submittingEmail"
        :email-sent="emailSent"
        :can-submit-email="canSubmitEmail"
        @submit="handleEmailChange"
      />

      <SettingsPasswordSection
        v-model:password-form="passwordForm"
        :has-password="profile.hasPassword"
        :submitting-password="submittingPassword"
        :can-submit-password="canSubmitPassword"
        :password-error="passwordError"
        @submit="handlePasswordChange"
      />

      <SettingsLocaleSection
        v-model:profile="profile"
        :saving-locale="savingLocale"
        @save="saveLocale"
      />

      <SettingsAppearanceSection />

      <SectionCard :title="$t('settings.account.notification_settings')">
        <NotificationPreferences />
      </SectionCard>

      <SettingsSecuritySection
        v-model:show-backup-codes-dialog="showBackupCodesDialog"
        v-model:rename-dialog="renameDialog"
        v-model:new-device-name="newDeviceName"
        :totp-setup="totpSetup"
        :setting2fa="setting2fa"
        :regenerating="regenerating"
        :sessions="sessions"
        :credentials="credentials"
        :backup-codes="backupCodes"
        @setup2fa="handleSetup2fa"
        @regenerate-backup-codes="handleRegenerateBackupCodes"
        @revoke-session="handleRevokeSession"
        @revoke-all-sessions="handleRevokeAllSessions"
        @delete-credential="handleDeleteCredential"
        @open-rename-dialog="openRenameDialog"
        @rename-credential="handleRenameCredential"
      />

      <SettingsLinkedAccountsSection
        :oauth-providers="oauthProviders"
        :line-status="lineStatus"
        @unlink-o-auth="handleUnlinkOAuth"
        @unlink-line="handleUnlinkLine"
        @link-o-auth="handleLinkOAuth"
      />

      <SettingsMemberCardSection
        v-model:member-card-active-tab="memberCardActiveTab"
        :member-cards="memberCards"
        :selected-card="selectedCard"
        @select-card="handleSelectCard"
        @suspend-card="handleSuspendCard"
        @reactivate-card="handleReactivateCard"
      />

      <SettingsSocialProfileSection
        v-model:show-social-dialog="showSocialDialog"
        v-model:social-form="socialForm"
        :social-profiles="socialProfiles"
        :editing-social-profile="editingSocialProfile"
        @create-social="openCreateSocial"
        @edit-social="openEditSocial"
        @delete-social="handleDeleteSocial"
        @save-social="saveSocial"
      />

      <SettingsSealSection
        v-model:seal-active-tab="sealActiveTab"
        :seals="seals"
        :scope-defaults="scopeDefaults"
        :regenerating-seals="regeneratingSeals"
        :user-id="userId"
        @regenerate-seals="handleRegenerateSeals(userId)"
        @save-defaults="(defaults: ScopeDefault[]) => handleSaveDefaults(userId, defaults)"
      />

      <SettingsLoginHistorySection
        :login-history="loginHistory"
        :login-history-has-next="loginHistoryHasNext"
        :loading-more-history="loadingMoreHistory"
        :login-history-next-cursor="loginHistoryNextCursor"
        @load-more="loadLoginHistory"
      />

      <SettingsGcalSection
        v-model:gcal-sync-settings="gcalSyncSettings"
        :gcal-status="gcalStatus"
        :gcal-syncing="gcalSyncing"
        :teams="teamStore.myTeams"
        :organizations="orgStore.myOrganizations"
        @connect="connectGoogle"
        @disconnect="disconnectGoogle"
        @save-settings="saveGcalSettings"
        @manual-sync="manualGcalSync"
        @toggle-team-sync="toggleTeamSync"
        @toggle-org-sync="toggleOrgSync"
      />

      <SettingsDataExportSection />

      <SettingsDeleteAccountSection @show-deletion-preview="showDeletionPreviewDialog = true" />

      <SettingsDeletionPreviewDialog
        v-model:visible="showDeletionPreviewDialog"
        :has-password="profile.hasPassword"
        @confirmed="handleDeleteAccount"
      />
    </div>
  </div>
</template>
