<script setup lang="ts">
/**
 * 村メンバー一覧テーブル — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/members.vue) から membership 配列・loading 状態・
 * 権限フラグ・ページング情報を受け取り、行の描画と操作ボタンを担当する。
 *
 * - ロジックは持たない（API 呼び出しは親が担う）
 * - 操作ボタン押下は emit で親に通知する
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import Paginator from 'primevue/paginator'
import Tag from 'primevue/tag'

import type {
  MembershipResponse,
  VillageResponse,
  VillageRole,
} from '~/types/village'

const props = defineProps<{
  members: MembershipResponse[]
  loading: boolean
  village: VillageResponse | null
  isHeadman: boolean
  currentUserId: number | null
  totalElements: number
  page: number
  pageSize: number
}>()

const emit = defineEmits<{
  pageChange: [event: { page: number }]
  openRoleDialog: [m: MembershipResponse]
  openBanDialog: [m: MembershipResponse]
  openMemberReport: [m: MembershipResponse]
  /** F17.2 Wave3 ⑥ 村人ミニプロフィール Dialog を開く（名前クリックで発火）。 */
  openMemberProfile: [m: MembershipResponse]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

// =============================================================================
// 表示ヘルパ
// =============================================================================

function roleLabel(role: VillageRole): string {
  return t(`village.role.${role}`)
}

function roleSeverity(role: VillageRole): 'warn' | 'info' | 'secondary' | 'contrast' {
  switch (role) {
    case 'HEADMAN':
      return 'warn'
    case 'ELDER':
      return 'info'
    case 'VILLAGER':
      return 'secondary'
    case 'VISITOR':
      return 'contrast'
  }
}

function subjectTypeIcon(type: MembershipResponse['subjectType']): string {
  switch (type) {
    case 'TEAM':
      return 'pi pi-users'
    case 'ORGANIZATION':
      return 'pi pi-building'
    case 'USER':
    default:
      return 'pi pi-user'
  }
}

function displayName(m: MembershipResponse): string {
  return m.displayName ?? `#${m.subjectId}`
}

function formatDate(iso: string): string {
  return formatDateTime(iso) || iso
}

/** 自分自身の membership 行かどうか（自分への操作は禁止） */
function isSelfMembership(m: MembershipResponse): boolean {
  if (props.currentUserId == null) return false
  return m.subjectType === 'USER' && m.subjectId === props.currentUserId
}
</script>

<template>
  <section class="mx-auto max-w-5xl px-4 py-6 sm:px-6">
    <div class="mb-3 flex items-center justify-between">
      <h2 class="text-lg font-semibold">
        {{ t('village.members.title') }}
      </h2>
      <span class="text-sm text-surface-500">
        {{ t('village.field.memberCount') }}: {{ totalElements }}
      </span>
    </div>

    <DataTable
      :value="members"
      :loading="loading"
      data-key="id"
      striped-rows
      responsive-layout="scroll"
    >
      <template #empty>
        <div class="py-6 text-center text-sm text-surface-500">
          {{ t('village.members.empty') }}
        </div>
      </template>

      <!-- 名前列 -->
      <Column :header="t('village.field.name')">
        <template #body="slotProps">
          <div class="flex items-center gap-2">
            <i :class="subjectTypeIcon((slotProps.data as MembershipResponse).subjectType)" />
            <!-- 村人（USER 主体）の名前タップでミニプロフィール Dialog を開く（§9.5）。
                 TEAM/ORGANIZATION は個人プロフィールの概念が無いため対象外。 -->
            <button
              v-if="(slotProps.data as MembershipResponse).subjectType === 'USER'"
              type="button"
              class="font-medium hover:underline focus:underline"
              @click="emit('openMemberProfile', slotProps.data as MembershipResponse)"
            >
              {{ displayName(slotProps.data as MembershipResponse) }}
            </button>
            <span v-else class="font-medium">
              {{ displayName(slotProps.data as MembershipResponse) }}
            </span>
            <Badge
              v-if="isSelfMembership(slotProps.data as MembershipResponse)"
              :value="t('village.members.you')"
              severity="info"
              size="small"
            />
            <Badge
              v-if="(slotProps.data as MembershipResponse).isBanned"
              :value="t('village.members.banned')"
              severity="danger"
              size="small"
            />
          </div>
          <div class="mt-1 text-xs text-surface-500">
            {{ t(`village.subjectType.${(slotProps.data as MembershipResponse).subjectType}`) }}
          </div>
        </template>
      </Column>

      <!-- ロール列 -->
      <Column :header="t('village.field.type')">
        <template #body="slotProps">
          <Tag
            :value="roleLabel((slotProps.data as MembershipResponse).role)"
            :severity="roleSeverity((slotProps.data as MembershipResponse).role)"
          />
        </template>
      </Column>

      <!-- 参加日列 -->
      <Column :header="t('village.field.createdAt')">
        <template #body="slotProps">
          {{ formatDate((slotProps.data as MembershipResponse).joinedAt) }}
        </template>
      </Column>

      <!-- 操作列 -->
      <Column :header="t('village.action.submit')">
        <template #body="slotProps">
          <div class="flex flex-wrap gap-1">
            <!-- 通報（自分以外・自分が村人の場合のみ） -->
            <Button
              v-if="village?.isMember && !isSelfMembership(slotProps.data as MembershipResponse)"
              :aria-label="t('village.action.report')"
              icon="pi pi-flag"
              severity="secondary"
              size="small"
              text
              @click="emit('openMemberReport', slotProps.data as MembershipResponse)"
            />

            <!-- ロール変更（HEADMAN のみ・自分以外・BAN 済みでない） -->
            <Button
              v-if="isHeadman && !isSelfMembership(slotProps.data as MembershipResponse) && !(slotProps.data as MembershipResponse).isBanned"
              :label="t('village.action.changeRole')"
              icon="pi pi-shield"
              severity="secondary"
              size="small"
              outlined
              @click="emit('openRoleDialog', slotProps.data as MembershipResponse)"
            />

            <!-- BAN（HEADMAN のみ・自分以外・BAN 済みでない） -->
            <Button
              v-if="isHeadman && !isSelfMembership(slotProps.data as MembershipResponse) && !(slotProps.data as MembershipResponse).isBanned"
              :label="t('village.action.ban')"
              icon="pi pi-ban"
              severity="danger"
              size="small"
              outlined
              @click="emit('openBanDialog', slotProps.data as MembershipResponse)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- ページネーション -->
    <Paginator
      v-if="totalElements > pageSize"
      :rows="pageSize"
      :total-records="totalElements"
      :first="page * pageSize"
      class="mt-2"
      @page="(event: { page: number }) => emit('pageChange', event)"
    />
  </section>
</template>
