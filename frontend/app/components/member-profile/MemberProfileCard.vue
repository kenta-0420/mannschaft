<script setup lang="ts">
import type { MemberProfile } from '~/types/member-profile'

const props = defineProps<{
  profile: MemberProfile
  editable?: boolean
  /** F08.10 §G.9: 指定時、userId を持つメンバー行に試合分析リンクを表示する */
  teamId?: string
}>()

const emit = defineEmits<{
  edit: [profile: MemberProfile]
  delete: [id: number]
}>()

const { t } = useI18n()

/**
 * バックエンドの customFieldValues は JSON 文字列（{"fieldId": "value"}）。
 * 不正な JSON でも画面を壊さないよう、パース失敗時は空オブジェクトにフォールバックする。
 */
const customFields = computed<Record<string, string>>(() => {
  if (!props.profile.customFieldValues) return {}
  try {
    const parsed = JSON.parse(props.profile.customFieldValues)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
})
</script>

<template>
  <Card class="w-full">
    <template #content>
      <div class="flex items-center gap-4">
        <div class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-xl text-primary">
          {{ profile.displayName.charAt(0) }}
        </div>
        <div class="flex-1">
          <div class="flex items-center gap-2">
            <p class="font-semibold">{{ profile.displayName }}</p>
            <Badge v-if="profile.memberNumber" :value="profile.memberNumber" severity="secondary" />
          </div>
          <p v-if="profile.position" class="text-sm text-surface-500">{{ profile.position }}</p>
          <p v-if="profile.bio" class="mt-1 text-sm text-surface-600 dark:text-surface-400">{{ profile.bio }}</p>
          <div v-if="Object.keys(customFields).length > 0" class="mt-2 flex flex-wrap gap-2">
            <span
              v-for="(value, key) in customFields"
              :key="key"
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-700"
            >
              {{ key }}: {{ value }}
            </span>
          </div>
        </div>
        <div class="flex items-center gap-1">
          <!-- F08.10 §G.9: メンバー別 試合分析への導線（userId を持つ行のみ） -->
          <NuxtLink
            v-if="teamId && profile.userId"
            :to="`/teams/${teamId}/members/${profile.userId}/match-analytics`"
            :aria-label="t('match.analytics.member_title')"
          >
            <Button icon="pi pi-chart-bar" size="small" text severity="secondary" />
          </NuxtLink>
          <template v-if="editable">
            <Button icon="pi pi-pencil" size="small" text severity="secondary" @click="emit('edit', profile)" />
            <Button icon="pi pi-trash" size="small" text severity="danger" @click="emit('delete', profile.id)" />
          </template>
        </div>
      </div>
    </template>
  </Card>
</template>
