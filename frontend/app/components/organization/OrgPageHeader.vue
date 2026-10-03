<script setup lang="ts">
import type { AncestorOrganization } from '~/types/organization'
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { FollowUiStatus } from '~/composables/useFollowSelfStatus'
import type { JoinRequestUiStatus } from '~/composables/useJoinRequestApi'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

const props = defineProps<{
  org: OrgDetail
  orgId: string
  roleName: string | null
  isAdmin: boolean
  isAdminOrDeputy: boolean
  followStatus: FollowUiStatus
  followLoading: boolean
  /** AC-9: フォロー解除成功・権限再取得失敗の同期失敗フラグ（未所属確定として扱わない）。 */
  followPermissionSyncError: boolean
  joinRequestStatus: JoinRequestUiStatus
  joinRequestLoading: boolean
  ancestors: AncestorOrganization[]
}>()

const emit = defineEmits<{
  back: []
  applySupporter: []
  cancelSupporter: []
  retryFollowStatus: []
  retryFollowPermissionSync: []
  applyJoinRequest: []
  retryJoinRequestStatus: []
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
  // AC-2/AC-3: 応援者(SUPPORTER)には退出を出さない（退出できるのは MEMBER 等の正規加入者のみ）。
  if (!props.isAdmin && props.roleName && props.roleName !== 'SUPPORTER') {
    items.push({
      label: t('orgShell.action.leave_button'),
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
        <!--
          フォロー（サポーター）状態の表示（CMP-261001-0835）。
          AC-1: APPROVED は roleName・supporterEnabled に関係なく「フォロー解除」導線を出す
          （SUPPORTER ロール自身も含む。フォロー承認で SUPPORTER ロールが付与されるため）。
          AC-4: 「フォローする」(NONE) は supporterEnabled=true かつ未所属（roleName なし）
          かつ同期エラーが無いときだけ出す。
          AC-6: UNKNOWN/LOADING の間は押せる状態を一切出さない（fail-close）。
        -->
        <template v-if="followStatus === 'APPROVED'">
          <Button
            icon="pi pi-heart-fill"
            :label="$t('common.scopeShell.follow_approved_badge')"
            :aria-label="$t('common.scopeShell.follow_unfollow_aria')"
            size="small"
            data-testid="follow-unfollow-button"
            :loading="followLoading"
            class="border-red-400 bg-red-50 text-red-500 hover:bg-red-100"
            outlined
            @click="emit('showCancelConfirm')"
          />
        </template>
        <!--
          検分修繕: PENDING の「取消」は SUPPORTER 以外の正規所属ロールを持つ人には出さない
          （MEMBER/ADMIN 等の正規所属に PENDING 申請が併存していても、BE はそれを解除対象として
          扱わないため、取消ボタンを出すと誤操作導線になる）。
        -->
        <span
          v-else-if="followStatus === 'PENDING' && (!roleName || roleName === 'SUPPORTER')"
          class="flex items-center gap-2 text-sm text-orange-500"
        >
          <i class="pi pi-clock" />{{ $t('common.scopeShell.follow_pending_label') }}
          <Button
            :label="$t('common.scopeShell.follow_pending_cancel_button')"
            size="small"
            severity="secondary"
            text
            data-testid="follow-pending-cancel-button"
            :loading="followLoading"
            @click="emit('cancelSupporter')"
          />
        </span>
        <span
          v-else-if="followStatus === 'ERROR'"
          class="flex items-center gap-2 text-sm text-red-500"
          data-testid="follow-fetch-error"
        >
          <i class="pi pi-exclamation-triangle" />{{ $t('common.scopeShell.follow_fetch_error') }}
          <Button
            :label="$t('common.scopeShell.retry')"
            text
            size="small"
            data-testid="follow-retry-button"
            @click="emit('retryFollowStatus')"
          />
        </span>
        <!-- AC-9: 解除は成功済みだが権限再取得が未同期。フォロー系操作は出さず再試行のみ出す。 -->
        <span
          v-else-if="followPermissionSyncError"
          class="flex items-center gap-2 text-sm text-red-500"
          data-testid="follow-permission-sync-error"
        >
          <i class="pi pi-exclamation-triangle" />{{ $t('common.scopeShell.follow_permission_sync_error_body') }}
          <Button
            :label="$t('common.scopeShell.retry')"
            text
            size="small"
            data-testid="follow-permission-sync-retry-button"
            @click="emit('retryFollowPermissionSync')"
          />
        </span>
        <Button
          v-else-if="followStatus === 'NONE' && org.visibility?.supporterEnabled && !roleName"
          :label="$t('common.scopeShell.follow_apply_button')"
          icon="pi pi-heart"
          severity="secondary"
          outlined
          size="small"
          data-testid="follow-apply-button"
          :loading="followLoading"
          @click="emit('applySupporter')"
        />
        <template v-if="org.visibility?.visibility === 'PUBLIC' && !roleName">
          <span
            v-if="joinRequestStatus === 'PENDING'"
            class="flex items-center gap-2 text-sm text-orange-500"
            data-testid="join-request-pending"
          >
            <i class="pi pi-clock" />{{ $t('joinRequest.pending') }}
          </span>
          <span
            v-else-if="joinRequestStatus === 'APPROVED'"
            class="flex items-center gap-2 text-sm text-green-600"
            data-testid="join-request-approved"
          >
            <i class="pi pi-check-circle" />{{ $t('joinRequest.approved') }}
          </span>
          <span
            v-else-if="joinRequestStatus === 'ERROR'"
            class="flex items-center gap-2 text-sm text-red-500"
            data-testid="join-request-error"
          >
            <i class="pi pi-exclamation-triangle" />{{ $t('joinRequest.fetchError') }}
            <Button
              :label="$t('joinRequest.retry')"
              text
              size="small"
              data-testid="join-request-retry-button"
              @click="emit('retryJoinRequestStatus')"
            />
          </span>
          <!--
            REJECTED は「再申請不可」を意味しない。BE は既存の PENDING のみを
            重複扱いし、却下後の新規申請を許可している（JoinRequestService.java）。
            そのため却下された旨は表示しつつ、申請ボタンは NONE と同様に有効にする
            （Codex 検分第2巡 P1-1 是正）。
          -->
          <template v-else>
            <span
              v-if="joinRequestStatus === 'REJECTED'"
              class="text-xs text-gray-500"
              data-testid="join-request-rejected"
            >
              {{ $t('joinRequest.rejected') }}
            </span>
            <Button
              :label="$t('joinRequest.apply')"
              icon="pi pi-user-plus"
              severity="secondary"
              outlined
              size="small"
              data-testid="join-request-apply-button"
              :disabled="joinRequestStatus !== 'NONE' && joinRequestStatus !== 'REJECTED'"
              :loading="joinRequestLoading"
              @click="emit('applyJoinRequest')"
            />
          </template>
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
        <!-- AC-2/AC-3: 応援者(SUPPORTER)には退出を出さない（退出できるのは MEMBER 等の正規加入者のみ）。 -->
        <Button
          v-if="!isAdmin && roleName && roleName !== 'SUPPORTER'"
          :label="$t('orgShell.action.leave_button')"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          class="hidden sm:inline-flex"
          data-testid="org-leave-button"
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
