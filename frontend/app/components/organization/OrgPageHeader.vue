<script setup lang="ts">
import type { AncestorOrganization } from '~/types/organization'
import type { OrgDetail } from '~/composables/useOrgDetail'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

const props = defineProps<{
  org: OrgDetail
  orgId: string
  roleName: string | null
  isAdmin: boolean
  isAdminOrDeputy: boolean
  followStatus: 'NONE' | 'PENDING' | 'APPROVED'
  followLoading: boolean
  ancestors: AncestorOrganization[]
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

// 表示名（nickname1 優先）
const displayName = computed(() => props.org.basicInfo?.nickname1 || props.org.basicInfo?.name || '')

const { t } = useI18n()

// F02.8 告知ウィザード（ローカル管理）
const showBroadcastWizard = ref(false)

/**
 * モバイル(<sm)向け「⋯」オーバーフローメニュー。
 * 低頻度アクション（市場出品導線・組織内告知・組織から退出）をここへ格納し、
 * デスクトップ(sm以上)は従来どおりインライン表示のまま維持する。
 */
const overflowMenu = ref()
function toggleOverflowMenu(event: Event) {
  overflowMenu.value.toggle(event)
}
const overflowMenuItems = computed(() => {
  const items: { label: string, icon: string, command: () => void }[] = []
  if (props.roleName) {
    items.push({
      label: t('market.management.title'),
      icon: 'pi pi-shopping-bag',
      command: () => navigateTo(`/organizations/${props.orgId}/market`),
    })
  }
  if (props.isAdminOrDeputy) {
    items.push({
      label: t('market.action.post'),
      icon: 'pi pi-tag',
      command: () => navigateTo(`/organizations/${props.orgId}/recruitment-listings/new`),
    })
  }
  if (props.roleName && props.roleName !== 'SUPPORTER') {
    items.push({
      label: t('announcement.broadcast_button_org'),
      icon: 'pi pi-bullhorn',
      command: () => { showBroadcastWizard.value = true },
    })
  }
  if (!props.isAdmin && props.roleName) {
    items.push({
      label: '組織から退出',
      icon: 'pi pi-sign-out',
      command: () => emit('showLeaveConfirm'),
    })
  }
  return items
})
</script>

<template>
  <ProfileHeader
    :icon-url="org.metadata?.iconUrl ?? null"
    :banner-url="org.metadata?.bannerUrl ?? null"
    :name="displayName"
    scope="organization"
    :scope-id="orgId"
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
          <RoleBadge v-if="roleName" :role="roleName" />
        </div>
        <div class="flex items-center gap-3 text-xs sm:text-sm text-surface-500 flex-wrap pl-8">
          <span class="flex items-center gap-1">
            <i class="pi pi-users text-xs" />
            メンバー <strong class="text-surface-700">{{ org.metadata?.memberCount }}</strong>人
          </span>
          <span v-if="org.visibility?.supporterEnabled" class="flex items-center gap-1">
            <i class="pi pi-heart text-xs" />
            サポーター <strong class="text-surface-700">{{ org.supporterCount ?? '—' }}</strong>人
          </span>
        </div>
      </div>

      <!-- 右: アクションボタン群 -->
      <div class="flex items-center gap-2 flex-wrap shrink-0">
        <!-- F02.9 お気に入りトグル -->
        <FavoriteToggleButton
          entity-type="ORGANIZATION"
          :entity-id="String(org.id)"
          :entity-name="displayName"
        />
        <Button
          v-if="roleName"
          :label="$t('market.management.title')"
          icon="pi pi-shopping-bag"
          severity="secondary"
          outlined
          size="small"
          class="hidden sm:inline-flex"
          data-testid="organization-market-link"
          @click="navigateTo(`/organizations/${orgId}/market`)"
        />
        <template v-if="org.visibility?.supporterEnabled && !roleName">
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
        <!-- 低頻度アクション（市場出品導線・組織内告知・組織から退出）:
             デスクトップ(sm以上)は従来どおりインライン表示 -->
        <!-- F22.1 市（Market）: ADMIN または DEPUTY_ADMIN のみ「札を立てる」導線 -->
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('market.action.post')"
          icon="pi pi-tag"
          severity="secondary"
          outlined
          size="small"
          class="hidden sm:inline-flex"
          @click="navigateTo(`/organizations/${orgId}/recruitment-listings/new`)"
        />
        <!-- F02.8 告知ウィザード：MEMBER以上に表示 -->
        <Button
          v-if="roleName && roleName !== 'SUPPORTER'"
          :label="$t('announcement.broadcast_button_org')"
          icon="pi pi-bullhorn"
          severity="secondary"
          size="small"
          class="hidden sm:inline-flex"
          @click="showBroadcastWizard = true"
        />
        <Button
          v-if="!isAdmin && roleName"
          label="組織から退出"
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

  <OrgAncestorsBreadcrumb
    v-if="ancestors.length > 0"
    :ancestors="ancestors"
    :current-org-name="displayName"
    class="mb-4 px-4 sm:px-6"
  />

  <BroadcastWizard
    v-model:visible="showBroadcastWizard"
    scope-type="ORGANIZATION"
    :scope-id="orgId"
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
