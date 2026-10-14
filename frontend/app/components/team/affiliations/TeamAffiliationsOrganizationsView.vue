<script setup lang="ts">
import type { TeamOrgSummary } from '~/composables/useTeamAffiliationApi'

/**
 * F01.2.1 8-D — チームの所属組織一覧と離脱（設計書 §6.6・AC-E01）。
 *
 * チームの加盟操作者にだけ親（affiliations）が描画する。離脱は組織の告知が届かなくなる操作なので、
 * 確認を挟む。一覧の取得は最後の要求の応答だけを反映し、離脱後は取り直す。
 */
const props = defineProps<{
  teamSlug: string
}>()

const emit = defineEmits<{
  changed: []
}>()

const { t } = useI18n()
const toast = useToast()
const { listOrganizations, leaveOrganization } = useTeamAffiliationApi()

const items = ref<TeamOrgSummary[]>([])
const loading = ref(true)
const loadFailed = ref(false)

const leaveTarget = ref<TeamOrgSummary | null>(null)
const showLeaveConfirm = ref(false)
const leaving = ref(false)
const leaveFailed = ref(false)

let requestSeq = 0

async function load() {
  const seq = ++requestSeq
  loading.value = true
  loadFailed.value = false
  try {
    const result = await listOrganizations(props.teamSlug)
    if (seq !== requestSeq) return
    items.value = result
  }
  catch {
    if (seq !== requestSeq) return
    // 取得失敗は「所属なし」に見せず、エラー表示にする（症状を隠さない）。
    items.value = []
    loadFailed.value = true
  }
  if (seq === requestSeq) loading.value = false
}

watch(() => props.teamSlug, () => void load())
onBeforeUnmount(() => {
  requestSeq++
})

function askLeave(org: TeamOrgSummary) {
  leaveTarget.value = org
  leaveFailed.value = false
  showLeaveConfirm.value = true
}

async function confirmLeave() {
  const slug = leaveTarget.value?.slug
  if (!slug || leaving.value) return
  leaving.value = true
  leaveFailed.value = false
  try {
    await leaveOrganization(props.teamSlug, slug)
    showLeaveConfirm.value = false
    toast.add({ severity: 'success', summary: t('teamAffiliations.organizations.left'), life: 3000 })
    emit('changed')
    await load()
  }
  catch {
    // 失敗は確認ダイアログ内に表示して残す（離脱できたように見せない）。
    leaveFailed.value = true
  }
  finally {
    leaving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="team-affiliations-organizations">
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="organizations-load-error">
      {{ t('teamAffiliations.organizations.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="organizations-empty"
    >
      {{ t('teamAffiliations.organizations.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="org in items"
        :key="org.slug"
        class="flex flex-wrap items-center justify-between gap-3 py-3"
        data-testid="org-row"
      >
        <div class="min-w-0 flex-1 space-y-1">
          <NuxtLink
            :to="`/organizations/${org.slug}`"
            class="font-medium text-primary-600 hover:underline"
          >
            {{ org.name }}
          </NuxtLink>
          <p v-if="org.teamGroup?.name" class="text-xs text-surface-500">
            {{ t('teamAffiliations.organizations.group', { group: org.teamGroup.name }) }}
          </p>
        </div>
        <Button
          :label="t('teamAffiliations.organizations.leave')"
          icon="pi pi-sign-out"
          size="small"
          severity="danger"
          outlined
          :data-testid="`org-leave-${org.slug}`"
          @click="askLeave(org)"
        />
      </li>
    </ul>

    <Dialog
      v-model:visible="showLeaveConfirm"
      modal
      :header="t('teamAffiliations.organizations.leave_confirm_title')"
      class="w-full max-w-md"
    >
      <div class="space-y-3 text-sm">
        <p>{{ t('teamAffiliations.organizations.leave_confirm', { org: leaveTarget?.name ?? '' }) }}</p>
        <Message v-if="leaveFailed" severity="error" data-testid="org-leave-error">
          {{ t('teamAffiliations.organizations.leave_error') }}
        </Message>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="showLeaveConfirm = false" />
        <Button
          :label="t('teamAffiliations.organizations.leave')"
          severity="danger"
          :loading="leaving"
          :disabled="leaving"
          data-testid="org-leave-confirm"
          @click="confirmLeave"
        />
      </template>
    </Dialog>
  </div>
</template>
