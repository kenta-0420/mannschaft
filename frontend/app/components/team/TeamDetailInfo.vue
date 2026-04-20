<script setup lang="ts">
interface Props {
  teamId: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  templateLabel: string
  prefecture: string | null
  city: string | null
  visibility: string
  visibilityLabel: string
  memberCount: number
  supporterEnabled: boolean
  description: string | null
  isAdmin: boolean
}

defineProps<Props>()
</script>

<template>
  <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">チーム名</label>
        <p class="mt-1">
          {{ name }}
        </p>
      </div>
      <div v-if="nameKana">
        <label class="text-sm font-medium text-gray-500">チーム名（カナ）</label>
        <p class="mt-1">
          {{ nameKana }}
        </p>
      </div>
      <div v-if="nickname1">
        <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
        <p class="mt-1">
          {{ nickname1 }}
        </p>
      </div>
      <div v-if="nickname2">
        <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
        <p class="mt-1">
          {{ nickname2 }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">ジャンル</label>
        <p class="mt-1">
          {{ templateLabel }}
        </p>
      </div>
    </div>
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">所在地</label>
        <p class="mt-1">
          {{ [prefecture, city].filter(Boolean).join(' ') || '未設定' }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">公開設定</label>
        <p class="mt-1">
          {{ visibilityLabel }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">メンバー数</label>
        <p class="mt-1">{{ memberCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">サポーター機能</label>
        <p class="mt-1">
          {{ supporterEnabled ? '有効' : '無効' }}
        </p>
      </div>
      <div v-if="description">
        <label class="text-sm font-medium text-gray-500">説明</label>
        <p class="mt-1 whitespace-pre-wrap">
          {{ description }}
        </p>
      </div>
    </div>
  </div>
  <TeamExtendedProfileDisplay
    :team-id="teamId"
    :is-admin-or-deputy="isAdmin"
  />
</template>
