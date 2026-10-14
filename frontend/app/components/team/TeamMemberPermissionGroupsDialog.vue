<script setup lang="ts">
import { useTeamMembers, type TeamPermissionGroup } from '~/composables/team/useTeamMembers'
import { MANAGE_ORG_AFFILIATION } from '~/utils/teamAffiliationAccess'

/**
 * F01.2.1 8-D — メンバー一覧の操作メニュー「権限グループ」ダイアログ（設計書 §3.2・AC-P03〜P05）。
 *
 * チーム ADMIN・管理レンズ ON のときだけ親（members）が開く。選んだ権限グループで、そのメンバーの
 * このチームでの権限グループ割当を置き換える（PUT /teams/{slug}/members/{userId}/permission-groups）。
 * 何も選ばずに保存すると割当をすべて外す（AC-P05）。
 *
 * 現在の割当を返す BE API が無いため、現在の選択状態は表示できない。上書きになることを明示している。
 */
const props = defineProps<{
  teamSlug: string
  member: { userId: number, displayName: string, roleName: string } | null
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
const saving = ref(false)
const saveFailed = ref(false)

let requestSeq = 0

async function load() {
  const seq = ++requestSeq
  loading.value = true
  loadFailed.value = false
  saveFailed.value = false
  selected.value = new Set()
  try {
    const res = await getPermissionGroups(props.teamSlug)
    if (seq !== requestSeq) return
    groups.value = res.data
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

function toggle(groupId: number, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) next.add(groupId)
  else next.delete(groupId)
  selected.value = next
}

async function save() {
  if (!props.member || saving.value) return
  saving.value = true
  saveFailed.value = false
  try {
    await assignPermissionGroups(props.teamSlug, props.member.userId, [...selected.value])
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

      <Message v-if="saveFailed" severity="error" data-testid="pg-assign-error">
        {{ t('teamAffiliations.member_menu.save_error') }}
      </Message>
    </div>
    <template #footer>
      <Button :label="t('button.cancel')" text @click="visible = false" />
      <Button
        v-if="!loading && !loadFailed && groups.length > 0"
        :label="t('teamAffiliations.member_menu.save')"
        :loading="saving"
        :disabled="saving"
        data-testid="pg-assign-submit"
        @click="save"
      />
    </template>
  </Dialog>
</template>
