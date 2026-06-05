<script setup lang="ts">
/**
 * F15.4 Phase 5-γ/δ 未ログイン公開店舗詳細ページ
 *
 * 設計書: docs/features/F15.4_phase5_team_public_detail.md §5.2 / §6
 *
 * - middleware なし（未ログインで閲覧可能）。
 * - バックエンド `GET /api/v1/public/teams/{id}` を呼ぶ（permitAll + レート制限 60/min/IP）。
 * - 404 は createError({ statusCode: 404 }) でハンドリング。
 * - 表示要素: ヘッダー / 基本情報 / 理念 / 地図 (Google Maps iframe) / ログイン誘導 CTA。
 * - メンバー一覧・連絡先・告知・チャット等は一切表示しない（抑制 DTO のため取得もしない）。
 *
 * Phase 5-δ: CSP (`frame-src https://www.google.com`) をページ単位の `<meta http-equiv>` で付与する。
 *   フロント全体には現状 CSP ヘッダが未導入のため、Google Maps iframe を許容する本ページに限定して
 *   ページ内 meta 形式で宣言する。将来全体に CSP ヘッダ（nuxt-security 等）を導入する際は、
 *   `frame-src 'self' https://www.google.com` をホワイトリストへ移行すること。
 */
import type { TeamPublicDetailResponse } from '~/types/team'

definePageMeta({
  layout: 'default',
})

const route = useRoute()
const router = useRouter()
const teamApi = useTeamApi()
const { templateLabel } = useScopeLabels()
const { t } = useI18n()

const teamId = computed(() => {
  const raw = route.params.id
  const idStr = Array.isArray(raw) ? raw[0] : raw
  if (!idStr) {
    throw createError({ statusCode: 404, statusMessage: 'Team not found' })
  }
  return String(idStr)
})

const team = ref<TeamPublicDetailResponse | null>(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await teamApi.getPublicTeam(teamId.value)
    team.value = res.data
  } catch (error) {
    // 404 / archived / PRIVATE / 削除済みはすべてバックエンドで 404 となる
    const status =
      typeof error === 'object' && error !== null && 'response' in error
        ? (error as { response?: { status?: number } }).response?.status
        : undefined
    if (status === 404) {
      throw createError({ statusCode: 404, statusMessage: 'Team not found' })
    }
    // それ以外（ネットワーク / 429 / 5xx）はそのまま投げる
    throw error
  } finally {
    loading.value = false
  }
}

await load()

// === 表示用 computed ===
const displayName = computed(() => team.value?.name ?? '')
const templateText = computed(() => {
  const tpl = team.value?.template
  if (!tpl) return null
  return templateLabel[tpl] ?? tpl
})
const locationText = computed(() => {
  const pref = team.value?.prefecture ?? ''
  const city = team.value?.city ?? ''
  return [pref, city].filter(Boolean).join(' ')
})
const hasLocation = computed(() => locationText.value.length > 0)
const hasMap = computed(() => Boolean(team.value?.mapEmbedUrl))
const hasPhilosophy = computed(() => Boolean(team.value?.philosophy))
const hasHomepage = computed(() => Boolean(team.value?.homepageUrl))
const hasMemberCount = computed(
  () => team.value?.memberCount !== null && team.value?.memberCount !== undefined,
)

function formatEstablishedDate(): string {
  const t2 = team.value
  if (!t2 || !t2.establishedDate) return ''
  const precision = t2.establishedDatePrecision ?? 'FULL'
  const d = new Date(t2.establishedDate)
  if (Number.isNaN(d.getTime())) return t2.establishedDate
  const y = d.getFullYear()
  const m = d.getMonth() + 1
  const day = d.getDate()
  if (precision === 'YEAR') return `${y}`
  if (precision === 'YEAR_MONTH') return `${y}-${String(m).padStart(2, '0')}`
  return `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

// === SEO / OGP / CSP ===
// CSP: 本ページ限定で Google Maps iframe を許容（設計書 §5.2）。
// frame-src と child-src を両方指定し旧 UA 互換を確保する。
// 既定の self / data: / https: のオリジンを含めつつ、frame だけは厳格に絞り込む。
const PUBLIC_TEAM_CSP =
  "default-src 'self' https: data: blob:; " +
  "img-src 'self' https: data: blob:; " +
  "style-src 'self' 'unsafe-inline' https:; " +
  "script-src 'self' 'unsafe-inline' 'unsafe-eval' https:; " +
  "frame-src 'self' https://www.google.com; " +
  "child-src 'self' https://www.google.com"

useHead(() => ({
  title: team.value?.name ?? t('publicTeamDetail.notFound'),
  meta: [
    { name: 'robots', content: 'index,follow' },
    { property: 'og:title', content: team.value?.name ?? '' },
    {
      property: 'og:image',
      content: team.value?.bannerUrl ?? team.value?.iconUrl ?? '',
    },
    { property: 'og:type', content: 'website' },
    { 'http-equiv': 'Content-Security-Policy', content: PUBLIC_TEAM_CSP },
  ],
}))

function onBackToSearch() {
  // 同組織の他店舗一覧へ戻る経路は本 DTO に orgId を含まないため、
  // 戻る操作は前ページ（検索ページなど）への履歴 back に委ねる。
  router.back()
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <!-- ローディング（実質 await load() で待っているため通常は描画されない） -->
    <div v-if="loading" class="space-y-4" data-testid="public-team-loading">
      <Skeleton width="100%" height="200px" />
      <Skeleton width="60%" height="2rem" />
      <Skeleton width="100%" height="1rem" />
      <Skeleton width="100%" height="1rem" />
    </div>

    <template v-else-if="team">
      <!-- 戻るリンク -->
      <div class="mb-4">
        <Button
          :label="$t('publicTeamDetail.backToSearch')"
          icon="pi pi-arrow-left"
          severity="secondary"
          text
          data-testid="public-team-back-button"
          @click="onBackToSearch"
        />
      </div>

      <!-- ヘッダー -->
      <div
        class="relative mb-6 overflow-hidden rounded-lg border border-surface-200 bg-surface-0"
        data-testid="public-team-header"
      >
        <div
          v-if="team.bannerUrl"
          class="h-40 bg-cover bg-center sm:h-56"
          :style="{ backgroundImage: `url(${team.bannerUrl})` }"
          aria-hidden="true"
        />
        <div v-else class="h-32 bg-gradient-to-r from-primary/20 to-primary/5 sm:h-40" />

        <div class="flex items-center gap-4 p-4 sm:p-6">
          <Avatar
            :image="team.iconUrl ?? undefined"
            :label="team.iconUrl ? undefined : displayName.charAt(0)"
            shape="circle"
            size="xlarge"
            class="border-2 border-surface-0 shadow"
          />
          <div class="min-w-0 flex-1">
            <h1 class="truncate text-2xl font-bold">{{ displayName }}</h1>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <Tag
                v-if="templateText"
                :value="templateText"
                severity="info"
              />
              <span
                v-if="hasMemberCount && team.memberCount !== null"
                class="text-sm text-gray-600"
                data-testid="public-team-member-count"
              >
                <i class="pi pi-users mr-1" aria-hidden="true" />
                {{ team.memberCount }}{{ $t('publicTeamDetail.memberCountSuffix') }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 基本情報 -->
      <section
        class="mb-6 rounded-lg border border-surface-200 bg-surface-0 p-4 sm:p-6"
        data-testid="public-team-basic-info"
      >
        <dl class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div v-if="hasLocation">
            <dt class="text-xs font-medium text-gray-500">
              <i class="pi pi-map-marker mr-1" aria-hidden="true" />
              {{ $t('organizationTeamSearch.prefectureLabel') }}
            </dt>
            <dd class="mt-1 text-base">{{ locationText }}</dd>
          </div>

          <div v-if="team.establishedDate">
            <dt class="text-xs font-medium text-gray-500">
              {{ $t('publicTeamDetail.establishedLabel') }}
            </dt>
            <dd class="mt-1 text-base">{{ formatEstablishedDate() }}</dd>
          </div>

          <div v-if="hasHomepage" class="sm:col-span-2">
            <dt class="text-xs font-medium text-gray-500">
              {{ $t('publicTeamDetail.homepageLabel') }}
            </dt>
            <dd class="mt-1">
              <a
                :href="team.homepageUrl ?? '#'"
                target="_blank"
                rel="noopener noreferrer"
                class="break-all text-primary hover:underline"
              >
                <i class="pi pi-external-link mr-1" aria-hidden="true" />
                {{ team.homepageUrl }}
              </a>
            </dd>
          </div>
        </dl>
      </section>

      <!-- 理念 -->
      <section
        v-if="hasPhilosophy"
        class="mb-6 rounded-lg border border-surface-200 bg-surface-0 p-4 sm:p-6"
        data-testid="public-team-philosophy"
      >
        <h2 class="mb-3 text-lg font-semibold">
          {{ $t('publicTeamDetail.philosophyTitle') }}
        </h2>
        <p class="whitespace-pre-line text-sm leading-relaxed text-gray-700">
          {{ team.philosophy }}
        </p>
      </section>

      <!-- 地図 -->
      <section
        v-if="hasMap"
        class="mb-6 rounded-lg border border-surface-200 bg-surface-0 p-4 sm:p-6"
        data-testid="public-team-map"
      >
        <h2 class="mb-3 text-lg font-semibold">
          {{ $t('publicTeamDetail.mapTitle') }}
        </h2>
        <div class="overflow-hidden rounded-md border border-surface-200">
          <iframe
            v-if="team.mapEmbedUrl"
            :src="team.mapEmbedUrl"
            width="100%"
            height="400"
            style="border: 0"
            loading="lazy"
            referrerpolicy="no-referrer-when-downgrade"
            :title="$t('publicTeamDetail.mapTitle')"
            data-testid="public-team-map-iframe"
          />
        </div>
      </section>

      <!-- ログイン誘導 CTA -->
      <PublicTeamLoginCta :team-id="team.id" />
    </template>
  </div>
</template>
