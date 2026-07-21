<script setup lang="ts">
/**
 * F17.2 Wave3 村機能 — 加入前相性表示カード
 *
 * 設計書: docs/features/F17.2_village_events_activation.md §8.3 / §8.6 / §8.8
 *
 * 未参加の村人候補が「この村は自分と合いそうか」を掴むためのヒント表示。
 * `GET /villages/{id}/affinity/me` を叩き、以下を根拠つき一言で表示する:
 *   - categoryMatch（カテゴリ一致バッジ）
 *   - reasonKeys（i18n キー配列を $t で翻訳した根拠一覧）
 *   - sharedVillagerBucket（FEW=「数人」/MANY=「10人以上」。HIDDEN は非表示・正確人数は
 *     返却されないため表示もしない・差分攻撃対策 §8.4）
 *   - pioneerAppeal（小規模村の「草分けアピール」・§8.8）
 *
 * 404（UNLISTED 村・存在秘匿）/401（未ログイン）はカード自体を出さない
 * （エラー表示しない・静かに非表示・呼び出し元の設計方針どおり）。
 *
 * 呼び出し元 (pages/villages/[id]/bulletin.vue) が `!village.isMember` のときのみ
 * 描画する（参加済みの村では表示しない・AC-24c）。
 */
import type { components } from '~/types/generated'

type VillageAffinityResponse = components['schemas']['VillageAffinityResponse']

const props = defineProps<{
  villageId: string
}>()

const { t } = useI18n()
const affinityApi = useVillageAffinityApi()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
/** 404/401 などカード自体を出さないべき状態。 */
const hidden = ref(false)
const affinity = ref<VillageAffinityResponse | null>(null)

function extractStatus(err: unknown): number | null {
  if (typeof err !== 'object' || err === null) return null
  const e = err as { statusCode?: number, response?: { status?: number } }
  return e.statusCode ?? e.response?.status ?? null
}

async function load() {
  loading.value = true
  hidden.value = false
  affinity.value = null
  try {
    affinity.value = await affinityApi.getMyAffinity(props.villageId)
  }
  catch (err) {
    const status = extractStatus(err)
    // 404（UNLISTED 村・存在秘匿）/401（未ログイン）は想定内の静かな非表示。
    // それ以外（5xx・ネットワーク断等）は原因追跡のため報告だけはしておく。
    if (status !== 404 && status !== 401) {
      captureQuiet(err, { context: 'VillageAffinityCard: 相性取得失敗' })
    }
    hidden.value = true
  }
  finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.villageId, load)

/** 匿名重なりバケットの表示ラベル（HIDDEN は非表示・正確人数は返らない・§8.4）。 */
const sharedVillagerLabel = computed<string | null>(() => {
  switch (affinity.value?.sharedVillagerBucket) {
    case 'FEW':
      return t('village.affinity.sharedVillagers.few')
    case 'MANY':
      return t('village.affinity.sharedVillagers.many')
    default:
      return null
  }
})

/** reasonKeys（i18n キー配列）を翻訳した根拠一覧。 */
const reasonLines = computed<string[]>(() => {
  const keys = affinity.value?.reasonKeys ?? []
  return keys.map(key => t(key))
})

/** 全条件不成立（根拠なし・草分けアピールもなし）のときの中立表示（§8.5）。 */
const showNeutral = computed<boolean>(() => {
  if (!affinity.value) return false
  return reasonLines.value.length === 0 && !affinity.value.pioneerAppeal
})
</script>

<template>
  <div v-if="!hidden" data-testid="village-affinity-card">
    <div class="mb-3">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('village.affinity.title') }}
      </h3>
      <p class="text-xs text-surface-500">
        {{ t('village.affinity.subtitle') }}
      </p>
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="1.25rem" />
      <Skeleton height="1.25rem" />
    </div>

    <div v-else-if="affinity" class="flex flex-col gap-3">
      <div v-if="affinity.categoryMatch" class="flex items-center gap-1">
        <Tag :value="t('village.affinity.categoryMatch')" severity="info" />
      </div>

      <ul
        v-if="reasonLines.length > 0"
        class="list-disc space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300"
      >
        <li v-for="(line, idx) in reasonLines" :key="idx">
          {{ line }}
        </li>
      </ul>

      <p v-if="sharedVillagerLabel" class="text-xs text-surface-500">
        {{ sharedVillagerLabel }}
      </p>

      <!-- 草分けアピール（未参加×総現役メンバー10人以下・§8.8） -->
      <div
        v-if="affinity.pioneerAppeal"
        class="rounded-md bg-primary-50 p-3 dark:bg-primary-950"
        data-testid="village-affinity-pioneer-appeal"
      >
        <p class="text-sm font-semibold">
          {{ t('village.affinity.pioneer.title') }}
        </p>
        <p class="mt-1 text-xs text-surface-600 dark:text-surface-300">
          {{ t('village.affinity.pioneer.body') }}
        </p>
        <p class="mt-1 text-xs text-surface-500">
          {{ t('village.field.memberCount') }}: {{ affinity.memberCount }}
        </p>
      </div>

      <p v-if="showNeutral" class="text-sm text-surface-500">
        {{ t('village.affinity.hidden') }}
      </p>
    </div>
  </div>
</template>
