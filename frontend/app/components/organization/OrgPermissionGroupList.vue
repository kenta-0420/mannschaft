<script setup lang="ts">
import type { PermissionGroupListItem } from '~/types/organization'
import { MANAGE_ORG_AFFILIATION } from '~/utils/teamAffiliationAccess'

/**
 * 権限グループの一覧表示。組織（/organizations/{slug}/permission-groups）とチーム
 * （/teams/{slug}/permission-groups。F01.2.1 8-D）で共用するスコープ非依存の表示部品。
 *
 * - チームの権限グループは対象ロール（targetRole）を持つので、あれば出す（組織側は持たない）
 * - `MANAGE_ORG_AFFILIATION`（チームの加盟操作権限。付与できるのはチーム ADMIN だけ）を含むグループには印を出す
 * - `editable` のときだけ各グループに編集ボタンを出し、押すと `edit` を発火する（組織側は既定 false で従来どおり）
 */
defineProps<{
  groups: PermissionGroupListItem[]
  editable?: boolean
}>()

const emit = defineEmits<{
  edit: [group: PermissionGroupListItem]
}>()
</script>

<template>
  <div class="mt-4">
    <div
      v-if="groups.length === 0"
      class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500"
      data-testid="group-list-empty"
    >
      <i class="pi pi-shield mb-2 text-3xl" />
      <p>{{ $t('teamAffiliations.permission_group_list.empty') }}</p>
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="group in groups"
        :key="group.id"
        class="rounded-lg border p-4"
      >
        <div class="mb-2 flex items-center justify-between gap-2">
          <h3 class="font-semibold">
            {{ group.name }}
          </h3>
          <div class="flex items-center gap-2">
            <Tag
              v-if="group.targetRole"
              :value="$t('teamAffiliations.permission_group_list.target_role', { role: group.targetRole })"
              severity="info"
              class="text-xs"
              :data-testid="`group-target-role-${group.id}`"
            />
            <Tag
              v-if="group.permissions.includes(MANAGE_ORG_AFFILIATION)"
              :value="$t('teamAffiliations.permission_group_list.affiliation_mark')"
              severity="warn"
              icon="pi pi-sitemap"
              class="text-xs"
              :data-testid="`group-affiliation-mark-${group.id}`"
            />
            <Button
              v-if="editable"
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              :aria-label="$t('teamAffiliations.permission_group_list.edit', { name: group.name })"
              :data-testid="`group-edit-${group.id}`"
              @click="emit('edit', group)"
            />
            <span class="text-sm text-gray-500" :data-testid="`group-count-${group.id}`">{{ $t('teamAffiliations.permission_group_list.count', { count: group.permissions.length }) }}</span>
          </div>
        </div>
        <p v-if="group.description" class="mb-2 text-sm text-gray-600">
          {{ group.description }}
        </p>
        <div class="flex flex-wrap gap-1">
          <Tag
            v-for="perm in group.permissions"
            :key="perm"
            :value="perm"
            severity="secondary"
            class="text-xs"
          />
        </div>
      </div>
    </div>
  </div>
</template>
