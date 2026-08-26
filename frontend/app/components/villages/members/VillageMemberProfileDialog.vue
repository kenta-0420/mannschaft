<script setup lang="ts">
/**
 * F17.2 Wave3 ⑥ 村人ミニプロフィール Dialog — 新規作成。
 *
 * 設計書: docs/features/F17.2_village_events_activation.md §9.5
 *
 * 村メンバー一覧（VillageMembersTable）の名前クリックで開く。中身は対象村人の
 * （現在の村での）ニックネーム・ロール ＋ 所属村一覧（`GET /users/{userId}/villages`。
 * 村名/村紋/カテゴリのみ・ニックネームは返さない・§9.3）。
 *
 * - 自分自身を開いた場合のみ「この村での所属を公開する」トグルを表示する
 *   （`PATCH .../memberships/me/profile-visibility`・per-village 設定・§9.2/§9.5(b)）。
 * - `GET /users/{userId}/villages` が 403（同居村なし or 公開村0件・AC-31）のときは
 *   「表示できる所属村はありません」の中立表示に留める（同居関係の有無を漏らさない）。
 *
 * 【既知の制約・BE 変更不可のため FE 側で吸収】
 * 自分の「公開トグル」初期状態は、BE が現在値を返す GET を持たないため
 * （`ProfileVisibilityResponse` は PATCH の書込エコーのみ）、同じ
 * `GET /users/{userId}/villages`（=自分の所属村一覧）の結果に現在の村 ID が
 * 含まれるかどうかから逆算する。これは村の `visibility=PUBLIC` の場合のみ厳密に正しい
 * （§9.4 のフィルタが `profilePublic=TRUE AND village.visibility=PUBLIC` のため）。
 * `visibility=UNLISTED` の村では一覧に現れないため、既にトグル ON でも初期表示が OFF に
 * 見えることがある（実際の公開状態は毎回 PATCH で明示的に上書きされるため実害はない）。
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import ToggleSwitch from 'primevue/toggleswitch'

import type { MembershipResponse, VillageRole } from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

type UserVillageSummaryResponse = import('~/types/generated').components['schemas']['UserVillageSummaryResponse']

const props = defineProps<{
  visible: boolean
  villageId: string
  member: MembershipResponse | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const { t } = useI18n()
const affinityApi = useVillageAffinityApi()
const { captureQuiet } = useErrorReport()
const { showError } = useNotification()
const { currentUserId } = useVillageContext()

const visibleModel = computed<boolean>({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

// =============================================================================
// 使い方ガイド（/手助けモーダル ②インライン折りたたみ方式）
// =============================================================================
const showGuide = ref(false)

// =============================================================================
// 所属村一覧の取得
// =============================================================================

const loading = ref(false)
/** 403（同居村なし or 公開村0件）— 同居関係の有無を漏らさない中立表示に使う。 */
const forbidden = ref(false)
/** 403 以外の予期しないエラー（ネットワーク断・5xx 等）。 */
const loadError = ref(false)
const villages = ref<UserVillageSummaryResponse[]>([])

function extractStatus(err: unknown): number | null {
  if (typeof err !== 'object' || err === null) return null
  const e = err as { statusCode?: number, response?: { status?: number } }
  return e.statusCode ?? e.response?.status ?? null
}

/** 自分自身を開いているか（USER 主体・自分の user id と一致）。 */
const isSelf = computed<boolean>(() => {
  const m = props.member
  if (!m || currentUserId.value == null) return false
  return m.subjectType === 'USER' && m.subjectId === currentUserId.value
})

// 自分の公開トグル（PATCH の書込エコーで確定させる。初期値は下記 load() 参照）。
const publicToggle = ref(false)
const toggleSubmitting = ref(false)

async function load() {
  const m = props.member
  if (!m) return
  loading.value = true
  forbidden.value = false
  loadError.value = false
  villages.value = []
  try {
    villages.value = await affinityApi.getUserVillages(m.subjectId)
    if (isSelf.value) {
      // 自分の所属村一覧に「今開いているこの村」が含まれるか＝現在の公開状態
      // （visibility=PUBLIC の村でのみ厳密に正しい。上記コメント参照）。
      publicToggle.value = villages.value.some(v => v.villageId === props.villageId)
    }
  }
  catch (err) {
    const status = extractStatus(err)
    if (status === 403) {
      forbidden.value = true
      if (isSelf.value) publicToggle.value = false
    }
    else {
      loadError.value = true
      captureQuiet(err, { context: 'VillageMemberProfileDialog: 所属村一覧取得失敗' })
    }
  }
  finally {
    loading.value = false
  }
}

watch(
  () => [props.visible, props.member?.id] as const,
  ([visible, memberId]) => {
    if (visible && memberId) void load()
  },
  { immediate: true },
)

// =============================================================================
// 公開トグル切替（自分自身のみ）
// =============================================================================

async function onToggle(newValue: boolean) {
  if (toggleSubmitting.value) return
  const prev = publicToggle.value
  publicToggle.value = newValue
  toggleSubmitting.value = true
  try {
    const res = await affinityApi.updateMyProfileVisibility(props.villageId, {
      profilePublic: newValue,
    })
    publicToggle.value = res.profilePublic
    // 一覧側も最新状態に合わせて再取得（自分のプレビュー表示を同期）。
    await load()
  }
  catch (err) {
    // 失敗時は表示を戻す（対処療法で握りつぶさず、状態を正直に巻き戻す）。
    publicToggle.value = prev
    captureQuiet(err, { context: 'VillageMemberProfileDialog: 公開トグル更新失敗' })
    showError(t('village.error.generic'))
  }
  finally {
    toggleSubmitting.value = false
  }
}

// =============================================================================
// 表示ヘルパ
// =============================================================================

function displayName(m: MembershipResponse | null): string {
  if (!m) return ''
  return m.displayName ?? `#${m.subjectId}`
}

function roleLabel(role: VillageRole | undefined): string {
  if (!role) return ''
  return t(`village.role.${role}`)
}

function onClose() {
  visibleModel.value = false
  showGuide.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    modal
    :draggable="false"
    :header="t('village.profile.title')"
    :style="{ width: '28rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => !v && onClose()"
  >
    <div v-if="member" class="flex flex-col gap-4">
      <!-- 使い方トグル（本文先頭・右寄せ・/手助けモーダル ②方式） -->
      <div class="flex justify-end">
        <Button
          :label="t('village.memberProfileGuide.toggleLabel')"
          :icon="showGuide ? 'pi pi-chevron-up' : 'pi pi-question-circle'"
          text
          size="small"
          :aria-expanded="showGuide"
          data-testid="member-profile-guide-toggle"
          @click="showGuide = !showGuide"
        />
      </div>
      <Transition name="fade">
        <div
          v-if="showGuide"
          class="rounded-lg border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="member-profile-guide-panel"
        >
          <VillageMemberProfileGuideContent />
        </div>
      </Transition>

      <!-- 対象村人（現在の村での表示） -->
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-100 dark:bg-surface-700">
          <i class="pi pi-user text-surface-500" aria-hidden="true" />
        </div>
        <div>
          <div class="font-semibold">
            {{ displayName(member) }}
          </div>
          <div class="text-xs text-surface-500">
            {{ roleLabel(member.role) }}
          </div>
        </div>
      </div>

      <!-- 自分自身のときのみ: 公開トグル -->
      <div
        v-if="isSelf"
        class="flex items-center justify-between gap-4 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
      >
        <div>
          <div class="text-sm font-medium">
            {{ t('village.profile.villages.publicToggle') }}
          </div>
          <div class="text-xs text-surface-500">
            {{ t('village.profile.villages.publicToggleHint') }}
          </div>
        </div>
        <ToggleSwitch
          :model-value="publicToggle"
          :disabled="toggleSubmitting"
          data-testid="member-profile-public-toggle"
          :aria-label="t('village.profile.villages.publicToggle')"
          @update:model-value="onToggle"
        />
      </div>

      <!-- 所属村一覧 -->
      <div>
        <h3 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ t('village.profile.villages.title') }}
        </h3>

        <div v-if="loading" class="space-y-2">
          <Skeleton height="3rem" />
          <Skeleton height="3rem" />
        </div>

        <div v-else-if="loadError" class="flex flex-col items-center gap-2 py-4">
          <i class="pi pi-exclamation-triangle text-xl text-orange-400" />
          <p class="text-sm text-surface-500">
            {{ t('village.error.generic') }}
          </p>
          <Button
            :label="t('village.feed.retry')"
            icon="pi pi-refresh"
            size="small"
            text
            @click="load"
          />
        </div>

        <!-- 403（同居村なし or 公開村0件）も 0 件表示も同じ中立文言に揃える
             （同居関係の有無を漏らさない・§9.4） -->
        <DashboardEmptyState
          v-else-if="forbidden || villages.length === 0"
          icon="pi pi-map"
          :message="t('village.profile.villages.empty')"
        />

        <ul v-else class="flex flex-col gap-2">
          <li v-for="v in villages" :key="v.villageId">
            <NuxtLink
              :to="`/villages/${v.villageId}`"
              class="flex items-center gap-3 rounded-lg border border-surface-200 p-2 hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
              @click="onClose"
            >
              <img
                v-if="v.villageMonshoUrl"
                :src="v.villageMonshoUrl"
                alt=""
                class="h-8 w-8 shrink-0 rounded-full object-cover"
              >
              <div v-else class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-100 dark:bg-surface-700">
                <i class="pi pi-image text-xs text-surface-400" aria-hidden="true" />
              </div>
              <div class="min-w-0">
                <div class="truncate text-sm font-medium">
                  {{ v.villageName }}
                </div>
                <div v-if="v.category" class="text-xs text-surface-500">
                  {{ v.category }}
                </div>
              </div>
            </NuxtLink>
          </li>
        </ul>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="onClose"
      />
    </template>
  </Dialog>
</template>
