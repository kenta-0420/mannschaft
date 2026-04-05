<script setup lang="ts">
interface Props {
  teamName: string
  template: string
  templateLabel: string
  roleName: string | null
  isAdmin: boolean
  memberCount: number
  supporterEnabled: boolean
  supporterCount?: number
  followStatus: 'NONE' | 'PENDING' | 'APPROVED'
  followLoading: boolean
}

defineProps<Props>()

defineEmits<{
  back: []
  applySupporter: []
  cancelSupporter: []
  showCancelConfirm: []
  showLeaveConfirm: []
}>()
</script>

<template>
  <div class="mb-6 flex items-center justify-between">
    <div class="flex items-center gap-4">
      <Button icon="pi pi-arrow-left" text rounded @click="$emit('back')" />
      <div>
        <h1 class="text-2xl font-bold">
          {{ teamName }}
        </h1>
        <div class="mt-1 flex items-center gap-2">
          <Tag :value="templateLabel" severity="info" />
          <RoleBadge v-if="roleName" :role="roleName" />
        </div>
        <div class="mt-2 flex items-center gap-4 text-sm text-surface-500">
          <span class="flex items-center gap-1">
            <i class="pi pi-users text-xs" />
            メンバー <strong class="text-surface-700">{{ memberCount }}</strong
            >人
          </span>
          <span v-if="supporterEnabled" class="flex items-center gap-1">
            <i class="pi pi-heart text-xs" />
            サポーター <strong class="text-surface-700">{{ supporterCount ?? '—' }}</strong
            >人
          </span>
        </div>
      </div>
    </div>
    <template v-if="supporterEnabled && !roleName">
      <Button
        v-if="followStatus === 'APPROVED'"
        icon="pi pi-heart-fill"
        label="サポーターです"
        size="small"
        :loading="followLoading"
        class="border-red-400 bg-red-50 text-red-500 hover:bg-red-100"
        outlined
        @click="$emit('showCancelConfirm')"
      />
      <span
        v-else-if="followStatus === 'PENDING'"
        class="flex items-center gap-2 text-sm text-orange-500"
      >
        <i class="pi pi-clock" />申請中（承認待ち）
        <Button
          label="取消"
          size="small"
          severity="secondary"
          text
          :loading="followLoading"
          @click="$emit('cancelSupporter')"
        />
      </span>
      <Button
        v-else
        label="サポーターになる"
        icon="pi pi-heart"
        severity="secondary"
        outlined
        size="small"
        :loading="followLoading"
        @click="$emit('applySupporter')"
      />
    </template>
    <Button
      v-if="!isAdmin && roleName"
      label="チームから退出"
      icon="pi pi-sign-out"
      severity="danger"
      outlined
      size="small"
      @click="$emit('showLeaveConfirm')"
    />
  </div>
</template>
