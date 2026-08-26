<script setup lang="ts">
import type { TeamResponse } from '~/types/team'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

const emit = defineEmits<{
  back: []
  applySupporter: []
  cancelSupporter: []
  showCancelConfirm: []
  showLeaveConfirm: []
  iconUpdated: [url: string | null]
  bannerUpdated: [url: string | null]
}>()

const props = defineProps<{
  team: TeamResponse
  displayName: string
  roleName: string | null
  isAdmin: boolean
  isAdminOrDeputy: boolean
  followStatus: 'NONE' | 'PENDING' | 'APPROVED'
  followLoading: boolean
  templateLabel: Record<string, string>
}>()

const { t } = useI18n()

// F02.8 告知ウィザード（ローカル管理）
const showBroadcastWizard = ref(false)

/**
 * モバイル(<sm)向け「⋯」オーバーフローメニュー。
 * 低頻度アクション（市場出品導線・チーム内告知・チームから退出）をここへ格納し、
 * デスクトップ(sm以上)は従来どおりインライン表示のまま維持する。
 */
const overflowMenu = ref()
function toggleOverflowMenu(event: Event) {
  overflowMenu.value.toggle(event)
}
const overflowMenuItems = computed(() => {
  const items: { label: string, icon: string, command: () => void }[] = []
  if (props.isAdminOrDeputy) {
    items.push({
      label: t('market.action.post'),
      icon: 'pi pi-tag',
      command: () => navigateTo(`/teams/${props.team.slug}/recruitment-listings/new`),
    })
  }
  if (props.roleName && props.roleName !== 'SUPPORTER') {
    items.push({
      label: t('announcement.broadcast_button_team'),
      icon: 'pi pi-bullhorn',
      command: () => { showBroadcastWizard.value = true },
    })
  }
  if (!props.isAdmin && props.roleName) {
    items.push({
      label: 'チームから退出',
      icon: 'pi pi-sign-out',
      command: () => emit('showLeaveConfirm'),
    })
  }
  return items
})
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
    <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-1 sm:gap-2 pt-1">
      <!-- 左: 戻る + 名前 + メタ情報 -->
      <div class="flex flex-col gap-1 min-w-0">
        <div class="flex items-center gap-2 flex-wrap tag-on-light-band">
          <Button icon="pi pi-arrow-left" text rounded size="small" @click="emit('back')" />
          <h1 class="text-xl sm:text-2xl font-bold truncate text-surface-900">
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
        <!-- 低頻度アクション（市場出品導線・チーム内告知・チームから退出）:
             デスクトップ(sm以上)は従来どおりインライン表示 -->
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('market.action.post')"
          icon="pi pi-tag"
          severity="secondary"
          outlined
          size="small"
          class="hidden sm:inline-flex"
          @click="navigateTo(`/teams/${team.slug}/recruitment-listings/new`)"
        />
        <Button
          v-if="roleName && roleName !== 'SUPPORTER'"
          :label="$t('announcement.broadcast_button_team')"
          icon="pi pi-bullhorn"
          severity="secondary"
          size="small"
          class="hidden sm:inline-flex"
          @click="showBroadcastWizard = true"
        />
        <Button
          v-if="!isAdmin && roleName"
          label="チームから退出"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          class="hidden sm:inline-flex"
          @click="emit('showLeaveConfirm')"
        />

        <!-- モバイル(<sm): 低頻度アクションを「⋯」オーバーフローメニューへ格納 -->
        <div v-if="overflowMenuItems.length > 0" class="sm:hidden">
          <Button
            icon="pi pi-ellipsis-v"
            text
            rounded
            severity="secondary"
            size="small"
            :aria-label="$t('common.menu')"
            @click="toggleOverflowMenu"
          />
          <Menu ref="overflowMenu" :model="overflowMenuItems" popup />
        </div>
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

<style scoped>
/*
 * ProfileHeader の情報バンドはダークモードでも bg-surface-0（白）固定。
 * PrimeVue Tag のダークモード配色は淡色テキスト前提のため、白地では
 * 「家族」「管理者」等のタグが薄れて読みにくい。バンドが白である以上、
 * ダークモードでもライトモード相当の濃色トークンへ上書きする
 * （CSS 変数は子孫の Tag へ継承される）。
 */
:global(.p-dark) .tag-on-light-band {
  --p-tag-primary-background: var(--p-primary-100);
  --p-tag-primary-color: var(--p-primary-700);
  --p-tag-secondary-background: var(--p-surface-100);
  --p-tag-secondary-color: var(--p-surface-600);
  --p-tag-success-background: var(--p-green-100);
  --p-tag-success-color: var(--p-green-700);
  --p-tag-info-background: var(--p-sky-100);
  --p-tag-info-color: var(--p-sky-700);
  --p-tag-warn-background: var(--p-orange-100);
  --p-tag-warn-color: var(--p-orange-700);
  --p-tag-danger-background: var(--p-red-100);
  --p-tag-danger-color: var(--p-red-700);
  --p-tag-contrast-background: var(--p-surface-950);
  --p-tag-contrast-color: var(--p-surface-0);
}
</style>
