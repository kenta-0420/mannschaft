<script setup lang="ts">
import type { ScopeDefault } from '~/types/seal'

definePageMeta({ middleware: 'auth' })

const showDeletionPreviewDialog = ref(false)
const api = useApi()
const notification = useNotification()

async function handleDeleteAccount() {
  try {
    await api('/api/v1/users/me', { method: 'DELETE' })
    authStore.logout()
    navigateTo('/login')
  } catch {
    notification.error('アカウントの削除に失敗しました')
  }
}

const authStore = useAuthStore()
const appearanceStore = useAppearanceStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

const loading = ref(true)
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
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">アカウント設定</h1>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <SettingsProfileSection
        :profile="profile"
        :saving-profile="savingProfile"
        @save="saveProfile"
        @upload-avatar="uploadAvatar"
      />

      <SettingsEmailSection
        :current-email="profile.email"
        :email-form="emailForm"
        :submitting-email="submittingEmail"
        :email-sent="emailSent"
        :can-submit-email="canSubmitEmail"
        @submit="handleEmailChange"
      />

      <SettingsPasswordSection
        :has-password="profile.hasPassword"
        :password-form="passwordForm"
        :submitting-password="submittingPassword"
        :can-submit-password="canSubmitPassword"
        :password-error="passwordError"
        @submit="handlePasswordChange"
      />

      <SettingsLocaleSection
        :profile="profile"
        :saving-locale="savingLocale"
        @save="saveLocale"
      />

      <SettingsAppearanceSection />

      <SectionCard title="通知設定">
        <NotificationPreferences />
      </SectionCard>

      <SettingsSecuritySection
        :totp-setup="totpSetup"
        :setting2fa="setting2fa"
        :regenerating="regenerating"
        :sessions="sessions"
        :credentials="credentials"
        :backup-codes="backupCodes"
        v-model:show-backup-codes-dialog="showBackupCodesDialog"
        v-model:rename-dialog="renameDialog"
        v-model:new-device-name="newDeviceName"
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
      />

      <SettingsMemberCardSection
        :member-cards="memberCards"
        :selected-card="selectedCard"
        v-model:member-card-active-tab="memberCardActiveTab"
        @select-card="handleSelectCard"
        @suspend-card="handleSuspendCard"
        @reactivate-card="handleReactivateCard"
      />

      <SettingsSocialProfileSection
        :social-profiles="socialProfiles"
        v-model:show-social-dialog="showSocialDialog"
        :editing-social-profile="editingSocialProfile"
        :social-form="socialForm"
        @create-social="openCreateSocial"
        @edit-social="openEditSocial"
        @delete-social="handleDeleteSocial"
        @save-social="saveSocial"
      />

      <SettingsSealSection
        :seals="seals"
        :scope-defaults="scopeDefaults"
        :regenerating-seals="regeneratingSeals"
        v-model:seal-active-tab="sealActiveTab"
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
        :gcal-status="gcalStatus"
        :gcal-sync-settings="gcalSyncSettings"
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
        @confirmed="handleDeleteAccount"
      />
    </div>
  </div>
</template>
