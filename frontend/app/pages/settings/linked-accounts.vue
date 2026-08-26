<script setup lang="ts">
import type { OAuthProviderResponse, UserLineStatusResponse } from '~/types/user-settings'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const notification = useNotification()
const { getOAuthProviders, unlinkOAuthProvider, getLineStatus, unlinkLine, getOAuthLinkAuthUrl } = useUserSettingsApi()
const { formatDateTime } = useDatetime()

const loading = ref(true)
const oauthProviders = ref<OAuthProviderResponse[]>([])
const lineStatus = ref<UserLineStatusResponse | null>(null)
const linkingGoogle = ref(false)
const showHelp = ref(false)
const calendarToggle = ref(false)

onMounted(async () => {
  await loadData()
  handleQueryParams()
})

function handleQueryParams() {
  const linked = route.query.linked
  const error = route.query.error

  if (linked) {
    if (linked === 'GOOGLE' && route.query.calendarConnected === 'true') {
      notification.success(t('settings.linked_accounts.toast.link_with_calendar_success'))
    } else {
      const provider = String(linked)
      notification.success(t('settings.linked_accounts.toast.link_success', { provider }))
    }
  } else if (error === 'oauth_denied') {
    notification.error(t('settings.linked_accounts.toast.oauth_denied'))
  } else if (error === 'already_taken') {
    notification.error(t('settings.linked_accounts.toast.taken_by_other', { provider: 'Google' }))
  } else if (error === 'invalid_state') {
    notification.error(t('settings.linked_accounts.toast.link_error'))
  }

  if (linked || error) {
    router.replace({ query: {} })
  }
}

async function loadData() {
  loading.value = true
  try {
    const [oauthRes, lineRes] = await Promise.all([getOAuthProviders(), getLineStatus()])
    oauthProviders.value = oauthRes.data
    lineStatus.value = lineRes.data
  } catch {
    notification.error(t('settings.linked_accounts.toast.load_error'))
  } finally {
    loading.value = false
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

async function handleLinkGoogle() {
  linkingGoogle.value = true
  try {
    const res = await getOAuthLinkAuthUrl('GOOGLE', calendarToggle.value)
    window.location.href = res.data.authUrl
  } catch {
    notification.error(t('settings.linked_accounts.toast.link_error'))
  } finally {
    linkingGoogle.value = false
  }
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    google: 'Google',
    apple: 'Apple',
    github: 'GitHub',
    microsoft: 'Microsoft',
    line: 'LINE',
  }
  return labels[provider.toLowerCase()] || provider
}

function providerIcon(provider: string) {
  const icons: Record<string, string> = {
    google: 'pi pi-google',
    apple: 'pi pi-apple',
    github: 'pi pi-github',
    microsoft: 'pi pi-microsoft',
  }
  return icons[provider.toLowerCase()] || 'pi pi-link'
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="$t('settings.linked_accounts.page_title')" help @help="showHelp = true" />

    <LinkedAccountsGuideModal v-model:visible="showHelp" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <!-- OAuth連携 -->
      <SectionCard :title="$t('settings.linked_accounts.oauth_section_title')">
        <div v-if="oauthProviders.length === 0" class="py-4 text-center text-surface-400">
          {{ $t('settings.linked_accounts.no_oauth') }}
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="provider in oauthProviders"
            :key="provider.provider"
            class="flex items-center justify-between rounded-lg border border-surface-100 p-4 dark:border-surface-600"
          >
            <div class="flex items-center gap-3">
              <i :class="providerIcon(provider.provider)" class="text-xl" />
              <div>
                <p class="font-medium" translate="no">{{ providerLabel(provider.provider) }}</p>
                <p class="text-sm text-surface-500">{{ provider.providerEmail }}</p>
                <p class="text-xs text-surface-400">
                  {{ $t('settings.linked_accounts.connected_at', { date: formatDate(provider.connectedAt) }) }}
                </p>
              </div>
            </div>
            <Button
              translate="no"
              :label="$t('settings.linked_accounts.unlink_button')"
              severity="danger"
              text
              size="small"
              @click="handleUnlinkOAuth(provider.provider)"
            />
          </div>
        </div>

        <!-- Google未連携時のボタン -->
        <div v-if="!oauthProviders.some(p => p.provider.toLowerCase() === 'google')" class="mt-3 flex flex-col gap-2">
          <div class="flex items-center gap-2 text-sm">
            <Checkbox v-model="calendarToggle" :binary="true" input-id="calendarToggle" />
            <label for="calendarToggle" class="cursor-pointer">{{ $t('settings.linked_accounts.calendar_sync_toggle') }}</label>
          </div>
          <Button
            :label="$t('settings.linked_accounts.google_link_button')"
            :loading="linkingGoogle"
            icon="pi pi-google"
            size="small"
            @click="handleLinkGoogle"
          />
        </div>
      </SectionCard>

      <!-- LINE連携 -->
      <SectionCard :title="$t('settings.linked_accounts.line_section_title')">
        <div v-if="lineStatus?.isLinked" class="space-y-4">
          <div class="flex items-center gap-4">
            <img
              v-if="lineStatus.pictureUrl"
              :src="lineStatus.pictureUrl"
              :alt="$t('settings.linked_accounts.line_icon_alt')"
              class="h-12 w-12 rounded-full"
            >
            <div
              v-else
              class="flex h-12 w-12 items-center justify-center rounded-full bg-green-100 text-green-600"
            >
              <i class="pi pi-comment text-xl" />
            </div>
            <div>
              <p class="font-medium" translate="no">{{ lineStatus.displayName || $t('settings.linked_accounts.line_default_user') }}</p>
              <p class="text-xs text-surface-400">{{ $t('settings.linked_accounts.line_linked_at', { date: formatDate(lineStatus.linkedAt) }) }}</p>
            </div>
          </div>
          <div class="flex justify-end">
            <Button
              translate="no"
              :label="$t('settings.linked_accounts.line_unlink_button')"
              severity="danger"
              outlined
              size="small"
              @click="handleUnlinkLine"
            />
          </div>
        </div>

        <div v-else class="py-4 text-center">
          <p class="mb-2 text-surface-400">{{ $t('settings.linked_accounts.line_not_linked') }}</p>
          <p class="text-sm text-surface-500">{{ $t('settings.linked_accounts.line_link_instruction') }}</p>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
