<script setup lang="ts">
import type { OrgDetail } from '~/composables/useOrgDetail'

defineProps<{
  org: OrgDetail
  isAdmin: boolean
}>()

const { visibilityLabel } = useScopeLabels()
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
  <div v-if="isAdmin" class="mt-6">
    <Button label="設定を編集" icon="pi pi-pencil" outlined />
  </div>
</template>
