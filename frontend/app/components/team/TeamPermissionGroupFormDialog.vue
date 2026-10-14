<script setup lang="ts">
import { useTeamMembers } from '~/composables/team/useTeamMembers'
import { usePermissionCatalogApi, type PermissionCatalogItem } from '~/composables/usePermissionCatalogApi'

/**
 * F01.2.1 8-D — チームの権限グループ 作成・編集ダイアログ（設計書 §3.2・AC-P03/P04/P10）。
 *
 * チーム ADMIN 割当・管理レンズ ON のときだけ親（permission-groups）が開く。権限は権限カタログ
 * （GET /api/v1/permissions）から選ぶ。加盟操作権限（MANAGE_ORG_AFFILIATION）などチーム ADMIN だけが
 * 含められる権限には印を付ける（DEPUTY_ADMIN が含めると BE が 403 にする）。
 * BE は ADMIN 専用権限が変更の前後どちらかに含まれる更新・作成を、チーム ADMIN 本人（SYSTEM_ADMIN 不可）だけに許す。
 * 付与できない操作者（SYSTEM_ADMIN 兼任など）には ADMIN 専用権限を選べなくし、既に ADMIN 専用権限を含む
 * グループは外す操作も含めて編集させない（理由を表示）。
 * 保存後は成功表示に切り替え、メンバー一覧（割当の入口）への案内を出す（作成→割当の導線）。
 */
const props = defineProps<{
  teamSlug: string
  /** チームの数値 ID（権限カタログの問い合わせ用）。 */
  teamId: number | null
  /** 編集対象。null なら新規作成。 */
  group: { id: number, name: string, targetRole?: string, permissions: string[] } | null
  /** ADMIN 専用権限を含む権限グループを作成・更新できる操作者か（チーム ADMIN 本人。SYSTEM_ADMIN 兼任者は不可）。 */
  canGrantAdminOnly: boolean
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  saved: []
}>()

const { t } = useI18n()
const { listPermissionCatalog } = usePermissionCatalogApi()
const { createPermissionGroup, updatePermissionGroup } = useTeamMembers()

const catalog = ref<PermissionCatalogItem[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const name = ref('')
const targetRole = ref<string>('MEMBER')
const selected = ref<Set<number>>(new Set())
const saving = ref(false)
const saveFailed = ref(false)
const savedOk = ref(false)

const roleOptions = computed(() => [
  { value: 'DEPUTY_ADMIN', label: t('teamAffiliations.group_form.target_role_deputy') },
  { value: 'MEMBER', label: t('teamAffiliations.group_form.target_role_member') },
])

let requestSeq = 0

async function init() {
  const seq = ++requestSeq
  saveFailed.value = false
  savedOk.value = false
  loadFailed.value = false
  name.value = props.group?.name ?? ''
  targetRole.value = props.group?.targetRole ?? 'MEMBER'
  selected.value = new Set()
  if (props.teamId === null) {
    loadFailed.value = true
    return
  }
  loading.value = true
  try {
    const items = await listPermissionCatalog('TEAM', props.teamId)
    if (seq !== requestSeq) return
    catalog.value = items
    // 既存グループの権限は名前で返るので、カタログの ID に写像する。
    const names = new Set(props.group?.permissions ?? [])
    selected.value = new Set(items.filter(i => names.has(i.name)).map(i => i.id))
  }
  catch {
    if (seq !== requestSeq) return
    // 取得失敗は「選べる権限なし」に見せず、エラー表示にして保存させない。
    catalog.value = []
    loadFailed.value = true
  }
  if (seq === requestSeq) loading.value = false
}

watch(
  [visible, () => props.group?.id, () => props.teamSlug],
  () => {
    if (visible.value) void init()
    else requestSeq++
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  requestSeq++
})

function toggle(id: number, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(id)
  else next.delete(id)
  selected.value = next
}

/** 付与できない操作者が、既に ADMIN 専用権限を含むグループを開いた（外す操作も BE は 403）。 */
const lockedByAdminOnly = computed(() => {
  if (props.canGrantAdminOnly || !props.group) return false
  const adminOnlyNames = new Set(catalog.value.filter(i => i.adminOnlyGrantable).map(i => i.name))
  return props.group.permissions.some(name => adminOnlyNames.has(name))
})
const hasAdminOnlyItems = computed(() => !props.canGrantAdminOnly && catalog.value.some(i => i.adminOnlyGrantable))
const isItemDisabled = (item: PermissionCatalogItem) =>
  lockedByAdminOnly.value || (!props.canGrantAdminOnly && item.adminOnlyGrantable)

const valid = computed(() => name.value.trim().length > 0 && selected.value.size > 0)

async function save() {
  if (saving.value || !valid.value || lockedByAdminOnly.value) return
  saving.value = true
  saveFailed.value = false
  const body = {
    name: name.value.trim(),
    targetRole: targetRole.value,
    permissionIds: [...selected.value].sort((a, b) => a - b),
  }
  try {
    if (props.group) await updatePermissionGroup(props.teamSlug, props.group.id, body)
    else await createPermissionGroup(props.teamSlug, body)
    savedOk.value = true
    emit('saved')
  }
  catch {
    // 失敗はダイアログ内に表示して残す（作成・更新できたように見せない）。
    saveFailed.value = true
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="group ? t('teamAffiliations.group_form.edit_title') : t('teamAffiliations.group_form.create_title')"
    class="w-full max-w-lg"
  >
    <div v-if="savedOk" class="space-y-3 text-sm" data-testid="pf-saved">
      <Message severity="success">
        {{ t('teamAffiliations.group_form.saved') }}
      </Message>
      <p>{{ t('teamAffiliations.group_form.saved_hint') }}</p>
      <NuxtLink
        :to="`/teams/${teamSlug}/members`"
        class="text-primary-600 hover:underline"
        data-testid="pf-go-members"
      >
        {{ t('teamAffiliations.group_form.go_members') }}
      </NuxtLink>
    </div>

    <div v-else class="space-y-3 text-sm">
      <div v-if="loading" class="flex justify-center py-4">
        <ProgressSpinner style="width: 2rem; height: 2rem" />
      </div>

      <Message v-else-if="loadFailed" severity="error" data-testid="pf-load-error">
        {{ t('teamAffiliations.group_form.load_error') }}
      </Message>

      <template v-else>
        <label class="block space-y-1">
          <span class="font-medium">{{ t('teamAffiliations.group_form.name') }}</span>
          <InputText
            v-model="name"
            class="w-full"
            maxlength="100"
            :disabled="lockedByAdminOnly"
            data-testid="pf-name"
          />
        </label>

        <label class="block space-y-1">
          <span class="font-medium">{{ t('teamAffiliations.group_form.target_role') }}</span>
          <Select
            v-model="targetRole"
            :options="roleOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :disabled="lockedByAdminOnly"
            data-testid="pf-target-role"
          />
          <span class="block text-xs text-surface-500">{{ t('teamAffiliations.group_form.target_role_hint') }}</span>
        </label>

        <fieldset class="space-y-2">
          <legend class="font-medium">
            {{ t('teamAffiliations.group_form.permissions') }}
          </legend>
          <label v-for="item in catalog" :key="item.id" class="flex items-start gap-2">
            <Checkbox
              binary
              :model-value="selected.has(item.id)"
              :disabled="isItemDisabled(item)"
              :data-testid="`pf-perm-${item.id}`"
              @update:model-value="(v: boolean) => toggle(item.id, v)"
            />
            <span class="min-w-0">
              <span>{{ item.displayName }}</span>
              <span class="ml-2 text-xs text-surface-500">{{ item.name }}</span>
              <Tag
                v-if="item.adminOnlyGrantable"
                :value="t('teamAffiliations.group_form.admin_only')"
                severity="warn"
                class="ml-2 text-xs"
                :data-testid="`pf-admin-only-${item.id}`"
              />
            </span>
          </label>
        </fieldset>

        <Message v-if="lockedByAdminOnly" severity="warn" data-testid="pf-admin-only-locked">
          {{ t('teamAffiliations.group_form.admin_only_locked') }}
        </Message>
        <Message v-else-if="hasAdminOnlyItems" severity="info" data-testid="pf-admin-only-reason">
          {{ t('teamAffiliations.group_form.admin_only_reason') }}
        </Message>

        <Message v-if="saveFailed" severity="error" data-testid="pf-error">
          {{ t('teamAffiliations.group_form.save_error') }}
        </Message>
      </template>
    </div>

    <template #footer>
      <Button :label="savedOk ? t('button.close') : t('button.cancel')" text @click="visible = false" />
      <Button
        v-if="!savedOk && !loading && !loadFailed && !lockedByAdminOnly"
        :label="t('teamAffiliations.group_form.save')"
        :loading="saving"
        :disabled="saving || !valid"
        data-testid="pf-submit"
        @click="save"
      />
    </template>
  </Dialog>
</template>
