<script setup lang="ts">
import type { TeamResponse } from '~/types/team'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

defineProps<{
  team: TeamResponse
  displayName: string
  roleName: string | null
  isAdmin: boolean
  isAdminOrDeputy: boolean
  followStatus: 'NONE' | 'PENDING' | 'APPROVED'
  followLoading: boolean
  templateLabel: Record<string, string>
}>()

const emit = defineEmits<{
  back: []
  applySupporter: []
  cancelSupporter: []
  showCancelConfirm: []
  showLeaveConfirm: []
  iconUpdated: [url: string | null]
  bannerUpdated: [url: string | null]
}>()

// F02.8 告知ウィザード（ローカル管理）
const showBroadcastWizard = ref(false)
</script>

<template>
  <ProfileHeader
    :icon-url="team.metadata?.iconUrl"
    :banner-url="team.metadata?.bannerUrl"
    :name="displayName"
    scope="team"
    :scope-id="String(team.id)"
    :editable="isAdminOrDeputy"
    @icon-updated="(url) => emit('iconUpdated', url)"
    @banner-updated="(url) => emit('bannerUpdated', url)"
  >
    <!-- 名前行 + アクション群 -->
    <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-2 pt-1">
      <!-- 左: 戻る + 名前 + メタ情報 -->
      <div class="flex flex-col gap-1 min-w-0">
        <div class="flex items-center gap-2 flex-wrap">
          <Button icon="pi pi-arrow-left" text rounded size="small" @click="emit('back')" />
          <h1 class="text-xl sm:text-2xl font-bold truncate">
            {{ displayName }}
          </h1>
          <Tag :value="templateLabel[team.location?.template ?? ''] ?? team.location?.template ?? ''" severity="info" />
          <RoleBadge v-if="roleName" :role="roleName" />
        </div>
        <div class="flex items-center gap-3 text-xs sm:text-sm text-surface-500 flex-wrap pl-8">
          <span class="flex items-center gap-1">
            <i class="pi pi-users text-xs" />
            メンバー <strong class="text-surface-700">{{ team.metadata?.memberCount ?? 0 }}</strong>人
          </span>
          <span v-if="team.visibility?.supporterEnabled" class="flex items-center gap-1">
            <i class="pi pi-heart text-xs" />
            サポーター <strong class="text-surface-700">{{ team.social?.supporterCount ?? '—' }}</strong>人
          </span>
        </div>
      </div>

      <!-- 右: アクションボタン群 -->
      <div class="flex items-center gap-2 flex-wrap shrink-0">
        <FavoriteToggleButton
          entity-type="TEAM"
          :entity-id="String(team.id)"
          :entity-name="displayName"
        />
        <template v-if="team.visibility?.supporterEnabled && !roleName">
          <Button
            v-if="followStatus === 'APPROVED'"
            icon="pi pi-heart-fill"
            label="サポーターです"
            size="small"
            :loading="followLoading"
            class="border-red-400 bg-red-50 text-red-500 hover:bg-red-100"
            outlined
            @click="emit('showCancelConfirm')"
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
              @click="emit('cancelSupporter')"
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
            @click="emit('applySupporter')"
          />
        </template>
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('market.action.post')"
          icon="pi pi-tag"
          severity="secondary"
          outlined
          size="small"
          @click="navigateTo(`/teams/${team.id}/recruitment-listings/new`)"
        />
        <Button
          v-if="roleName && roleName !== 'SUPPORTER'"
          :label="$t('announcement.broadcast_button_team')"
          icon="pi pi-bullhorn"
          severity="secondary"
          size="small"
          @click="showBroadcastWizard = true"
        />
        <Button
          v-if="!isAdmin && roleName"
          label="チームから退出"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          @click="emit('showLeaveConfirm')"
        />
      </div>
    </div>
  </ProfileHeader>

  <BroadcastWizard
    v-model:visible="showBroadcastWizard"
    scope-type="TEAM"
    :scope-id="String(team.id)"
    :is-admin="isAdmin"
  />
</template>
