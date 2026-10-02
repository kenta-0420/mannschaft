<script setup lang="ts">
/**
 * F20.3 Wave2b: ベータ特典セクション（個人/チーム/組織 課金管理画面に同居・design 04 §1 B-1/B-2）。
 *
 * - 申請ボタンは置かない（自動付与のため）。個人スコープのみ充足進捗（進捗バー）を表示する。
 * - 称号バッジ（BETA_TESTER）は F04.7 のバッジ表示（gamification.vue の視覚パターン）を踏襲。
 *   badges は scope=PLATFORM の system badge 行（DB シード済み）で、専用の照会 API はまだ無いため、
 *   「有効な INDIVIDUAL 特典を保有しているか」から表示要否を導出する（badge 一覧 API 自体は Phase 対象外）。
 * - 「永久/一生/無期限保証」の語は使わない（AC-13）。個人特典は「サービス提供期間中無償」固定文言。
 */
import dayjs from 'dayjs'
import type { BetaPerkEligibilityStatus, BetaPerkGrantItem, BetaPerkScopeKind } from '~/composables/useBetaPerkApi'

const props = defineProps<{
  scopeKind: BetaPerkScopeKind
  /** USER スコープでは API 呼び出しに使わないため空文字で可。 */
  scopeId: string
}>()

const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const { formatDate } = useDatetime()
const betaPerkApi = useBetaPerkApi()

const loading = ref(true)
const grants = ref<BetaPerkGrantItem[]>([])
const eligibility = ref<BetaPerkEligibilityStatus | null>(null)
const expandedIdx = ref<Set<number>>(new Set())
const showHelp = ref(false)

async function load() {
  loading.value = true
  try {
    if (props.scopeKind === 'USER') {
      const res = await betaPerkApi.getMyBetaPerks()
      grants.value = res.data.grants ?? []
      eligibility.value = res.data.eligibility ?? null
    }
    else {
      const res = await betaPerkApi.getScopedBetaPerks(props.scopeKind, props.scopeId)
      grants.value = res.data ?? []
      eligibility.value = null
    }
  }
  catch (err) {
    handleApiError(err, 'billing.betaPerks.load')
  }
  finally {
    loading.value = false
  }
}

/** 取消済みも履歴として表示する（design betaPerks.revoked キー・非管理画面向け）。 */
const activeGrants = computed(() => grants.value.filter(g => !g.revokedAt))
const eligibilityMetrics = computed(() => eligibility.value?.metrics ?? [])
const hasEligibilitySection = computed(
  () => props.scopeKind === 'USER' && !!eligibility.value && eligibilityMetrics.value.length > 0,
)
/** 称号バッジ（B-1 のみ）: 有効な個人特典を保有しているかで表示要否を導出する。 */
const hasActiveIndividualGrant = computed(() =>
  props.scopeKind === 'USER' && activeGrants.value.some(g => g.grantKind === 'INDIVIDUAL'),
)
const showEmptyState = computed(() => grants.value.length === 0 && !hasEligibilitySection.value)

function featureName(key: string): string {
  return t(`billing.features.${key.replace(/\./g, '_')}.name`, key)
}

function toggleFeatures(idx: number) {
  const next = new Set(expandedIdx.value)
  if (next.has(idx)) next.delete(idx)
  else next.add(idx)
  expandedIdx.value = next
}

function isExpanded(idx: number): boolean {
  return expandedIdx.value.has(idx)
}

/** 有効期限90日前から更新注意行を表示する（B-2運用・design 04§1）。 */
function isNearExpiry(validUntil: string | undefined): boolean {
  if (!validUntil) return false
  const days = dayjs(validUntil).diff(dayjs(), 'day')
  return days >= 0 && days <= 90
}

function metricLabel(metricKey: string | undefined): string {
  if (!metricKey) return ''
  return t(`billing.betaPerks.eligibility.metric.${metricKey}`, metricKey)
}

function metricPercent(actual: number | undefined, required: number | undefined): number {
  if (!required || required <= 0) return 0
  return Math.min(100, Math.round(((actual ?? 0) / required) * 100))
}

onMounted(load)

defineExpose({ load })
</script>

<template>
  <SectionCard>
    <div class="mb-4 flex items-center justify-between gap-2">
      <h2 class="text-base font-semibold">
        {{ t('billing.betaPerks.sectionTitle') }}
      </h2>
      <Button
        icon="pi pi-question-circle"
        :label="t('billing.betaPerks.helpButton')"
        text
        size="small"
        data-testid="beta-perks-help-button"
        @click="showHelp = true"
      />
    </div>

    <BillingHelpDialog v-model:visible="showHelp" variant="betaPerks" />

    <PageLoading v-if="loading" />

    <template v-else>
      <p v-if="showEmptyState" class="text-sm text-surface-500">
        {{ t('billing.betaPerks.noGrant') }}
      </p>

      <!-- 付与済み特典一覧 -->
      <div v-if="grants.length > 0" class="space-y-3">
        <div
          v-for="(g, idx) in grants"
          :key="g.grantId ?? idx"
          class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
          :class="{ 'opacity-60': !!g.revokedAt }"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex items-center gap-2">
              <Tag :value="t('billing.betaPerks.phaseBadge', { phase: g.betaPhase })" severity="success" />
              <Tag v-if="g.revokedAt" :value="t('billing.betaPerks.revoked')" severity="danger" />
            </div>
            <span class="text-xs text-surface-500">
              {{ t('billing.betaPerks.grantedAt', { date: formatDate(g.grantedAt) }) }}
            </span>
          </div>

          <p class="mt-2 text-sm">
            <template v-if="g.grantKind === 'INDIVIDUAL'">
              {{ t('billing.betaPerks.noExpiryIndividual') }}
            </template>
            <template v-else>
              {{ g.validUntil
                ? t('billing.betaPerks.expiryTeamOrg', { date: formatDate(g.validUntil) })
                : t('billing.betaPerks.noExpiryIndividual') }}
            </template>
          </p>

          <p
            v-if="g.grantKind !== 'INDIVIDUAL' && !g.revokedAt && isNearExpiry(g.validUntil)"
            class="mt-1 text-xs text-amber-600 dark:text-amber-400"
          >
            {{ t('billing.betaPerks.renewalNote') }}
          </p>

          <p v-if="g.activeMemberCountSnapshot != null" class="mt-1 text-xs text-surface-500">
            {{ t('billing.betaPerks.memberSnapshot', { count: g.activeMemberCountSnapshot }) }}
          </p>

          <div class="mt-2">
            <Button
              :label="t('billing.betaPerks.featureListToggle')"
              :icon="isExpanded(idx) ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
              text
              size="small"
              @click="toggleFeatures(idx)"
            />
            <ul v-if="isExpanded(idx)" class="mt-1 space-y-1 pl-2 text-sm">
              <li v-for="fk in (g.featureKeys ?? [])" :key="fk" class="flex items-center gap-2">
                <i class="pi pi-check text-primary" />
                {{ featureName(fk) }}
              </li>
            </ul>
          </div>
        </div>
      </div>

      <!-- 称号バッジ（個人スコープ・有効な特典を保有時のみ・F04.7 の視覚パターンを踏襲） -->
      <div
        v-if="hasActiveIndividualGrant"
        class="mt-4 flex items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 dark:border-amber-900/50 dark:bg-amber-900/10"
        data-testid="beta-tester-badge"
      >
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-amber-100 text-2xl dark:bg-amber-900/30">
          🚀
        </div>
        <div>
          <p class="text-sm font-semibold">
            {{ t('billing.betaPerks.badge.name') }}
          </p>
          <p class="text-xs text-surface-500">
            {{ t('billing.betaPerks.badge.description') }}
          </p>
        </div>
      </div>

      <!-- 充足進捗（個人スコープ・criteria 未定義/eligibility=null 時は非表示・design 04§1） -->
      <div
        v-if="hasEligibilitySection"
        class="mt-5 border-t border-surface-200 pt-4 dark:border-surface-700"
        data-testid="beta-perks-eligibility"
      >
        <h3 class="mb-1 text-sm font-semibold">
          {{ t('billing.betaPerks.eligibility.title') }}
        </h3>
        <p class="mb-3 text-xs text-surface-500">
          {{ t('billing.betaPerks.eligibility.autoGrantNote') }}
        </p>
        <div class="space-y-3">
          <div v-for="m in eligibilityMetrics" :key="m.metricKey">
            <div class="mb-1 flex items-center justify-between text-xs">
              <span>{{ metricLabel(m.metricKey) }}</span>
              <span>{{ t('billing.betaPerks.eligibility.progress', { actual: m.actual, required: m.required }) }}</span>
            </div>
            <ProgressBar :value="metricPercent(m.actual, m.required)" :show-value="false" />
          </div>
        </div>
        <p v-if="eligibility?.eligible" class="mt-3 text-sm font-medium text-green-600 dark:text-green-400">
          {{ t('billing.betaPerks.eligibility.achieved') }}
        </p>
      </div>
    </template>
  </SectionCard>
</template>
