<script setup lang="ts">
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { AncestorOrganization } from '~/types/organization'

const props = defineProps<{
  org: OrgDetail
  isAdmin: boolean
  ancestors?: AncestorOrganization[]
}>()

const { t } = useI18n()
const { visibilityLabel } = useScopeLabels()

const ancestorList = computed<AncestorOrganization[]>(() => props.ancestors ?? [])
</script>

<template>
  <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">組織名</label>
        <p class="mt-1">
          {{ org.name }}
        </p>
      </div>
      <div v-if="org.nameKana">
        <label class="text-sm font-medium text-gray-500">組織名（カナ）</label>
        <p class="mt-1">
          {{ org.nameKana }}
        </p>
      </div>
      <div v-if="org.nickname1">
        <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
        <p class="mt-1">
          {{ org.nickname1 }}
        </p>
      </div>
      <div v-if="org.nickname2">
        <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
        <p class="mt-1">
          {{ org.nickname2 }}
        </p>
      </div>
    </div>
    <div class="space-y-4">
      <div v-if="ancestorList.length > 0">
        <label class="text-sm font-medium text-gray-500">{{ t('organization.parent_chain') }}</label>
        <ul class="mt-1 space-y-1" data-testid="org-info-parent-chain">
          <li
            v-for="ancestor in ancestorList"
            :key="ancestor.id"
            class="flex items-center gap-2"
          >
            <i class="pi pi-chevron-right text-xs text-surface-400" aria-hidden="true" />
            <span
              v-if="ancestor.hidden"
              class="inline-flex items-center gap-1 rounded bg-surface-200 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700 dark:text-surface-400"
            >
              <i class="pi pi-lock text-xs" />
              {{ t('organization.hidden_org') }}
            </span>
            <NuxtLink
              v-else
              :to="`/organizations/${ancestor.id}`"
              class="hover:text-primary hover:underline"
            >
              {{ ancestor.nickname1 || ancestor.name }}
            </NuxtLink>
          </li>
        </ul>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">所在地</label>
        <p class="mt-1">
          {{ [org.prefecture, org.city].filter(Boolean).join(' ') || '未設定' }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">公開設定</label>
        <p class="mt-1">
          {{ visibilityLabel[org.visibility] ?? org.visibility }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">メンバー数</label>
        <p class="mt-1">{{ org.memberCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">サポーター機能</label>
        <p class="mt-1">
          {{ org.supporterEnabled ? '有効' : '無効' }}
        </p>
      </div>
      <div v-if="org.description">
        <label class="text-sm font-medium text-gray-500">説明</label>
        <p class="mt-1 whitespace-pre-wrap">
          {{ org.description }}
        </p>
      </div>
    </div>
  </div>
  <OrgExtendedProfileDisplay
    :org-id="org.id"
    :is-admin-or-deputy="isAdmin"
  />
</template>
