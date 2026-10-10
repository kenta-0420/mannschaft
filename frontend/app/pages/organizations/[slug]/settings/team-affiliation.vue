<script setup lang="ts">
import type {
  ApplicationGroupMode,
  TeamAffiliationSettings,
} from '~/composables/useTeamAffiliationApi'

/**
 * F01.2.1 8-A — 組織の「チーム加盟の設定」画面。
 *
 * - 加盟申請の受付 on/off、チームグループ機能 on/off、申請時のグループ選択（OFF/OPTIONAL/REQUIRED）、案内文
 * - 受付を off にするときは確認を挟む（受付済みの申請件数を表示。設計書 §4.3）
 * - REQUIRED のまま全グループを削除すると実効 OPTIONAL に落ちる。その旨を警告表示する（AC-G112 の画面側）
 *
 * アクセス制御は `org-role-guard`（ADMIN でなければトースト＋組織トップへ。AC-K01）。BE も ADMIN 専用で 403 を返す。
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13
 */
definePageMeta({
  layout: 'organization',
  middleware: ['auth', 'org-role-guard'],
})

const { t } = useI18n()
const route = useRoute()
const toast = useToast()
const orgSlug = String(route.params.slug)

const { getSettings, updateSettings } = useTeamAffiliationApi()

const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const saveFailed = ref(false)
const showGuide = ref(false)
const showTurnOffConfirm = ref(false)

/** サーバーが最後に返した値（off への切り替え判定・格下げ警告の基準）。 */
const saved = ref<TeamAffiliationSettings | null>(null)

const applicationEnabled = ref(false)
const groupsEnabled = ref(false)
const groupMode = ref<ApplicationGroupMode>('OFF')
const guidance = ref('')

/** BE の案内文上限（UpdateTeamAffiliationSettingsRequest の @Size(max = 500)。設計書 §10.2）。 */
const GUIDANCE_MAX = 500

const groupModeOptions = computed(() => [
  { value: 'OFF', label: t('teamAffiliation.settings.group_mode_off') },
  { value: 'OPTIONAL', label: t('teamAffiliation.settings.group_mode_optional') },
  { value: 'REQUIRED', label: t('teamAffiliation.settings.group_mode_required') },
])

/** REQUIRED を設定しているが、グループが1件も無く実効 OPTIONAL に格下げされている。 */
const requiredDegraded = computed(
  () =>
    saved.value?.applicationGroupMode === 'REQUIRED'
    && saved.value?.effectiveApplicationGroupMode !== 'REQUIRED',
)

const pendingCount = computed(() => saved.value?.pendingApplicationCount ?? 0)

function applyToForm(settings: TeamAffiliationSettings) {
  saved.value = settings
  applicationEnabled.value = settings.teamApplicationEnabled === true
  groupsEnabled.value = settings.teamGroupsEnabled === true
  groupMode.value = settings.applicationGroupMode ?? 'OFF'
  guidance.value = settings.applicationGuidance ?? ''
}

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    applyToForm(await getSettings(orgSlug))
  }
  catch {
    // 読み込み失敗は空の設定に見せず、エラー表示にする（症状を隠さない）。
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

const turningOff = computed(
  () => saved.value?.teamApplicationEnabled === true && !applicationEnabled.value,
)

/** 保存ボタン。受付を off にする場合は確認ダイアログを先に挟む。 */
function onSaveClick() {
  if (turningOff.value) {
    showTurnOffConfirm.value = true
    return
  }
  void save()
}

async function save() {
  showTurnOffConfirm.value = false
  saving.value = true
  saveFailed.value = false
  try {
    const result = await updateSettings(orgSlug, {
      teamApplicationEnabled: applicationEnabled.value,
      teamGroupsEnabled: groupsEnabled.value,
      applicationGroupMode: groupMode.value,
      applicationGuidance: guidance.value,
    })
    applyToForm(result)
    toast.add({ severity: 'success', summary: t('teamAffiliation.settings.saved'), life: 3000 })
  }
  catch {
    // 失敗はフォーム上に表示する。成功トーストにはしない。
    saveFailed.value = true
  }
  finally {
    saving.value = false
  }
}

await load()
</script>

<template>
  <div class="mx-auto max-w-2xl space-y-6 p-4">
    <PageHeader
      :title="t('teamAffiliation.settings.title')"
      :back-to="`/organizations/${orgSlug}/member-teams`"
      help
      @help="showGuide = true"
    />

    <div v-if="loading" class="flex items-center justify-center py-10">
      <ProgressSpinner />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="settings-load-error">
      {{ t('teamAffiliation.settings.load_error') }}
    </Message>

    <form
      v-else
      class="space-y-6"
      data-testid="team-affiliation-settings-form"
      @submit.prevent="onSaveClick"
    >
      <SectionCard :title="t('teamAffiliation.settings.section_application')">
        <div class="flex items-start justify-between gap-4">
          <div class="flex-1">
            <label class="block text-sm font-medium" for="settings-application-enabled">
              {{ t('teamAffiliation.settings.application_enabled') }}
            </label>
            <p class="mt-1 text-xs text-surface-500">
              {{ t('teamAffiliation.settings.application_enabled_hint') }}
            </p>
          </div>
          <ToggleSwitch
            v-model="applicationEnabled"
            input-id="settings-application-enabled"
            data-testid="settings-application-enabled"
          />
        </div>

        <div class="mt-4">
          <label class="mb-1 block text-sm font-medium" for="settings-guidance">
            {{ t('teamAffiliation.settings.guidance') }}
          </label>
          <Textarea
            id="settings-guidance"
            v-model="guidance"
            rows="4"
            :maxlength="GUIDANCE_MAX"
            class="w-full"
            data-testid="settings-guidance"
          />
          <p class="mt-1 text-right text-xs text-surface-400" data-testid="settings-guidance-count">
            {{ guidance.length }} / {{ GUIDANCE_MAX }}
          </p>
        </div>
      </SectionCard>

      <SectionCard :title="t('teamAffiliation.settings.section_groups')">
        <div class="flex items-start justify-between gap-4">
          <div class="flex-1">
            <label class="block text-sm font-medium" for="settings-groups-enabled">
              {{ t('teamAffiliation.settings.groups_enabled') }}
            </label>
            <p class="mt-1 text-xs text-surface-500">
              {{ t('teamAffiliation.settings.groups_enabled_hint') }}
            </p>
          </div>
          <ToggleSwitch
            v-model="groupsEnabled"
            input-id="settings-groups-enabled"
            data-testid="settings-groups-enabled"
          />
        </div>

        <div class="mt-4">
          <label class="mb-1 block text-sm font-medium" for="settings-group-mode">
            {{ t('teamAffiliation.settings.group_mode') }}
          </label>
          <Select
            v-model="groupMode"
            input-id="settings-group-mode"
            :options="groupModeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="settings-group-mode"
          />
          <Message
            v-if="requiredDegraded"
            severity="warn"
            class="mt-2"
            data-testid="required-mode-degraded"
          >
            {{ t('teamAffiliation.settings.required_mode_degraded') }}
          </Message>
        </div>

        <NuxtLink
          v-if="groupsEnabled"
          :to="`/organizations/${orgSlug}/member-teams?view=groups`"
          class="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary-600 hover:underline dark:text-primary-400"
          data-testid="manage-groups-link"
        >
          <i class="pi pi-sitemap" aria-hidden="true" />
          {{ t('teamAffiliation.settings.manage_groups') }}
        </NuxtLink>
      </SectionCard>

      <Message v-if="saveFailed" severity="error" data-testid="settings-save-error">
        {{ t('teamAffiliation.settings.save_error') }}
      </Message>

      <div class="flex justify-end">
        <Button
          type="button"
          :label="t('button.save')"
          :loading="saving"
          data-testid="settings-save"
          @click="onSaveClick"
        />
      </div>
    </form>

    <Dialog
      v-model:visible="showTurnOffConfirm"
      modal
      :header="t('teamAffiliation.settings.turn_off_confirm_title')"
      class="w-full max-w-md"
    >
      <div data-testid="turn-off-confirm" class="space-y-2 text-sm">
        <p>{{ t('teamAffiliation.settings.turn_off_confirm') }}</p>
        <p v-if="pendingCount > 0">
          {{ t('teamAffiliation.settings.turn_off_pending_confirm', { count: pendingCount }) }}
        </p>
      </div>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="showTurnOffConfirm = false"
        />
        <Button
          :label="t('teamAffiliation.settings.turn_off_confirm_ok')"
          severity="danger"
          data-testid="turn-off-confirm-ok"
          @click="save"
        />
      </template>
    </Dialog>

    <TeamAffiliationGuideModal v-model:visible="showGuide" />
  </div>
</template>
