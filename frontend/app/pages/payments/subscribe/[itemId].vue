<script setup lang="ts">
import type { SwitchableChild } from '~/types/guardianship'
import type { SubscribeRequest } from '~/types/membershipSubscription'
import type { PaymentItemResponse } from '~/types/payment'

/**
 * F08.9 P5/P6 継続課金・期別課金 加入ページ（専用ページ方式・殿裁定 / 設計書 04 §2）。
 *
 * フロー（多段）:
 *   ①受益者選択（既定=自分／後見下の子は switchable-children から）
 *     ↳ TERM 型の場合は有効期間を表示する（P6 対応）
 *   ②カード入力（StripePaymentForm: SetupIntent を confirmSetup）
 *   ③確認・実行（confirmPaymentMethod で attach → subscribe）
 *   ④完了（subscriptions 一覧への導線）
 *
 * TERM 型（P6）:
 *   type=TERM の項目は期別課金（有効期限つき単発）。有効期間（termStartsOn〜termEndsOn）を
 *   受益者選択ステップに表示する。決済フローはカード/継続と同様（checkout エンドポイントを使用）。
 *
 * 3DS 復帰（同一ページで完結）:
 *   3DS が必要なカードは confirmSetup がブラウザを returnUrl へ遷移させる。
 *   returnUrl には beneficiaryUserId のみを載せ、clientSecret は URL/storage に置かない。
 *   Stripe が付与する setup_intent_client_secret を onMounted で検出して続行する。
 *
 * セキュリティ:
 *   - clientSecret は ref 上のみ保持し、URL/localStorage/sessionStorage に保存しない。
 *   - 復帰後はクエリを router.replace で即座に除去する。
 */
definePageMeta({ middleware: 'auth' })

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const subscriptionApi = useMembershipSubscriptionApi()
const guardianshipApi = useGuardianshipApi()
const paymentApi = usePaymentApi()
const { retrieveSetupIntent } = useStripeSetup()

// ── 支払い項目情報（TERM 型の有効期間表示に使用） ────────────────
const paymentItem = ref<PaymentItemResponse | null>(null)

/** 項目が TERM 型か。null（未取得）の場合は false を返す。 */
const isTermType = computed(() => paymentItem.value?.meta.type === 'TERM')

/** TERM 有効期間開始日（ロケールに合わせてフォーマット）。 */
const termStartsOnFormatted = computed<string | null>(() => {
  const d = paymentItem.value?.term?.termStartsOn
  if (!d) return null
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(new Date(d))
})

/** TERM 有効期間終了日（ロケールに合わせてフォーマット）。 */
const termEndsOnFormatted = computed<string | null>(() => {
  const d = paymentItem.value?.term?.termEndsOn
  if (!d) return null
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(new Date(d))
})

/**
 * 支払い項目を取得する（TERM 型の有効期間表示に使用）。
 * BE エンドポイント未実装の間は取得失敗を許容し、ページ機能は継続する（UI 上は期間非表示）。
 * 症状を隠さない原則：エラー時は WARN でログに出す（ユーザー向けエラー表示はしない）。
 */
async function loadPaymentItem() {
  if (itemId.value === null) return
  try {
    const res = await paymentApi.getPaymentItemById(itemId.value)
    paymentItem.value = res.data
  } catch (err) {
    // P6 BE 未実装の間は 404/500 が返るため WARN のみ（加入フロー自体は継続可能）。
    console.warn('[subscribe] 支払い項目の取得に失敗（TERM 型表示は省略）', { itemId: itemId.value, err })
  }
}

/** 会費項目 ID（数値）。不正な場合は後続フローを止めてエラー表示する。 */
const itemId = computed(() => {
  const raw = route.params.itemId
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(id) && id > 0 ? id : null
})

/** ステップ識別子。 */
type Step = 'beneficiary' | 'card' | 'confirm' | 'done'
const step = ref<Step>('beneficiary')

// ── 受益者選択 ─────────────────────────────────────────────
/** 自分自身を表す受益者選択肢の番兵値。 */
const SELF_VALUE = 'self'
const beneficiaryChoice = ref<string>(SELF_VALUE)
const switchableChildren = ref<SwitchableChild[]>([])
const childrenLoading = ref(false)

/** 選択された受益者ユーザー ID（自分 or 後見下の子）。 */
const beneficiaryUserId = computed<number | null>(() => {
  if (beneficiaryChoice.value === SELF_VALUE) {
    return authStore.user?.id ?? null
  }
  const id = Number(beneficiaryChoice.value)
  return Number.isFinite(id) ? id : null
})

// ── Stripe フロー状態 ──────────────────────────────────────
const clientSecret = ref<string | null>(null)
/** confirm→subscribe の二重送信防止フラグ（finalizeSubscription が管理）。 */
const processing = ref(false)
/** 3DS 復帰時の SetupIntent 取得中フラグ（processing と分離し二重送信ガードと衝突させない）。 */
const retrieving = ref(false)
const pageError = ref<string | null>(null)

/** StripePaymentForm の 3DS リダイレクト復帰先 URL（secret は載せない・ID のみ）。 */
const returnUrl = computed(() => {
  if (typeof window === 'undefined' || itemId.value === null) return ''
  const url = new URL(`/payments/subscribe/${itemId.value}`, window.location.origin)
  if (beneficiaryUserId.value !== null) {
    url.searchParams.set('beneficiaryUserId', String(beneficiaryUserId.value))
  }
  return url.toString()
})

/**
 * BE の継続課金 409 エラーコードをユーザー向け文言にマップする。
 * 未知コードは genericError にフォールバックする（症状を隠さず一般文言で表示）。
 */
function mapSubscribeError(err: unknown): string {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  switch (code) {
    case 'MEMBERSHIP_BILLING_019':
      return t('payment.membership.subscribe.errorNotRecurring')
    case 'MEMBERSHIP_BILLING_020':
      return t('payment.membership.subscribe.errorPmNotSaved')
    case 'MEMBERSHIP_BILLING_021':
      return t('payment.membership.subscribe.errorAlreadyExists')
    default:
      return t('payment.membership.subscribe.genericError')
  }
}

/** crypto.randomUUID があれば使い、無い環境はフォールバックで冪等キーを生成する。 */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const rnd = () => Math.floor(Math.random() * 0xffff).toString(16).padStart(4, '0')
  return `${rnd()}${rnd()}-${rnd()}-4${rnd().slice(1)}-${((Math.random() * 0x4) | 0x8).toString(16)}${rnd().slice(1)}-${rnd()}${rnd()}${rnd()}`
}

/** 後見下の切替可能な子を取得し、受益者の選択肢に加える。 */
async function loadSwitchableChildren() {
  childrenLoading.value = true
  try {
    const res = await guardianshipApi.listSwitchableChildren()
    switchableChildren.value = res.data.children.filter((c) => c.switchAllowed)
  } catch {
    // 子一覧の取得失敗は致命的ではない（自分への加入は継続可能）。選択肢を空にするのみ。
    switchableChildren.value = []
  } finally {
    childrenLoading.value = false
  }
}

/**
 * SetupIntent を作成し clientSecret を取得してカード入力ステップへ進む。
 * 受益者未確定（自分の ID も取れない）の場合は明示エラーにする（症状を隠さない）。
 */
async function startCardStep() {
  if (processing.value) return
  if (itemId.value === null) {
    pageError.value = t('payment.membership.subscribe.invalidItem')
    return
  }
  if (beneficiaryUserId.value === null) {
    pageError.value = t('payment.membership.subscribe.beneficiaryRequired')
    return
  }
  processing.value = true
  pageError.value = null
  try {
    const res = await subscriptionApi.createSetupIntent()
    const secret = res.data.clientSecret
    if (!secret) {
      pageError.value = t('payment.membership.subscribe.genericError')
      return
    }
    clientSecret.value = secret
    step.value = 'card'
  } catch {
    pageError.value = t('payment.membership.subscribe.genericError')
  } finally {
    processing.value = false
  }
}

/**
 * PaymentMethod 確定後の共通処理: confirm（attach）→ subscribe。
 * 成功で完了ステップへ。失敗は 409 コードをマップして表示し、再試行できるよう card ステップに戻す。
 */
async function finalizeSubscription(paymentMethodId: string) {
  if (processing.value) return
  if (itemId.value === null || beneficiaryUserId.value === null) {
    pageError.value = t('payment.membership.subscribe.genericError')
    return
  }
  processing.value = true
  pageError.value = null
  step.value = 'confirm'
  try {
    await subscriptionApi.confirmPaymentMethod({ paymentMethodId })
    const body: SubscribeRequest = {
      beneficiaryUserId: beneficiaryUserId.value,
      idempotencyKey: newIdempotencyKey(),
    }
    await subscriptionApi.subscribe(itemId.value, body)
    step.value = 'done'
  } catch (err) {
    pageError.value = mapSubscribeError(err)
    // カード入力からやり直せるよう card ステップへ戻す（SetupIntent は再作成）。
    clientSecret.value = null
    step.value = 'card'
    await restartCardStep()
  } finally {
    processing.value = false
  }
}

/** SetupIntent を作り直してカード入力をやり直す（失敗・キャンセル後の再試行）。 */
async function restartCardStep() {
  if (itemId.value === null) return
  try {
    const res = await subscriptionApi.createSetupIntent()
    clientSecret.value = res.data.clientSecret
  } catch {
    // 再作成も失敗した場合は受益者選択へ戻す。
    step.value = 'beneficiary'
  }
}

// ── StripePaymentForm のイベント ──────────────────────────
/** カード確定成功（非リダイレクト）→ confirm → subscribe。 */
function onCardSuccess(paymentMethodId: string) {
  void finalizeSubscription(paymentMethodId)
}

/** カードフォームのエラー（読み込み失敗・決済拒否）はそのまま表示する。 */
function onCardError(message: string) {
  pageError.value = message
}

// ── 3DS 復帰処理（同一ページで完結） ─────────────────────
/**
 * Stripe が returnUrl に付与する setup_intent_client_secret を検出し、
 * SetupIntent の状態に応じて続行・エラー表示する。クエリは即座に除去する。
 */
async function handleRedirectReturn() {
  const secretParam = route.query.setup_intent_client_secret
  const secret = Array.isArray(secretParam) ? secretParam[0] : secretParam
  if (!secret) return

  // 受益者 ID は returnUrl に載せた beneficiaryUserId クエリから復元する。
  // セキュリティ二重防御（BE 権原検証が最終防衛）: FE でもホワイトリスト照合し、
  // 「自分の userId」または「switchableChildren の childUserId」以外は SELF にフォールバックする。
  // ※ loadSwitchableChildren はまだ未実行（3DS 復帰時は beneficiary ステップを経由しない）ため、
  //   ここで await してから判定する。
  await loadSwitchableChildren()
  const benParam = route.query.beneficiaryUserId
  const ben = Array.isArray(benParam) ? benParam[0] : benParam
  if (ben) {
    const benNum = Number(ben)
    const isSelf = benNum === authStore.user?.id
    const isKnownChild = switchableChildren.value.some((c) => c.childUserId === benNum)
    if (isSelf) {
      beneficiaryChoice.value = SELF_VALUE
    } else if (isKnownChild) {
      // 後見下の子として選択を復元する。
      beneficiaryChoice.value = String(benNum)
    } else {
      // ホワイトリスト不一致（攻撃改竄 or loadSwitchableChildren API 失敗で空配列）:
      // SELF にフォールバックして finalizeSubscription を自動実行すると、
      // 後見人が子のために加入したつもりが自分への課金に化ける（SELF化け）。
      // 安全側に倒し、自動課金を中断して受益者選択ステップへ戻す。
      // client_secret 値はログに含めない。
      console.warn('[subscribe] 3DS 復帰: beneficiaryUserId がホワイトリスト外のため自動課金を中断', {
        path: route.path,
        receivedBeneficiaryUserId: benNum,
        childrenLoaded: switchableChildren.value.length,
      })
      pageError.value = t('payment.membership.subscribe.beneficiaryUnverified')
      step.value = 'beneficiary'
      return
    }
  }

  // クエリ（secret 含む）を URL から即座に除去する（履歴・共有リンクに secret を残さない）。
  // 症状を隠さない（CLAUDE.md 障害対応の原則）: replace 失敗は WARN で可視化する。
  // secret は URL に残留し得るため WARN で開発者に通知する（加入フロー自体は継続する）。
  // client_secret 値そのものはログに絶対に含めない。
  try {
    await router.replace({ path: route.path })
  } catch (replaceErr) {
    console.warn('[subscribe] secret クエリの URL 除去に失敗した（加入フローは継続）', {
      path: route.path,
      reason: replaceErr instanceof Error ? replaceErr.message : String(replaceErr),
    })
  }

  // retrieve フェーズは専用フラグ retrieving で管理する。
  // 共有 processing は finalizeSubscription が自前で立てる（二重送信ガードとの衝突回避）。
  retrieving.value = true
  pageError.value = null
  step.value = 'confirm'
  try {
    const result = await retrieveSetupIntent(secret)
    if (result.status === 'error') {
      pageError.value = result.message
      step.value = 'card'
      await restartCardStep()
      return
    }
    const si = result.setupIntent
    if (si.status === 'succeeded') {
      const pm = si.payment_method
      const paymentMethodId = typeof pm === 'string' ? pm : (pm?.id ?? null)
      if (!paymentMethodId) {
        pageError.value = t('payment.membership.subscribe.noPaymentMethod')
        step.value = 'card'
        await restartCardStep()
        return
      }
      // retrieving を解除してから confirm→subscribe へ（finalizeSubscription の processing ガードと衝突させない）。
      retrieving.value = false
      await finalizeSubscription(paymentMethodId)
    } else {
      // canceled / requires_payment_method 等は再試行できるようカード入力へ戻す。
      pageError.value = t('payment.membership.subscribe.authFailed')
      step.value = 'card'
      await restartCardStep()
    }
  } catch {
    pageError.value = t('payment.membership.subscribe.genericError')
    step.value = 'card'
    await restartCardStep()
  } finally {
    retrieving.value = false
  }
}

onMounted(async () => {
  if (itemId.value === null) {
    pageError.value = t('payment.membership.subscribe.invalidItem')
    return
  }
  // 支払い項目情報（TERM 型の有効期間表示用）を並行で取得する。失敗しても加入フローは続行可能。
  void loadPaymentItem()
  // 3DS 復帰クエリがある場合は復帰処理を優先する。
  if (route.query.setup_intent_client_secret) {
    await handleRedirectReturn()
    return
  }
  await loadSwitchableChildren()
})
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <PageHeader :title="$t('payment.membership.subscribe.pageTitle')" class="mb-4" />

    <!-- ステップ表示（記号併用でアクセシビリティ配慮） -->
    <ol class="mb-6 flex flex-wrap gap-2 text-sm text-surface-500" aria-label="steps">
      <li :class="{ 'font-semibold text-primary': step === 'beneficiary' }">
        1. {{ $t('payment.membership.subscribe.stepBeneficiary') }}
      </li>
      <li aria-hidden="true">›</li>
      <li :class="{ 'font-semibold text-primary': step === 'card' }">
        2. {{ $t('payment.membership.subscribe.stepCard') }}
      </li>
      <li aria-hidden="true">›</li>
      <li :class="{ 'font-semibold text-primary': step === 'confirm' }">
        3. {{ $t('payment.membership.subscribe.stepConfirm') }}
      </li>
      <li aria-hidden="true">›</li>
      <li :class="{ 'font-semibold text-primary': step === 'done' }">
        4. {{ $t('payment.membership.subscribe.stepDone') }}
      </li>
    </ol>

    <!-- ページ全体のエラー表示（握り潰さず表示） -->
    <Message
      v-if="pageError"
      severity="error"
      :closable="false"
      class="mb-4"
      role="alert"
      data-testid="subscribe-error"
    >
      {{ pageError }}
    </Message>

    <!-- 不正な itemId のときは以降のフォームを描画しない -->
    <template v-if="itemId !== null">
      <!-- ① 受益者選択 -->
      <section v-if="step === 'beneficiary'" class="flex flex-col gap-4">
        <h2 class="text-lg font-semibold">
          {{ $t('payment.membership.subscribe.stepBeneficiary') }}
        </h2>

        <!-- F08.9 P6: TERM 型の有効期間表示 -->
        <div
          v-if="isTermType && (termStartsOnFormatted || termEndsOnFormatted)"
          class="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm"
          role="note"
          :aria-label="$t('payment.term.periodLabel')"
        >
          <i class="pi pi-calendar text-blue-500" aria-hidden="true" />
          <span class="font-medium text-blue-700">{{ $t('payment.term.periodLabel') }}:</span>
          <span class="text-blue-900">
            <template v-if="termStartsOnFormatted && termEndsOnFormatted">
              {{ termStartsOnFormatted }} 〜 {{ termEndsOnFormatted }}
            </template>
            <template v-else-if="termStartsOnFormatted">
              {{ $t('payment.term.from') }} {{ termStartsOnFormatted }}
            </template>
            <template v-else-if="termEndsOnFormatted">
              {{ $t('payment.term.until') }} {{ termEndsOnFormatted }}
            </template>
          </span>
        </div>

        <p class="text-sm text-surface-500">
          {{ $t('payment.membership.subscribe.beneficiaryHelp') }}
        </p>

        <div v-if="childrenLoading" class="flex justify-center p-4">
          <LoadingBounce />
        </div>

        <div v-else class="flex flex-col gap-2">
          <!-- 自分（既定） -->
          <label class="flex items-center gap-2">
            <RadioButton v-model="beneficiaryChoice" :value="SELF_VALUE" />
            <span>
              {{ $t('payment.membership.subscribe.beneficiarySelf') }}
              <span v-if="authStore.user?.fullName" class="text-surface-500">
                （{{ authStore.user.fullName }}）
              </span>
            </span>
          </label>

          <!-- 後見下の子（switchAllowed=true のみ） -->
          <label
            v-for="child in switchableChildren"
            :key="child.childUserId"
            class="flex items-center gap-2"
          >
            <RadioButton
              v-model="beneficiaryChoice"
              :value="String(child.childUserId)"
            />
            <span>
              {{ child.displayName || $t('payment.membership.subscribe.unnamedChild') }}
            </span>
          </label>
        </div>

        <div class="mt-2 flex justify-end">
          <Button
            :label="$t('payment.membership.subscribe.next')"
            :loading="processing"
            :disabled="processing || beneficiaryUserId === null"
            data-testid="subscribe-next"
            @click="startCardStep"
          />
        </div>
      </section>

      <!-- ② カード入力 -->
      <section v-else-if="step === 'card'" class="flex flex-col gap-4">
        <h2 class="text-lg font-semibold">
          {{ $t('payment.membership.subscribe.stepCard') }}
        </h2>
        <NuxtLink
          to="/commerce-disclosure"
          target="_blank"
          class="text-xs text-surface-400 hover:text-primary hover:underline"
        >
          {{ $t('landing.layout.footer_commerce') }}
        </NuxtLink>
        <StripePaymentForm
          v-if="clientSecret"
          :key="clientSecret"
          :client-secret="clientSecret"
          :return-url="returnUrl"
          @success="onCardSuccess"
          @error="onCardError"
        />
        <div v-else class="flex justify-center p-4">
          <LoadingBounce />
        </div>
      </section>

      <!-- ③ 確認・実行（confirm → subscribe 処理中） -->
      <section v-else-if="step === 'confirm'" class="flex flex-col items-center gap-4 py-8">
        <LoadingBounce />
        <p class="text-surface-500">
          {{ $t('payment.membership.subscribe.finalizing') }}
        </p>
      </section>

      <!-- ④ 完了 -->
      <section v-else-if="step === 'done'" data-testid="subscribe-done" class="flex flex-col items-center gap-4 py-8 text-center">
        <i class="pi pi-check-circle text-5xl text-green-500" aria-hidden="true" />
        <h2 class="text-lg font-semibold">
          {{ $t('payment.membership.subscribe.doneTitle') }}
        </h2>
        <p class="text-surface-500">
          {{ $t('payment.membership.subscribe.doneMessage') }}
        </p>
        <NuxtLink to="/me/payments/subscriptions">
          <Button
            :label="$t('payment.membership.subscribe.toSubscriptions')"
            icon="pi pi-list"
          />
        </NuxtLink>
      </section>
    </template>
  </div>
</template>
