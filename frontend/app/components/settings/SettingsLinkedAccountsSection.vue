<script setup lang="ts">
import type { OAuthProviderResponse, UserLineStatusResponse } from '~/types/user-settings'

defineProps<{
  oauthProviders: OAuthProviderResponse[]
  lineStatus: UserLineStatusResponse | null
}>()

defineEmits<{
  unlinkOAuth: [provider: string]
  unlinkLine: []
}>()

const { formatDateTime } = useDatetime()

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
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
</script>

<template>
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
          @click="$emit('unlinkOAuth', provider.provider)"
        />
      </div>
    </div>
  </SectionCard>

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
          @click="$emit('unlinkLine')"
        />
      </div>
    </div>
    <div v-else class="py-4 text-center">
      <p class="mb-2 text-surface-400">{{ $t('settings.linked_accounts.line_not_linked') }}</p>
      <p class="text-sm text-surface-500">{{ $t('settings.linked_accounts.line_link_instruction') }}</p>
    </div>
  </SectionCard>
</template>
