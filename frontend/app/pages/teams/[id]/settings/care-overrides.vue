<script setup lang="ts">
import type { CareLinkResponse } from '~/types/careLink'
import type { TeamCareOverrideRequest, TeamCareOverrideResponse } from '~/types/teamCareOverride'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)

const careLinkApi = useCareLinkApi()
const overrideApi = useTeamCareOverrideApi()
const notification = useNotification()

// ケアリンク一覧（自分が絡むもの）
const activeLinks = ref<CareLinkResponse[]>([])
// 上書き設定マップ（careLinkId -> override or null）
const overrideMap = ref<Map<number, TeamCareOverrideResponse | null>>(new Map())

const loading = ref(false)
const savingMap = ref<Map<number, boolean>>(new Map())
const deletingMap = ref<Map<number, boolean>>(new Map())

// 編集フォームマップ（careLinkId -> form）
const formMap = ref<Map<number, TeamCareOverrideRequest>>(new Map())

async function loadData() {
  loading.value = true
  try {
    // 見守り者・ケア対象者を合わせて取得
    const [watchersRes, recipientsRes] = await Promise.all([
      careLinkApi.getMyWatchers(),
      careLinkApi.getMyRecipients(),
    ])
    const allLinks = [
      ...watchersRes.data.filter(l => l.status === 'ACTIVE'),
      ...recipientsRes.data.filter(l => l.status === 'ACTIVE'),
    ]
    activeLinks.value = allLinks

    // 各リンクの上書き設定を取得
    const results = await Promise.allSettled(
      allLinks.map(link => overrideApi.getCareOverride(teamId, link.id)),
    )

    const newMap = new Map<number, TeamCareOverrideResponse | null>()
    const newFormMap = new Map<number, TeamCareOverrideRequest>()

    results.forEach((result, idx) => {
      const linkId = allLinks[idx].id
      if (result.status === 'fulfilled') {
        const override = result.value.data
        newMap.set(linkId, override)
        newFormMap.set(linkId, {
          notifyOnRsvp: override.notifyOnRsvp,
          notifyOnCheckin: override.notifyOnCheckin,
          notifyOnCheckout: override.notifyOnCheckout,
          notifyOnAbsentAlert: override.notifyOnAbsentAlert,
          notifyOnDismissal: override.notifyOnDismissal,
          disabled: override.disabled,
        })
      } else {
        // 404 = 上書き設定なし
        newMap.set(linkId, null)
        newFormMap.set(linkId, {
          notifyOnRsvp: true,
          notifyOnCheckin: true,
          notifyOnCheckout: true,
          notifyOnAbsentAlert: true,
          notifyOnDismissal: true,
          disabled: false,
        })
      }
    })

    overrideMap.value = newMap
    formMap.value = newFormMap
  } catch {
    notification.error(t('care.message.loadError'))
  } finally {
    loading.value = false
  }
}

function getForm(linkId: number): TeamCareOverrideRequest {
  return formMap.value.get(linkId) ?? {
    notifyOnRsvp: true,
    notifyOnCheckin: true,
    notifyOnCheckout: true,
    notifyOnAbsentAlert: true,
    notifyOnDismissal: true,
    disabled: false,
  }
}

function updateFormField<K extends keyof TeamCareOverrideRequest>(
  linkId: number,
  field: K,
  value: TeamCareOverrideRequest[K],
) {
  const existing = formMap.value.get(linkId) ?? {}
  formMap.value.set(linkId, { ...existing, [field]: value })
  // reactivity trigger
  formMap.value = new Map(formMap.value)
}

async function saveOverride(linkId: number) {
  savingMap.value.set(linkId, true)
  savingMap.value = new Map(savingMap.value)
  try {
    const body = getForm(linkId)
    const result = await overrideApi.upsertCareOverride(teamId, linkId, body)
    overrideMap.value.set(linkId, result.data)
    overrideMap.value = new Map(overrideMap.value)
    notification.success(t('care.message.saveOverrideSuccess'))
  } catch {
    notification.error(t('care.message.saveOverrideError'))
  } finally {
    savingMap.value.set(linkId, false)
    savingMap.value = new Map(savingMap.value)
  }
}

async function deleteOverride(linkId: number) {
  deletingMap.value.set(linkId, true)
  deletingMap.value = new Map(deletingMap.value)
  try {
    await overrideApi.deleteCareOverride(teamId, linkId)
    overrideMap.value.set(linkId, null)
    overrideMap.value = new Map(overrideMap.value)
    // フォームをデフォルトに戻す
    formMap.value.set(linkId, {
      notifyOnRsvp: true,
      notifyOnCheckin: true,
      notifyOnCheckout: true,
      notifyOnAbsentAlert: true,
      notifyOnDismissal: true,
      disabled: false,
    })
    formMap.value = new Map(formMap.value)
    notification.success(t('care.message.deleteOverrideSuccess'))
  } catch {
    notification.error(t('care.message.deleteOverrideError'))
  } finally {
    deletingMap.value.set(linkId, false)
    deletingMap.value = new Map(deletingMap.value)
  }
}

function getLinkLabel(link: CareLinkResponse): string {
  // 見守り者として表示か、ケア対象者として表示かを判断
  return `${link.watcherDisplayName} → ${link.careRecipientDisplayName}`
}

onMounted(() => loadData())
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <PageHeader :title="$t('care.page.teamOverride')" class="mb-4" />

    <p class="mb-6 text-sm text-surface-500">
      {{ $t('care.label.overrideNote') }}
    </p>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <div
      v-else-if="activeLinks.length === 0"
      class="rounded border border-dashed p-8 text-center text-surface-400"
    >
      {{ $t('care.label.noWatchers') }}
    </div>

    <div v-else class="flex flex-col gap-4">
      <div
        v-for="link in activeLinks"
        :key="link.id"
        class="rounded-lg border border-surface-200 p-5 dark:border-surface-700"
      >
        <!-- リンク情報ヘッダー -->
        <div class="mb-4 flex items-center justify-between">
          <div>
            <div class="font-semibold">{{ getLinkLabel(link) }}</div>
            <div class="text-sm text-surface-500">
              {{ $t(`care.category.${link.careCategory}`) }}
              · {{ $t(`care.relationship.${link.relationship}`) }}
            </div>
          </div>
          <Tag
            v-if="overrideMap.get(link.id)"
            :value="$t('care.label.disabled')"
            severity="warn"
            rounded
          />
        </div>

        <!-- 通知設定トグル群 -->
        <div class="mb-4 flex flex-col gap-3">
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('care.label.notifyOnRsvp') }}</label>
            <ToggleSwitch
              :model-value="getForm(link.id).notifyOnRsvp"
              @update:model-value="(v) => updateFormField(link.id, 'notifyOnRsvp', v)"
            />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('care.label.notifyOnCheckin') }}</label>
            <ToggleSwitch
              :model-value="getForm(link.id).notifyOnCheckin"
              @update:model-value="(v) => updateFormField(link.id, 'notifyOnCheckin', v)"
            />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('care.label.notifyOnCheckout') }}</label>
            <ToggleSwitch
              :model-value="getForm(link.id).notifyOnCheckout"
              @update:model-value="(v) => updateFormField(link.id, 'notifyOnCheckout', v)"
            />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('care.label.notifyOnAbsentAlert') }}</label>
            <ToggleSwitch
              :model-value="getForm(link.id).notifyOnAbsentAlert"
              @update:model-value="(v) => updateFormField(link.id, 'notifyOnAbsentAlert', v)"
            />
          </div>
          <div class="flex items-center justify-between">
            <label class="text-sm">{{ $t('care.label.notifyOnDismissal') }}</label>
            <ToggleSwitch
              :model-value="getForm(link.id).notifyOnDismissal"
              @update:model-value="(v) => updateFormField(link.id, 'notifyOnDismissal', v)"
            />
          </div>
          <div class="flex items-center justify-between border-t border-surface-200 pt-2 dark:border-surface-700">
            <label class="text-sm font-medium text-orange-600 dark:text-orange-400">
              {{ $t('care.label.disabled') }}
            </label>
            <ToggleSwitch
              :model-value="getForm(link.id).disabled"
              @update:model-value="(v) => updateFormField(link.id, 'disabled', v)"
            />
          </div>
        </div>

        <!-- アクションボタン -->
        <div class="flex gap-2">
          <Button
            :label="$t('care.button.saveOverride')"
            icon="pi pi-save"
            size="small"
            :loading="savingMap.get(link.id) ?? false"
            @click="saveOverride(link.id)"
          />
          <Button
            v-if="overrideMap.get(link.id)"
            :label="$t('care.button.deleteOverride')"
            icon="pi pi-trash"
            size="small"
            severity="danger"
            outlined
            :loading="deletingMap.get(link.id) ?? false"
            @click="deleteOverride(link.id)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
