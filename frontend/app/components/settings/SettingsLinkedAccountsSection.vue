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

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('ja-JP')
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
  <SectionCard title="OAuth連携">
    <div v-if="oauthProviders.length === 0" class="py-4 text-center text-surface-400">
      連携されたアカウントはありません
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
          @click="$emit('unlinkOAuth', provider.provider)"
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
        >
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
          @click="$emit('unlinkLine')"
        />
      </div>
    </div>
    <div v-else class="py-4 text-center">
      <p class="mb-2 text-surface-400">LINEアカウントは連携されていません</p>
      <p class="text-sm text-surface-500">LINE連携はLINEアプリから行ってください</p>
    </div>
  </SectionCard>
</template>
