<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import type {
  OAuthProviderResponse,
  UserLineStatusResponse,
  LoginHistoryResponse,
} from '~/types/user-settings'
import type { MemberCard } from '~/types/member-card'
import type { ElectronicSeal, ScopeDefault } from '~/types/seal'
import type { SocialProfile, CreateSocialProfileRequest } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const appearanceStore = useAppearanceStore()
const { changeLocale } = useLocale()
const {
  getProfile,
  updateProfile,
  changeEmail,
  changePassword,
  setupPassword,
  getLoginHistory,
  getOAuthProviders,
  unlinkOAuthProvider,
  getLineStatus,
  unlinkLine,
} = useUserSettingsApi()
const {
  setup2fa,
  regenerateBackupCodes,
  getSessions,
  revokeSession,
  revokeAllSessions,
  getWebAuthnCredentials,
  deleteWebAuthnCredential,
  updateWebAuthnCredential,
} = useAuthApi()
const memberCardApi = useMemberCardApi()
const sealApi = useSealApi()
const socialApi = useSocialProfileApi()
const gcalApi = useGoogleCalendarApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

// ── ローディング ──────────────────────────────
const loading = ref(true)

// ── プロフィール ──────────────────────────────
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

// ── メール変更 ────────────────────────────────
const emailForm = ref({ newEmail: '', currentPassword: '' })
const submittingEmail = ref(false)
const emailSent = ref(false)

// ── パスワード変更 ────────────────────────────
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const submittingPassword = ref(false)

// ── 言語・タイムゾーン ────────────────────────
const savingLocale = ref(false)
const localeOptions = [
  { code: 'ja', name: '日本語' },
  { code: 'en', name: 'English' },
  { code: 'zh', name: '中文（简体）' },
  { code: 'ko', name: '한국어' },
  { code: 'es', name: 'Español' },
  { code: 'de', name: 'Deutsch' },
]
const timezoneOptions = [
  { label: 'Asia/Tokyo (JST)', value: 'Asia/Tokyo' },
  { label: 'Asia/Shanghai (CST)', value: 'Asia/Shanghai' },
  { label: 'Asia/Seoul (KST)', value: 'Asia/Seoul' },
  { label: 'Asia/Singapore (SGT)', value: 'Asia/Singapore' },
  { label: 'Asia/Bangkok (ICT)', value: 'Asia/Bangkok' },
  { label: 'Europe/London (GMT/BST)', value: 'Europe/London' },
  { label: 'Europe/Paris (CET/CEST)', value: 'Europe/Paris' },
  { label: 'Europe/Berlin (CET/CEST)', value: 'Europe/Berlin' },
  { label: 'America/New_York (EST/EDT)', value: 'America/New_York' },
  { label: 'America/Chicago (CST/CDT)', value: 'America/Chicago' },
  { label: 'America/Los_Angeles (PST/PDT)', value: 'America/Los_Angeles' },
  { label: 'America/Sao_Paulo (BRT)', value: 'America/Sao_Paulo' },
  { label: 'Australia/Sydney (AEST/AEDT)', value: 'Australia/Sydney' },
  { label: 'Pacific/Auckland (NZST/NZDT)', value: 'Pacific/Auckland' },
  { label: 'UTC', value: 'UTC' },
]

// ── セキュリティ ──────────────────────────────
const sessions = ref<AuthSessionResponse[]>([])
const credentials = ref<WebAuthnCredentialResponse[]>([])
const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
const backupCodes = ref<string[]>([])
const showBackupCodesDialog = ref(false)
const setting2fa = ref(false)
const regenerating = ref(false)
const renameDialog = ref(false)
const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
const newDeviceName = ref('')

// ── アカウント連携 ────────────────────────────
const oauthProviders = ref<OAuthProviderResponse[]>([])
const lineStatus = ref<UserLineStatusResponse | null>(null)

// ── ログイン履歴 ──────────────────────────────
const loginHistory = ref<LoginHistoryResponse[]>([])
const loginHistoryNextCursor = ref<string | null>(null)
const loginHistoryHasNext = ref(false)
const loadingMoreHistory = ref(false)

// ── QR会員証 ──────────────────────────────────
const memberCards = ref<MemberCard[]>([])
const selectedCard = ref<MemberCard | null>(null)
const memberCardActiveTab = ref('0')

// ── ソーシャルプロフィール ────────────────────
const socialProfiles = ref<SocialProfile[]>([])
const showSocialDialog = ref(false)
const editingSocialProfile = ref<SocialProfile | null>(null)
const socialForm = ref<CreateSocialProfileRequest>({ handle: '', displayName: '', bio: '' })

// ── 電子印鑑 ──────────────────────────────────
const seals = ref<ElectronicSeal[]>([])
const scopeDefaults = ref<ScopeDefault[]>([])
const regeneratingSeals = ref(false)
const sealActiveTab = ref('0')

// ── Google Calendar ───────────────────────────
interface GcalStatus {
  isConnected: boolean
  email: string | null
  lastSyncedAt: string | null
}
interface GcalSync {
  personalSync: boolean
  teamSyncIds: number[]
  orgSyncIds: number[]
}
const gcalStatus = ref<GcalStatus | null>(null)
const gcalSyncSettings = ref<GcalSync | null>(null)
const gcalSyncing = ref(false)

// ── アカウント削除 ────────────────────────────
const showDeleteDialog = ref(false)

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────
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
    return (
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
    emailForm.value.newEmail &&
    emailForm.value.currentPassword &&
    emailForm.value.newEmail !== profile.value.email,
)
const userId = computed(() => authStore.user?.id)

// ─────────────────────────────────────────────
// データ取得
// ─────────────────────────────────────────────
onMounted(async () => {
  await Promise.allSettled([
    loadProfile(),
    loadSecurity(),
    loadLinkedAccounts(),
    loadLoginHistory(),
    loadMemberCards(),
    loadSocialProfiles(),
    loadSeals(),
    loadGcal(),
    appearanceStore.loadFromServer(),
    teamStore.fetchMyTeams(),
    orgStore.fetchMyOrganizations(),
  ])
  loading.value = false
})

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

async function loadSecurity() {
  try {
    const [sessRes, credRes] = await Promise.all([getSessions(), getWebAuthnCredentials()])
    sessions.value = sessRes.data
    credentials.value = credRes.data
  } catch {
    /* silent */
  }
}

async function loadLinkedAccounts() {
  try {
    const [oauthRes, lineRes] = await Promise.all([getOAuthProviders(), getLineStatus()])
    oauthProviders.value = oauthRes.data
    lineStatus.value = lineRes.data
  } catch {
    /* silent */
  }
}

async function loadLoginHistory(cursor?: string) {
  if (cursor) loadingMoreHistory.value = true
  try {
    const res = await getLoginHistory(cursor, 10)
    if (cursor) loginHistory.value.push(...res.data)
    else loginHistory.value = res.data
    loginHistoryNextCursor.value = res.meta.nextCursor
    loginHistoryHasNext.value = res.meta.hasNext
  } catch {
    /* silent */
  } finally {
    loadingMoreHistory.value = false
  }
}

async function loadMemberCards() {
  try {
    memberCards.value = await memberCardApi.listMy()
  } catch {
    /* silent */
  }
}

async function loadSocialProfiles() {
  try {
    const p = await socialApi.getMyProfile()
    socialProfiles.value = p ? [p] : []
  } catch {
    /* silent */
  }
}

async function loadSeals() {
  if (!userId.value) return
  try {
    const [sealsRes, defaultsRes] = await Promise.all([
      sealApi.getSeals(userId.value),
      sealApi.getScopeDefaults(userId.value),
    ])
    seals.value = sealsRes
    scopeDefaults.value = defaultsRes
  } catch {
    /* silent */
  }
}

async function loadGcal() {
  try {
    const [statusRes, settingsRes] = await Promise.all([
      gcalApi.getConnectionStatus(),
      gcalApi.getPersonalSync(),
    ])
    gcalStatus.value = statusRes.data as GcalStatus
    gcalSyncSettings.value = settingsRes as unknown as GcalSync
  } catch {
    /* silent */
  }
}

// ─────────────────────────────────────────────
// Actions - プロフィール
// ─────────────────────────────────────────────
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

// ─────────────────────────────────────────────
// Actions - メール・パスワード
// ─────────────────────────────────────────────
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

// ─────────────────────────────────────────────
// Actions - セキュリティ
// ─────────────────────────────────────────────
async function handleSetup2fa() {
  setting2fa.value = true
  try {
    totpSetup.value = (await setup2fa()).data
  } catch {
    notification.error('2FAセットアップの開始に失敗しました')
  } finally {
    setting2fa.value = false
  }
}

async function handleRegenerateBackupCodes() {
  regenerating.value = true
  try {
    backupCodes.value = (await regenerateBackupCodes()).data.backupCodes
    showBackupCodesDialog.value = true
    notification.success('バックアップコードを再生成しました')
  } catch {
    notification.error('バックアップコードの再生成に失敗しました')
  } finally {
    regenerating.value = false
  }
}

async function handleRevokeSession(id: number) {
  try {
    await revokeSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    notification.success('セッションを無効化しました')
  } catch {
    notification.error('セッションの無効化に失敗しました')
  }
}

async function handleRevokeAllSessions() {
  try {
    await revokeAllSessions()
    sessions.value = []
    notification.success('全デバイスからログアウトしました')
  } catch {
    notification.error('全デバイスログアウトに失敗しました')
  }
}

async function handleDeleteCredential(id: number) {
  try {
    await deleteWebAuthnCredential(id)
    credentials.value = credentials.value.filter((c) => c.id !== id)
    notification.success('セキュリティキーを削除しました')
  } catch {
    notification.error('セキュリティキーの削除に失敗しました')
  }
}

function openRenameDialog(cred: WebAuthnCredentialResponse) {
  renameTarget.value = cred
  newDeviceName.value = cred.deviceName
  renameDialog.value = true
}

async function handleRenameCredential() {
  if (!renameTarget.value) return
  try {
    const res = await updateWebAuthnCredential(renameTarget.value.id, {
      deviceName: newDeviceName.value,
    })
    const idx = credentials.value.findIndex((c) => c.id === renameTarget.value!.id)
    if (idx !== -1) credentials.value[idx] = res.data
    renameDialog.value = false
    notification.success('デバイス名を更新しました')
  } catch {
    notification.error('デバイス名の更新に失敗しました')
  }
}

// ─────────────────────────────────────────────
// Actions - アカウント連携
// ─────────────────────────────────────────────
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

// ─────────────────────────────────────────────
// Actions - 会員証
// ─────────────────────────────────────────────
async function handleSuspendCard(id: number) {
  try {
    await memberCardApi.suspend(id)
    notification.success('会員証を一時停止しました')
    await loadMemberCards()
  } catch {
    notification.error('一時停止に失敗しました')
  }
}
async function handleReactivateCard(id: number) {
  try {
    await memberCardApi.reactivate(id)
    notification.success('会員証を再開しました')
    await loadMemberCards()
  } catch {
    notification.error('再開に失敗しました')
  }
}
function handleSelectCard(card: MemberCard) {
  selectedCard.value = card
  memberCardActiveTab.value = '1'
}

// ─────────────────────────────────────────────
// Actions - ソーシャルプロフィール
// ─────────────────────────────────────────────
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

// ─────────────────────────────────────────────
// Actions - 電子印鑑
// ─────────────────────────────────────────────
async function handleRegenerateSeals() {
  if (!userId.value) return
  regeneratingSeals.value = true
  try {
    seals.value = await sealApi.regenerateSeals(userId.value)
    notification.success('印鑑を再生成しました')
  } catch {
    notification.error('再生成に失敗しました（1時間に3回まで）')
  } finally {
    regeneratingSeals.value = false
  }
}
async function handleSaveDefaults(defaults: ScopeDefault[]) {
  if (!userId.value) return
  try {
    scopeDefaults.value = await sealApi.updateScopeDefaults(
      userId.value,
      defaults.map((d) => ({ scopeType: d.scopeType, scopeId: d.scopeId, variant: d.variant })),
    )
    notification.success('デフォルト設定を保存しました')
  } catch {
    notification.error('設定の保存に失敗しました')
  }
}

// ─────────────────────────────────────────────
// Actions - Google Calendar
// ─────────────────────────────────────────────
async function connectGoogle() {
  try {
    const res = await gcalApi.connect()
    window.location.href = (res.data as { authUrl: string }).authUrl
  } catch {
    notification.error('接続に失敗しました')
  }
}
async function disconnectGoogle() {
  if (!confirm('Google Calendar連携を解除しますか？')) return
  try {
    await gcalApi.disconnect()
    notification.success('連携を解除しました')
    await loadGcal()
  } catch {
    notification.error('解除に失敗しました')
  }
}
async function saveGcalSettings() {
  if (!gcalSyncSettings.value) return
  try {
    await gcalApi.updatePersonalSync(gcalSyncSettings.value as unknown as Record<string, unknown>)
    notification.success('同期設定を保存しました')
  } catch {
    notification.error('保存に失敗しました')
  }
}
async function manualGcalSync() {
  gcalSyncing.value = true
  try {
    await gcalApi.manualSync()
    notification.success('同期を実行しました')
    await loadGcal()
  } catch {
    notification.error('同期に失敗しました')
  } finally {
    gcalSyncing.value = false
  }
}
function toggleTeamSync(teamId: number) {
  if (!gcalSyncSettings.value) return
  const idx = gcalSyncSettings.value.teamSyncIds.indexOf(teamId)
  if (idx >= 0) gcalSyncSettings.value.teamSyncIds.splice(idx, 1)
  else gcalSyncSettings.value.teamSyncIds.push(teamId)
}
function toggleOrgSync(orgId: number) {
  if (!gcalSyncSettings.value) return
  const idx = gcalSyncSettings.value.orgSyncIds.indexOf(orgId)
  if (idx >= 0) gcalSyncSettings.value.orgSyncIds.splice(idx, 1)
  else gcalSyncSettings.value.orgSyncIds.push(orgId)
}

// ─────────────────────────────────────────────
// Actions - アカウント削除
// ─────────────────────────────────────────────
async function deleteAccount() {
  try {
    await api('/api/v1/users/me', { method: 'DELETE' })
    authStore.logout()
    navigateTo('/login')
  } catch {
    notification.error('アカウントの削除に失敗しました')
  }
}

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────
function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
}
function eventLabel(eventType: string) {
  return (
    (
      {
        LOGIN_SUCCESS: 'ログイン成功',
        LOGIN_FAILURE: 'ログイン失敗',
        LOGOUT: 'ログアウト',
        TOKEN_REFRESH: 'トークン更新',
        PASSWORD_CHANGE: 'パスワード変更',
        MFA_VERIFY: '2FA認証',
      } as Record<string, string>
    )[eventType] || eventType
  )
}
function eventSeverity(eventType: string) {
  if (eventType === 'LOGIN_FAILURE') return 'danger'
  if (eventType === 'LOGOUT') return 'warn'
  return 'success'
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">アカウント設定</h1>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <!-- ══ プロフィール情報 ══════════════════════════ -->
      <SectionCard title="プロフィール情報">
        <div class="space-y-4">
          <div class="flex items-center gap-4">
            <div>
              <img
                v-if="profile.avatarUrl"
                :src="profile.avatarUrl"
                alt="アバター"
                class="h-20 w-20 rounded-full object-cover"
              />
              <div
                v-else
                class="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 text-2xl text-primary"
              >
                <i class="pi pi-user" />
              </div>
            </div>
            <div>
              <label class="cursor-pointer">
                <input type="file" accept="image/*" class="hidden" @change="uploadAvatar" />
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
            <label class="mb-1 block text-sm font-medium">電話番号</label>
            <InputText v-model="profile.phoneNumber" class="w-full" placeholder="090-0000-0000" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">郵便番号</label>
            <InputText v-model="profile.postalCode" class="w-full" placeholder="000-0000" />
          </div>
          <div
            class="flex items-center justify-between rounded-lg border border-surface-200 p-3 dark:border-surface-700"
          >
            <div>
              <p class="text-sm font-medium">ユーザー検索への表示</p>
              <p class="text-xs text-surface-500">
                オンにすると他のユーザーから検索で見つけられます
              </p>
            </div>
            <ToggleSwitch v-model="profile.isSearchable" />
          </div>
          <div class="flex justify-end">
            <Button label="保存" icon="pi pi-check" :loading="savingProfile" @click="saveProfile" />
          </div>
        </div>
      </SectionCard>

      <!-- ══ メールアドレス変更 ═════════════════════════ -->
      <SectionCard title="メールアドレス変更">
        <template v-if="!emailSent">
          <div class="space-y-4">
            <div>
              <label class="mb-1 block text-sm font-medium">現在のメールアドレス</label>
              <InputText :model-value="profile.email" class="w-full" disabled />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">新しいメールアドレス</label>
              <InputText
                v-model="emailForm.newEmail"
                type="email"
                class="w-full"
                placeholder="new@example.com"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
              <Password
                v-model="emailForm.currentPassword"
                :feedback="false"
                toggle-mask
                class="w-full"
                input-class="w-full"
              />
            </div>
            <div class="flex justify-end">
              <Button
                label="確認メールを送信"
                icon="pi pi-envelope"
                :loading="submittingEmail"
                :disabled="!canSubmitEmail"
                @click="handleEmailChange"
              />
            </div>
          </div>
        </template>
        <template v-else>
          <div class="py-6 text-center">
            <i class="pi pi-check-circle mb-3 text-5xl text-green-500" />
            <p class="mb-1 font-semibold">確認メールを送信しました</p>
            <p class="text-sm text-surface-500">
              {{
                emailForm.newEmail
              }}
              に確認メールを送信しました。リンクをクリックして変更を完了してください。
            </p>
          </div>
        </template>
      </SectionCard>

      <!-- ══ パスワード変更 ═════════════════════════════ -->
      <SectionCard :title="profile.hasPassword ? 'パスワード変更' : 'パスワード設定'">
        <div class="space-y-4">
          <p v-if="!profile.hasPassword" class="text-sm text-surface-500">
            現在パスワードが設定されていません。パスワードを設定することでメール・パスワードでもログインできるようになります。
          </p>
          <div v-if="profile.hasPassword">
            <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
            <Password
              v-model="passwordForm.currentPassword"
              :feedback="false"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">新しいパスワード</label>
            <Password
              v-model="passwordForm.newPassword"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">新しいパスワード（確認）</label>
            <Password
              v-model="passwordForm.confirmPassword"
              :feedback="false"
              toggle-mask
              class="w-full"
              input-class="w-full"
            />
          </div>
          <p v-if="passwordError" class="text-sm text-red-500">{{ passwordError }}</p>
          <div class="flex justify-end">
            <Button
              :label="profile.hasPassword ? 'パスワードを変更' : 'パスワードを設定'"
              icon="pi pi-lock"
              :loading="submittingPassword"
              :disabled="!canSubmitPassword"
              @click="handlePasswordChange"
            />
          </div>
        </div>
      </SectionCard>

      <!-- ══ 言語・タイムゾーン ═════════════════════════ -->
      <SectionCard title="言語・タイムゾーン">
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">表示言語</label>
            <Select
              v-model="profile.locale"
              :options="localeOptions"
              option-label="name"
              option-value="code"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">タイムゾーン</label>
            <Select
              v-model="profile.timezone"
              :options="timezoneOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div class="flex justify-end">
            <Button label="保存" icon="pi pi-check" :loading="savingLocale" @click="saveLocale" />
          </div>
        </div>
      </SectionCard>

      <!-- ══ 外観 ════════════════════════════════════════ -->
      <SectionCard title="外観">
        <div class="space-y-6">
          <ThemeSelector />
          <Divider />
          <BackgroundColorPicker />
          <Divider />
          <div class="flex items-center justify-between">
            <div>
              <label class="text-sm font-medium">チャットプレビュー非表示</label>
              <p class="text-xs text-surface-500">通知バナーでチャットの内容を表示しない</p>
            </div>
            <ToggleSwitch
              :model-value="appearanceStore.hideChatPreview"
              @update:model-value="(val: boolean) => appearanceStore.setHideChatPreview(val)"
            />
          </div>
          <div class="flex justify-end">
            <Button
              label="外観を保存"
              icon="pi pi-check"
              @click="
                appearanceStore
                  .syncWithServer()
                  .then(() => notification.success('外観設定を保存しました'))
              "
            />
          </div>
        </div>
      </SectionCard>

      <!-- ══ 通知 ════════════════════════════════════════ -->
      <SectionCard title="通知設定">
        <NotificationPreferences />
      </SectionCard>

      <!-- ══ セキュリティ ═══════════════════════════════ -->
      <SectionCard title="二要素認証（2FA）">
        <div v-if="!totpSetup" class="space-y-4">
          <p class="text-sm text-surface-500">
            認証アプリ（Google Authenticatorなど）を使用して、アカウントのセキュリティを強化します。
          </p>
          <div class="flex flex-wrap gap-2">
            <Button
              label="2FAをセットアップ"
              icon="pi pi-shield"
              :loading="setting2fa"
              @click="handleSetup2fa"
            />
            <Button
              label="バックアップコード再生成"
              icon="pi pi-refresh"
              severity="secondary"
              :loading="regenerating"
              @click="handleRegenerateBackupCodes"
            />
          </div>
        </div>
        <div v-else class="space-y-4">
          <p class="text-sm text-surface-500">認証アプリでQRコードをスキャンしてください。</p>
          <div class="flex justify-center">
            <img :src="totpSetup.qrCodeUrl" alt="TOTP QRコード" class="h-48 w-48" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">シークレットキー</label>
            <code class="block rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-700">{{
              totpSetup.secret
            }}</code>
          </div>
        </div>
      </SectionCard>

      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">アクティブセッション</h2>
          <Button
            v-if="sessions.length > 0"
            label="全てログアウト"
            icon="pi pi-sign-out"
            severity="danger"
            text
            size="small"
            @click="handleRevokeAllSessions"
          />
        </div>
        <div v-if="sessions.length === 0" class="py-4 text-center text-surface-400">
          セッション情報がありません
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
          >
            <div>
              <p class="text-sm font-medium">
                {{ session.userAgent || '不明なデバイス' }}
                <Tag v-if="session.isCurrent" value="現在" severity="success" class="ml-2" />
              </p>
              <p class="text-xs text-surface-500">
                IP: {{ session.ipAddress || '-' }} / {{ formatDate(session.createdAt) }}
              </p>
            </div>
            <Button
              v-if="!session.isCurrent"
              icon="pi pi-times"
              severity="danger"
              text
              rounded
              size="small"
              @click="handleRevokeSession(session.id)"
            />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="セキュリティキー（WebAuthn）">
        <p class="mb-4 text-sm text-surface-500">
          FIDO2/WebAuthn対応のセキュリティキーや生体認証を登録できます。
        </p>
        <div v-if="credentials.length === 0" class="py-4 text-center text-surface-400">
          登録されたセキュリティキーはありません
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="cred in credentials"
            :key="cred.id"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
          >
            <div>
              <p class="text-sm font-medium">
                <i class="pi pi-key mr-1" />{{ cred.deviceName || 'セキュリティキー' }}
              </p>
              <p class="text-xs text-surface-500">
                最終使用: {{ formatDate(cred.lastUsedAt) }} / 登録: {{ formatDate(cred.createdAt) }}
              </p>
            </div>
            <div class="flex gap-1">
              <Button
                icon="pi pi-pencil"
                severity="secondary"
                text
                rounded
                size="small"
                @click="openRenameDialog(cred)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                rounded
                size="small"
                @click="handleDeleteCredential(cred.id)"
              />
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- ══ アカウント連携 ══════════════════════════════ -->
      <SectionCard title="OAuth連携">
        <div v-if="oauthProviders.length === 0" class="py-4 text-center text-surface-400">
          連携されたアカウントはありません
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="provider in oauthProviders"
            :key="provider.provider"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-4 dark:border-surface-700"
          >
            <div class="flex items-center gap-3">
              <i :class="providerIcon(provider.provider)" class="text-xl" />
              <div>
                <p class="font-medium">{{ providerLabel(provider.provider) }}</p>
                <p class="text-sm text-surface-500">{{ provider.providerEmail }}</p>
                <p class="text-xs text-surface-400">
                  連携日: {{ formatDate(provider.connectedAt) }}
                </p>
              </div>
            </div>
            <Button
              label="解除"
              severity="danger"
              text
              size="small"
              @click="handleUnlinkOAuth(provider.provider)"
            />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="LINE連携">
        <div v-if="lineStatus?.isLinked" class="space-y-4">
          <div class="flex items-center gap-4">
            <img
              v-if="lineStatus.pictureUrl"
              :src="lineStatus.pictureUrl"
              alt="LINEアイコン"
              class="h-12 w-12 rounded-full"
            />
            <div
              v-else
              class="flex h-12 w-12 items-center justify-center rounded-full bg-green-100 text-green-600"
            >
              <i class="pi pi-comment text-xl" />
            </div>
            <div>
              <p class="font-medium">{{ lineStatus.displayName || 'LINE ユーザー' }}</p>
              <p class="text-xs text-surface-400">連携日: {{ formatDate(lineStatus.linkedAt) }}</p>
            </div>
          </div>
          <div class="flex justify-end">
            <Button
              label="LINE連携を解除"
              severity="danger"
              outlined
              size="small"
              @click="handleUnlinkLine"
            />
          </div>
        </div>
        <div v-else class="py-4 text-center">
          <p class="mb-2 text-surface-400">LINEアカウントは連携されていません</p>
          <p class="text-sm text-surface-500">LINE連携はLINEアプリから行ってください</p>
        </div>
      </SectionCard>

      <!-- ══ QR会員証 ════════════════════════════════════ -->
      <SectionCard title="QR会員証">
        <Tabs v-model:value="memberCardActiveTab">
          <TabList>
            <Tab value="0">会員証一覧</Tab>
            <Tab value="1" :disabled="!selectedCard">チェックイン履歴</Tab>
          </TabList>
          <TabPanels>
            <TabPanel value="0">
              <MemberCardList
                :cards="memberCards"
                @select="handleSelectCard"
                @suspend="handleSuspendCard"
                @reactivate="handleReactivateCard"
              />
            </TabPanel>
            <TabPanel value="1">
              <CheckinHistory v-if="selectedCard" :card-id="selectedCard.id" />
            </TabPanel>
          </TabPanels>
        </Tabs>
      </SectionCard>

      <!-- ══ ソーシャルプロフィール ═════════════════════ -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">ソーシャルプロフィール</h2>
          <Button
            v-if="socialProfiles.length < 3"
            label="新規作成"
            icon="pi pi-plus"
            size="small"
            @click="openCreateSocial"
          />
        </div>
        <p class="mb-4 text-sm text-surface-500">
          最大3つのプロフィールを作成できます（{{ socialProfiles.length }}/3）
        </p>
        <div class="space-y-4">
          <SocialProfileCard
            v-for="p in socialProfiles"
            :key="p.id"
            :profile="p"
            :show-actions="true"
            @edit="openEditSocial"
            @delete="handleDeleteSocial"
          />
          <div v-if="socialProfiles.length === 0" class="py-8 text-center text-surface-500">
            <i class="pi pi-user-plus mb-2 text-4xl" />
            <p>まだプロフィールがありません</p>
          </div>
        </div>
      </SectionCard>

      <!-- ══ 電子印鑑 ════════════════════════════════════ -->
      <SectionCard title="電子印鑑">
        <Tabs v-model:value="sealActiveTab">
          <TabList>
            <Tab value="0">印鑑プレビュー</Tab>
            <Tab value="1">デフォルト設定</Tab>
            <Tab value="2">押印履歴</Tab>
          </TabList>
          <TabPanels>
            <TabPanel value="0">
              <div class="space-y-4">
                <SealPreview :seals="seals" />
                <div class="flex justify-center">
                  <Button
                    label="印鑑を再生成"
                    icon="pi pi-refresh"
                    severity="secondary"
                    :loading="regeneratingSeals"
                    @click="handleRegenerateSeals"
                  />
                </div>
                <p class="text-center text-xs text-surface-500">
                  印鑑は登録姓名から自動生成されます（1時間に3回まで）
                </p>
              </div>
            </TabPanel>
            <TabPanel value="1">
              <SealScopeDefaults :defaults="scopeDefaults" @save="handleSaveDefaults" />
            </TabPanel>
            <TabPanel value="2">
              <StampLog v-if="userId" :user-id="userId" />
            </TabPanel>
          </TabPanels>
        </Tabs>
      </SectionCard>

      <!-- ══ ログイン履歴 ════════════════════════════════ -->
      <SectionCard title="ログイン履歴">
        <div v-if="loginHistory.length === 0" class="py-8 text-center text-surface-400">
          <i class="pi pi-history mb-2 text-4xl" />
          <p>ログイン履歴がありません</p>
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="item in loginHistory"
            :key="item.id"
            class="rounded-lg border border-surface-100 p-3 dark:border-surface-700"
          >
            <div class="flex items-start justify-between">
              <div>
                <div class="flex items-center gap-2">
                  <Tag
                    :value="eventLabel(item.eventType)"
                    :severity="eventSeverity(item.eventType)"
                  />
                  <span v-if="item.method" class="text-xs text-surface-500">{{ item.method }}</span>
                </div>
                <p class="mt-1 text-sm text-surface-500">
                  <i class="pi pi-globe mr-1" />{{ item.ipAddress || '-' }}
                </p>
                <p class="mt-1 text-xs text-surface-400 line-clamp-1">
                  {{ item.userAgent || '-' }}
                </p>
              </div>
              <span class="text-xs text-surface-400">{{ formatDate(item.createdAt) }}</span>
            </div>
          </div>
          <div v-if="loginHistoryHasNext" class="flex justify-center pt-2">
            <Button
              label="もっと読む"
              text
              :loading="loadingMoreHistory"
              @click="loadLoginHistory(loginHistoryNextCursor ?? undefined)"
            />
          </div>
        </div>
      </SectionCard>

      <!-- ══ Google Calendar ════════════════════════════ -->
      <SectionCard title="Google Calendar 連携">
        <div v-if="gcalStatus?.isConnected" class="space-y-4">
          <div class="flex items-center gap-3">
            <div
              class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
            >
              <i class="pi pi-check text-green-600" />
            </div>
            <div>
              <p class="font-medium text-green-700 dark:text-green-400">接続中</p>
              <p class="text-sm text-surface-500">{{ gcalStatus.email }}</p>
            </div>
          </div>
          <p class="text-xs text-surface-400">
            最終同期: {{ formatDate(gcalStatus.lastSyncedAt) }}
          </p>
          <div class="flex gap-2">
            <Button
              label="手動同期"
              icon="pi pi-refresh"
              size="small"
              outlined
              :loading="gcalSyncing"
              @click="manualGcalSync"
            />
            <Button
              label="連携解除"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              @click="disconnectGoogle"
            />
          </div>
          <div v-if="gcalSyncSettings">
            <Divider />
            <h3 class="mb-3 text-sm font-semibold">同期設定</h3>
            <div class="mb-3 flex items-center justify-between">
              <div>
                <p class="text-sm font-medium">個人カレンダー</p>
                <p class="text-xs text-surface-500">個人の予定をGoogleカレンダーに同期</p>
              </div>
              <ToggleSwitch v-model="gcalSyncSettings.personalSync" />
            </div>
            <div v-if="teamStore.myTeams.length > 0" class="mb-3">
              <p class="mb-2 text-xs font-medium text-surface-500">チームカレンダー</p>
              <div class="space-y-2">
                <div
                  v-for="team in teamStore.myTeams"
                  :key="team.id"
                  class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
                >
                  <span class="text-sm">{{ team.nickname1 || team.name }}</span>
                  <ToggleSwitch
                    :model-value="gcalSyncSettings.teamSyncIds.includes(team.id)"
                    @update:model-value="toggleTeamSync(team.id)"
                  />
                </div>
              </div>
            </div>
            <div v-if="orgStore.myOrganizations.length > 0" class="mb-3">
              <p class="mb-2 text-xs font-medium text-surface-500">組織カレンダー</p>
              <div class="space-y-2">
                <div
                  v-for="org in orgStore.myOrganizations"
                  :key="org.id"
                  class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
                >
                  <span class="text-sm">{{ org.nickname1 || org.name }}</span>
                  <ToggleSwitch
                    :model-value="gcalSyncSettings.orgSyncIds.includes(org.id)"
                    @update:model-value="toggleOrgSync(org.id)"
                  />
                </div>
              </div>
            </div>
            <Button label="設定を保存" icon="pi pi-check" size="small" @click="saveGcalSettings" />
          </div>
        </div>
        <div v-else>
          <p class="mb-3 text-sm text-surface-500">
            Googleアカウントと連携して、カレンダーを同期できます
          </p>
          <Button
            label="Googleアカウントに接続"
            icon="pi pi-external-link"
            @click="connectGoogle"
          />
        </div>
      </SectionCard>

      <!-- ══ アカウント削除 ══════════════════════════════ -->
      <div
        class="rounded-xl border border-red-200 bg-surface-0 p-6 dark:border-red-900 dark:bg-surface-800"
      >
        <h2 class="mb-2 text-lg font-semibold text-red-600">アカウント削除</h2>
        <p class="mb-4 text-sm text-surface-500">
          アカウントを削除すると、全てのデータが完全に削除されます。この操作は取り消せません。
        </p>
        <Button
          label="アカウントを削除"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="showDeleteDialog = true"
        />
      </div>
    </div>

    <!-- ══ ダイアログ群 ══════════════════════════════════ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      header="アカウント削除の確認"
      :modal="true"
      class="w-full max-w-md"
    >
      <p class="mb-4">
        本当にアカウントを削除しますか？全てのデータが完全に削除され、復元できません。
      </p>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="showDeleteDialog = false" />
        <Button label="削除する" severity="danger" @click="deleteAccount" />
      </div>
    </Dialog>

    <Dialog
      v-model:visible="showBackupCodesDialog"
      header="バックアップコード"
      :modal="true"
      class="w-full max-w-md"
    >
      <p class="mb-4 text-sm text-surface-500">
        以下のバックアップコードを安全な場所に保管してください。各コードは一度だけ使用できます。
      </p>
      <div class="grid grid-cols-2 gap-2">
        <code
          v-for="code in backupCodes"
          :key="code"
          class="rounded bg-surface-100 px-3 py-2 text-center text-sm dark:bg-surface-700"
          >{{ code }}</code
        >
      </div>
      <div class="mt-4 flex justify-end">
        <Button label="閉じる" @click="showBackupCodesDialog = false" />
      </div>
    </Dialog>

    <Dialog
      v-model:visible="renameDialog"
      header="デバイス名の変更"
      :modal="true"
      class="w-full max-w-sm"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">デバイス名</label>
          <InputText v-model="newDeviceName" class="w-full" />
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="renameDialog = false" />
          <Button label="保存" @click="handleRenameCredential" />
        </div>
      </div>
    </Dialog>

    <Dialog
      v-model:visible="showSocialDialog"
      :header="editingSocialProfile ? 'プロフィール編集' : 'プロフィール作成'"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">ハンドル名 *</label>
          <InputText
            v-model="socialForm.handle"
            class="w-full"
            placeholder="my_handle"
            :disabled="!!editingSocialProfile"
          />
          <p class="mt-1 text-xs text-surface-500">英数字とアンダースコアのみ（変更は30日に1回）</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">表示名 *</label>
          <InputText v-model="socialForm.displayName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">自己紹介</label>
          <Textarea v-model="socialForm.bio" class="w-full" rows="3" :maxlength="300" />
          <p class="mt-1 text-right text-xs text-surface-400">
            {{ socialForm.bio?.length ?? 0 }}/300
          </p>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showSocialDialog = false" />
        <Button
          :label="editingSocialProfile ? '更新' : '作成'"
          icon="pi pi-check"
          :disabled="!socialForm.handle || !socialForm.displayName"
          @click="saveSocial"
        />
      </template>
    </Dialog>
  </div>
</template>
