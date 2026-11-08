<script setup lang="ts">
import type { OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import { getApiErrorCode } from '~/utils/loadError'

/**
 * F01.2.1 8-C — チームグループの一覧と、作成・改名・並び替え・削除。
 *
 * - 並び替えは ↑↓ ボタン（キーボード・タッチで確実に操作できる）。生存グループ全件の ID を新しい順で送る。
 * - 削除は所属チーム件数を示す確認を挟む（所属チームは未分類へ戻る）。
 * - 操作が成立したら `changed` を発火し、親が一覧・件数を取得し直す。
 * - 機能 off（ORG_067）になっていたら `changed` を発火して、親に誘導カードへ切り替えさせる。
 */
const props = defineProps<{
  orgSlug: string
  groups: OrgTeamGroup[]
  /** 1 組織あたりのグループ上限（BE の meta.limit）。 */
  limit: number
}>()

const emit = defineEmits<{
  changed: []
}>()

const NAME_MAX = 50
const DESCRIPTION_MAX = 200

const { t } = useI18n()
const toast = useToast()
const { createTeamGroup, updateTeamGroup, deleteTeamGroup, reorderTeamGroups } = useOrgTeamMembershipApi()

const limitReached = computed(() => props.groups.length >= props.limit)

// 作成・改名ダイアログ
const showForm = ref(false)
const editing = ref<OrgTeamGroup | null>(null)
const formName = ref('')
const formDescription = ref('')
const submitting = ref(false)
const formErrorCode = ref<string | null>(null)

// 削除確認
const showDelete = ref(false)
const deleteTarget = ref<OrgTeamGroup | null>(null)
const deleting = ref(false)
const deleteErrorCode = ref<string | null>(null)

// 並び替え
const reordering = ref(false)
const reorderErrorCode = ref<string | null>(null)

const formValid = computed(() => formName.value.trim().length > 0)

function openCreate() {
  editing.value = null
  formName.value = ''
  formDescription.value = ''
  formErrorCode.value = null
  showForm.value = true
}

function openEdit(group: OrgTeamGroup) {
  editing.value = group
  formName.value = group.name ?? ''
  formDescription.value = group.description ?? ''
  formErrorCode.value = null
  showForm.value = true
}

/** エラーコードを表示用の区分へ。未知は 'generic'。 */
function errorKind(code: string | null): string {
  return code === 'ORG_065' || code === 'ORG_066' || code === 'ORG_067' || code === 'ORG_064' || code === 'ORG_068'
    ? code
    : 'generic'
}

async function submitForm() {
  if (submitting.value || !formValid.value) return
  submitting.value = true
  formErrorCode.value = null
  const name = formName.value.trim()
  const description = formDescription.value.trim()
  try {
    if (editing.value?.id) {
      // 説明を消したいとき（元は有り→空）は空文字を送る。元から無いなら送らない。
      const clearing = description === '' && !!editing.value.description
      await updateTeamGroup(props.orgSlug, editing.value.id, {
        name,
        description: description !== '' ? description : (clearing ? '' : undefined),
      })
      toast.add({ severity: 'success', summary: t('orgMemberTeams.groups.renamed'), life: 3000 })
    }
    else {
      await createTeamGroup(props.orgSlug, { name, description: description !== '' ? description : undefined })
      toast.add({ severity: 'success', summary: t('orgMemberTeams.groups.created'), life: 3000 })
    }
    showForm.value = false
    emit('changed')
  }
  catch (e) {
    // 失敗はダイアログ内に残す（成功に見せない）。機能 off になっていたら親へ知らせて誘導に切り替えさせる。
    const code = getApiErrorCode(e) ?? null
    formErrorCode.value = code ?? 'generic'
    if (code === 'ORG_067' || code === 'ORG_064') emit('changed')
  }
  finally {
    submitting.value = false
  }
}

function askDelete(group: OrgTeamGroup) {
  deleteTarget.value = group
  deleteErrorCode.value = null
  showDelete.value = true
}

async function confirmDelete() {
  const id = deleteTarget.value?.id
  if (!id || deleting.value) return
  deleting.value = true
  deleteErrorCode.value = null
  try {
    await deleteTeamGroup(props.orgSlug, id)
    showDelete.value = false
    toast.add({ severity: 'success', summary: t('orgMemberTeams.groups.deleted'), life: 3000 })
    emit('changed')
  }
  catch (e) {
    const code = getApiErrorCode(e) ?? null
    deleteErrorCode.value = code ?? 'generic'
    if (code === 'ORG_067' || code === 'ORG_064') emit('changed')
  }
  finally {
    deleting.value = false
  }
}

async function move(index: number, delta: -1 | 1) {
  if (reordering.value) return
  const ids = props.groups.map(g => g.id ?? '')
  const target = index + delta
  if (target < 0 || target >= ids.length) return
  const moved = ids[index]!
  ids[index] = ids[target]!
  ids[target] = moved
  reordering.value = true
  reorderErrorCode.value = null
  try {
    await reorderTeamGroups(props.orgSlug, ids)
    emit('changed')
  }
  catch (e) {
    // 他の管理者が先にグループを増減していた(ORG_068)等。最新の一覧を取り直して見せる。
    reorderErrorCode.value = getApiErrorCode(e) ?? 'generic'
    emit('changed')
  }
  finally {
    reordering.value = false
  }
}
</script>

<template>
  <section data-testid="member-teams-group-list">
    <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
      <h2 class="text-base font-semibold">
        {{ t('teamGroup.title') }}
      </h2>
      <Button
        :label="t('teamGroup.create')"
        icon="pi pi-plus"
        size="small"
        :disabled="limitReached"
        data-testid="group-create-open"
        @click="openCreate"
      />
    </div>
    <p class="mb-3 text-xs text-surface-500">
      {{ t('teamGroup.reorder_hint') }}
    </p>
    <Message v-if="limitReached" severity="warn" data-testid="group-limit-reached">
      {{ t('orgMemberTeams.groups.limit_reached', { limit }) }}
    </Message>
    <Message v-if="reorderErrorCode" severity="error" data-testid="group-reorder-error" :data-error-code="reorderErrorCode">
      {{ t(`orgMemberTeams.groups.error.${errorKind(reorderErrorCode)}`) }}
    </Message>

    <p
      v-if="groups.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="groups-empty"
    >
      {{ t('orgMemberTeams.groups.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="(group, index) in groups"
        :key="group.id"
        class="flex flex-wrap items-center justify-between gap-3 py-3"
        data-testid="group-row"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ group.name }}
            <span class="ml-2 text-xs text-surface-500">{{ t('teamGroup.team_count', { count: group.teamCount ?? 0 }) }}</span>
          </p>
          <p v-if="group.description" class="text-xs text-surface-500">
            {{ group.description }}
          </p>
        </div>
        <div class="flex items-center gap-1">
          <Button
            icon="pi pi-arrow-up"
            text
            size="small"
            :aria-label="t('orgMemberTeams.groups.move_up')"
            :disabled="index === 0 || reordering"
            :data-testid="`group-up-${group.id}`"
            @click="move(index, -1)"
          />
          <Button
            icon="pi pi-arrow-down"
            text
            size="small"
            :aria-label="t('orgMemberTeams.groups.move_down')"
            :disabled="index === groups.length - 1 || reordering"
            :data-testid="`group-down-${group.id}`"
            @click="move(index, 1)"
          />
          <Button
            :label="t('orgMemberTeams.groups.rename')"
            icon="pi pi-pencil"
            text
            size="small"
            :data-testid="`group-edit-${group.id}`"
            @click="openEdit(group)"
          />
          <Button
            :label="t('button.delete')"
            icon="pi pi-trash"
            text
            size="small"
            severity="danger"
            :data-testid="`group-delete-${group.id}`"
            @click="askDelete(group)"
          />
        </div>
      </li>
    </ul>

    <Dialog
      v-model:visible="showForm"
      modal
      :header="editing ? t('orgMemberTeams.groups.rename_title') : t('teamGroup.create')"
      class="w-full max-w-md"
    >
      <div class="space-y-4 text-sm">
        <div>
          <label class="mb-1 block font-medium" for="group-form-name">{{ t('teamGroup.name') }}</label>
          <InputText
            id="group-form-name"
            v-model="formName"
            :maxlength="NAME_MAX"
            class="w-full"
            data-testid="group-form-name"
          />
        </div>
        <div>
          <label class="mb-1 block font-medium" for="group-form-description">{{ t('teamGroup.description') }}</label>
          <Textarea
            id="group-form-description"
            v-model="formDescription"
            :maxlength="DESCRIPTION_MAX"
            rows="3"
            class="w-full"
            data-testid="group-form-description"
          />
        </div>
        <Message v-if="formErrorCode" severity="error" data-testid="group-form-error" :data-error-code="formErrorCode">
          {{ t(`orgMemberTeams.groups.error.${errorKind(formErrorCode)}`) }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showForm = false" />
        <Button
          :label="editing ? t('button.save') : t('teamGroup.create')"
          :loading="submitting"
          :disabled="submitting"
          data-testid="group-form-submit"
          @click="submitForm"
        />
      </template>
    </Dialog>

    <Dialog
      v-model:visible="showDelete"
      modal
      :header="t('orgMemberTeams.groups.delete_title')"
      class="w-full max-w-md"
    >
      <div class="space-y-3 text-sm">
        <p data-testid="group-delete-message">
          {{ t('teamGroup.delete_confirm', { name: deleteTarget?.name ?? '', count: deleteTarget?.teamCount ?? 0 }) }}
        </p>
        <Message v-if="deleteErrorCode" severity="error" data-testid="group-delete-error" :data-error-code="deleteErrorCode">
          {{ t(`orgMemberTeams.groups.error.${errorKind(deleteErrorCode)}`) }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showDelete = false" />
        <Button
          :label="t('button.delete')"
          severity="danger"
          :loading="deleting"
          :disabled="deleting"
          data-testid="group-delete-confirm"
          @click="confirmDelete"
        />
      </template>
    </Dialog>
  </section>
</template>
