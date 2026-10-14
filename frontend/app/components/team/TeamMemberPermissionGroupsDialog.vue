<script setup lang="ts">
import { useTeamMembers, type TeamPermissionGroup } from '~/composables/team/useTeamMembers'
import { MANAGE_ORG_AFFILIATION } from '~/utils/teamAffiliationAccess'

/**
 * F01.2.1 8-D — メンバー一覧の操作メニュー「権限グループ」ダイアログ（設計書 §3.2・AC-P03〜P05）。
 *
 * チーム ADMIN・管理レンズ ON のときだけ親（members）が開く。BE の PUT
 * （/teams/{slug}/members/{userId}/permission-groups）は割当の全置換なので、メンバー一覧が返す現在の割当
 * （permissionGroups）でチェック状態を初期化し、追加・削除の差分を見せてから保存する。
 * 既存の割当を黙って消さない。全部外すと空配列で保存する（AC-P05）。
 * 現在の割当が分からない（permissionGroups が無い）ときは、置き換えで既存を消さないよう保存させない。
 */
const props = defineProps<{
  teamSlug: string
  member: {
    userId: number
    displayName: string
    roleName: string
    permissionGroups?: { id: number, name: string }[]
  } | null
}>()

const visible = defineModel<boolean>('visible', { default: false })

const emit = defineEmits<{
  assigned: []
}>()

const { t } = useI18n()
const toast = useToast()
const { getPermissionGroups, assignPermissionGroups } = useTeamMembers()

const groups = ref<TeamPermissionGroup[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const selected = ref<Set<number>>(new Set())
/** ダイアログを開いた時点の割当（差分の基準）。 */
const current = ref<Set<number>>(new Set())
const currentKnown = computed(() => Array.isArray(props.member?.permissionGroups))
const saving = ref(false)
const saveFailed = ref(false)

let requestSeq = 0

async function load() {
  const seq = ++requestSeq
  loading.value = true
  loadFailed.value = false
  saveFailed.value = false
  selected.value = new Set()
  current.value = new Set()
  try {
    const res = await getPermissionGroups(props.teamSlug)
    if (seq !== requestSeq) return
    groups.value = res.data
    // 現在の割当で初期化する。いま存在しないグループ（削除済み）は対象外。
    const existing = new Set(res.data.map(g => g.id))
    const initial = new Set((props.member?.permissionGroups ?? []).map(g => g.id).filter(id => existing.has(id)))
    current.value = initial
    selected.value = new Set(initial)
  }
  catch {
    if (seq !== requestSeq) return
    // 取得失敗は「グループなし」に見せず、エラー表示にして保存させない。
    groups.value = []
    loadFailed.value = true
  }
  if (seq === requestSeq) loading.value = false
}

watch(
  [visible, () => props.member?.userId, () => props.teamSlug],
  () => {
    if (visible.value && props.member) void load()
    else requestSeq++
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  requestSeq++
})

const groupName = (id: number) => groups.value.find(g => g.id === id)?.name ?? String(id)
const added = computed(() => [...selected.value].filter(id => !current.value.has(id)).sort((a, b) => a - b))
const removed = computed(() => [...current.value].filter(id => !selected.value.has(id)).sort((a, b) => a - b))
const changed = computed(() => added.value.length > 0 || removed.value.length > 0)

function toggle(groupId: number, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(groupId)
  else next.delete(groupId)
  selected.value = next
}

async function save() {
  if (!props.member || saving.value || !currentKnown.value || !changed.value) return
  saving.value = true
  saveFailed.value = false
  try {
    await assignPermissionGroups(props.teamSlug, props.member.userId, [...selected.value].sort((a, b) => a - b))
    toast.add({ severity: 'success', summary: t('teamAffiliations.member_menu.saved'), life: 3000 })
    emit('assigned')
    visible.value = false
  }
  catch {
    // 失敗はダイアログ内に表示して残す（付与・剥奪できたように見せない）。
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
    :header="t('teamAffiliations.member_menu.title', { name: member?.displayName ?? '' })"
    class="w-full max-w-lg"
  >
    <div class="space-y-3 text-sm">
      <p>{{ t('teamAffiliations.member_menu.hint') }}</p>

      <div v-if="loading" class="flex justify-center py-4">
        <ProgressSpinner style="width: 2rem; height: 2rem" />
      </div>

      <Message v-else-if="loadFailed" severity="error" data-testid="pg-load-error">
        {{ t('teamAffiliations.member_menu.load_error') }}
      </Message>

      <p v-else-if="groups.length === 0" class="text-surface-500" data-testid="pg-empty">
        {{ t('teamAffiliations.member_menu.empty') }}
      </p>

      <Message v-else-if="!currentKnown" severity="warn" data-testid="pg-current-unknown">
        {{ t('teamAffiliations.member_menu.current_unknown') }}
      </Message>

      <ul v-else class="space-y-2">
        <li v-for="group in groups" :key="group.id">
          <label class="flex items-start gap-2">
            <Checkbox
              binary
              :model-value="selected.has(group.id)"
              :data-testid="`pg-check-${group.id}`"
              @update:model-value="(v: boolean) => toggle(group.id, v)"
            />
            <span class="min-w-0">
              <span class="font-medium">{{ group.name }}</span>
              <span class="ml-2 text-xs text-surface-500">{{ group.targetRole }}</span>
              <Tag
                v-if="group.permissions.includes(MANAGE_ORG_AFFILIATION)"
                :value="t('teamAffiliations.permission_group_list.affiliation_mark')"
                severity="warn"
                class="ml-2 text-xs"
                :data-testid="`pg-affiliation-mark-${group.id}`"
              />
            </span>
          </label>
        </li>
      </ul>

      <div v-if="changed" class="space-y-1 rounded-lg bg-surface-50 p-2 dark:bg-surface-800" data-testid="pg-diff">
        <p v-if="added.length > 0" class="text-green-700 dark:text-green-400" data-testid="pg-diff-add">
          {{ t('teamAffiliations.member_menu.diff_add', { groups: added.map(groupName).join(', ') }) }}
        </p>
        <p v-if="removed.length > 0" class="text-red-700 dark:text-red-400" data-testid="pg-diff-remove">
          {{ t('teamAffiliations.member_menu.diff_remove', { groups: removed.map(groupName).join(', ') }) }}
        </p>
      </div>

      <Message v-if="saveFailed" severity="error" data-testid="pg-assign-error">
        {{ t('teamAffiliations.member_menu.save_error') }}
      </Message>
    </div>
    <template #footer>
      <Button :label="t('button.cancel')" text @click="visible = false" />
      <Button
        v-if="!loading && !loadFailed && groups.length > 0 && currentKnown"
        :label="t('teamAffiliations.member_menu.save')"
        :loading="saving"
        :disabled="saving || !changed"
        data-testid="pg-assign-submit"
        @click="save"
      />
    </template>
  </Dialog>
</template>
