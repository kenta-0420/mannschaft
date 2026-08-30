<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import type {
  CreateRecruitmentListingRequest,
  RecruitmentCategoryResponse,
  RecruitmentParticipationType,
  RecruitmentPayeeKind,
  RecruitmentScopeType,
  RecruitmentVisibility,
} from '~/types/recruitment'
import type { MemberResponse } from '~/types/member'

interface Props {
  initial?: Partial<CreateRecruitmentListingRequest>
  categories: RecruitmentCategoryResponse[]
  submitLabel?: string
  loading?: boolean
  /**
   * true のとき visibility セレクタを非表示にする。
   * F22.1 市の札立てページで MarketListingFormExtension の visibility と二重化しないために使用。
   * デフォルト false（従来挙動を維持）。
   */
  hideVisibility?: boolean
  /**
   * true のとき決済入力を非表示にし、送信値を paymentEnabled=false に固定する。
   * PERSONAL 札は主体別管理市 Phase 5 まで決済禁止のため使用する。
   */
  hidePayment?: boolean
  /**
   * F22.1 市 謝礼決済: スコープ種別（TEAM / ORGANIZATION）。
   * payeeKind=USER 選択時に所属メンバー一覧を取得するために使用。
   * 未指定時はメンバー一覧取得を省略する。
   */
  scopeType?: RecruitmentScopeType
  /**
   * F22.1 市 謝礼決済: スコープID。
   * payeeKind=USER 選択時に所属メンバー一覧を取得するために使用。
   */
  scopeId?: string
}
const props = withDefaults(defineProps<Props>(), {
  initial: () => ({}),
  submitLabel: undefined,
  loading: false,
  hideVisibility: false,
  hidePayment: false,
  scopeType: undefined,
  scopeId: undefined,
})

const emit = defineEmits<{
  submit: [value: CreateRecruitmentListingRequest]
}>()

const { t } = useI18n()

const categoryId = ref<number | null>(props.initial.categoryId ?? null)
const title = ref(props.initial.title ?? '')
const description = ref(props.initial.description ?? '')
const participationType = ref<RecruitmentParticipationType>(
  props.initial.participationType ?? 'INDIVIDUAL',
)
const startAt = ref(props.initial.startAt ?? '')
const endAt = ref(props.initial.endAt ?? '')
const applicationDeadline = ref(props.initial.applicationDeadline ?? '')
const autoCancelAt = ref(props.initial.autoCancelAt ?? '')
const capacity = ref<number | null>(props.initial.capacity ?? null)
const minCapacity = ref<number | null>(props.initial.minCapacity ?? null)
const paymentEnabled = ref<boolean>(props.initial.paymentEnabled ?? false)
const price = ref<number | null>(props.initial.price ?? null)
const visibility = ref<RecruitmentVisibility>(props.initial.visibility ?? 'SCOPE_ONLY')
const location = ref(props.initial.location ?? '')

// F22.1 市 謝礼決済: 受領主体
const payeeKind = ref<RecruitmentPayeeKind | null>(
  (props.initial.payeeKind as RecruitmentPayeeKind | null | undefined) ?? null,
)
const payeeUserId = ref<number | null>(props.initial.payeeUserId ?? null)

// payeeKind=USER のとき表示するメンバー一覧
const scopeMembers = ref<MemberResponse[]>([])
const membersLoading = ref(false)

// クライアントサイドバリデーションエラー
const payeeKindError = ref<string | null>(null)
const payeeUserError = ref<string | null>(null)

const submitButtonLabel = computed(() => props.submitLabel ?? t('recruitment.action.create'))

/**
 * scopeType によって選択肢を絞る。
 *   TEAM スコープ        → USER, TEAM（ORG は送信すると BE PAYMENT_C013 になるため除外）
 *   ORGANIZATION スコープ → USER, ORG（TEAM は送信すると BE PAYMENT_C013 になるため除外）
 *   未指定              → 全 3 択（後方互換）
 */
const payeeKindOptions = computed(() => {
  const all: { value: RecruitmentPayeeKind; label: string }[] = [
    { value: 'USER', label: t('recruitment.field.payeeKindUser') },
    { value: 'TEAM', label: t('recruitment.field.payeeKindTeam') },
    { value: 'ORG', label: t('recruitment.field.payeeKindOrg') },
  ]
  if (props.scopeType === 'TEAM') {
    return all.filter((o) => o.value === 'USER' || o.value === 'TEAM')
  }
  if (props.scopeType === 'ORGANIZATION') {
    return all.filter((o) => o.value === 'USER' || o.value === 'ORG')
  }
  if (props.scopeType === 'PERSONAL') {
    return []
  }
  return all
})

// scopeType 変更または payeeKindOptions 絞り込みで現在選択中の値が消えた場合にリセット
watch(payeeKindOptions, (opts) => {
  if (payeeKind.value !== null && !opts.some((o) => o.value === payeeKind.value)) {
    payeeKind.value = null
    payeeUserId.value = null
    payeeKindError.value = null
    payeeUserError.value = null
  }
})

async function loadMembers() {
  if (!props.scopeType || !props.scopeId) return
  membersLoading.value = true
  try {
    if (props.scopeType === 'TEAM') {
      const { useTeamMembers } = await import('~/composables/team/useTeamMembers')
      const teamMembers = useTeamMembers()
      // size=100 で十分な件数を取得（受領者は 1 名選択のため）
      const result = await teamMembers.getMembers(props.scopeId, { size: 100 })
      scopeMembers.value = result.data
    } else if (props.scopeType === 'ORGANIZATION') {
      const orgApi = useOrganizationApi()
      const result = await orgApi.getMembers(props.scopeId, { size: 100 })
      scopeMembers.value = result.data
    }
  } catch {
    // メンバー一覧取得失敗は警告のみ（手動入力にフォールバック不要・選択UIは空表示）
  } finally {
    membersLoading.value = false
  }
}

// payeeKind が USER に切り替わったタイミングでメンバー一覧を取得
watch(payeeKind, (newKind) => {
  if (newKind === 'USER') {
    loadMembers()
  } else {
    // USER 以外に切り替えたら受領者選択をリセット
    payeeUserId.value = null
    payeeUserError.value = null
  }
  payeeKindError.value = null
})

// paymentEnabled が false になったら payeeKind/payeeUserId をリセット
watch(paymentEnabled, (enabled) => {
  if (!enabled) {
    payeeKind.value = null
    payeeUserId.value = null
    payeeKindError.value = null
    payeeUserError.value = null
  }
})

onMounted(() => {
  // 初期値で payeeKind=USER の場合はメンバーを事前ロード
  if (payeeKind.value === 'USER') {
    loadMembers()
  }
})

function validatePayee(): boolean {
  payeeKindError.value = null
  payeeUserError.value = null

  if (!paymentEnabled.value) return true

  if (!payeeKind.value) {
    payeeKindError.value = t('recruitment.payee.required')
    return false
  }

  if (payeeKind.value === 'USER' && !payeeUserId.value) {
    payeeUserError.value = t('recruitment.payee.userRequired')
    return false
  }

  return true
}

function onSubmit() {
  if (
    !categoryId.value ||
    !title.value ||
    !startAt.value ||
    !endAt.value ||
    !applicationDeadline.value ||
    !autoCancelAt.value ||
    capacity.value == null ||
    minCapacity.value == null
  ) {
    return
  }

  if (!props.hidePayment && !validatePayee()) return

  const submittedPaymentEnabled = props.hidePayment ? false : paymentEnabled.value

  emit('submit', {
    categoryId: categoryId.value,
    title: title.value,
    description: description.value || null,
    participationType: participationType.value,
    startAt: startAt.value,
    endAt: endAt.value,
    applicationDeadline: applicationDeadline.value,
    autoCancelAt: autoCancelAt.value,
    capacity: capacity.value,
    minCapacity: minCapacity.value,
    paymentEnabled: submittedPaymentEnabled,
    price: submittedPaymentEnabled ? price.value : null,
    visibility: visibility.value,
    location: location.value || null,
    // F22.1 市 謝礼決済
    payeeKind: submittedPaymentEnabled ? payeeKind.value : null,
    payeeUserId: submittedPaymentEnabled && payeeKind.value === 'USER' ? payeeUserId.value : null,
  })
}
</script>

<template>
  <form class="flex flex-col gap-4" @submit.prevent="onSubmit">
    <div class="flex flex-col gap-2">
      <label for="title">{{ t('recruitment.field.title') }}</label>
      <InputText id="title" v-model="title" required />
    </div>

    <div class="flex flex-col gap-2">
      <label for="category">{{ t('recruitment.field.category') }}</label>
      <Select
        id="category"
        v-model="categoryId"
        :options="categories"
        option-label="nameI18nKey"
        option-value="id"
        required
      >
        <template #option="{ option }">
          <span>{{ t(option.nameI18nKey) }}</span>
        </template>
        <template #value="{ value }">
          <span v-if="value">{{
            t(categories.find((c) => c.id === value)?.nameI18nKey ?? '')
          }}</span>
        </template>
      </Select>
    </div>

    <div class="flex flex-col gap-2">
      <label>{{ t('recruitment.field.participationType') }}</label>
      <SelectButton
        v-model="participationType"
        :options="[
          { value: 'INDIVIDUAL', label: t('recruitment.participationType.individual') },
          { value: 'TEAM', label: t('recruitment.participationType.team') },
        ]"
        option-label="label"
        option-value="value"
      />
    </div>

    <div class="flex flex-col gap-2">
      <label for="description">{{ t('recruitment.field.description') }}</label>
      <Textarea id="description" v-model="description" rows="3" />
    </div>

    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
      <div class="flex flex-col gap-2">
        <label for="startAt">{{ t('recruitment.field.startAt') }}</label>
        <InputText id="startAt" v-model="startAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="endAt">{{ t('recruitment.field.endAt') }}</label>
        <InputText id="endAt" v-model="endAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="applicationDeadline">{{ t('recruitment.field.applicationDeadline') }}</label>
        <InputText
          id="applicationDeadline"
          v-model="applicationDeadline"
          type="datetime-local"
          required
        />
      </div>
      <div class="flex flex-col gap-2">
        <label for="autoCancelAt">{{ t('recruitment.field.autoCancelAt') }}</label>
        <InputText id="autoCancelAt" v-model="autoCancelAt" type="datetime-local" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="capacity">{{ t('recruitment.field.capacity') }}</label>
        <InputNumber id="capacity" v-model="capacity" :min="1" required />
      </div>
      <div class="flex flex-col gap-2">
        <label for="minCapacity">{{ t('recruitment.field.minCapacity') }}</label>
        <InputNumber id="minCapacity" v-model="minCapacity" :min="1" required />
      </div>
    </div>

    <div class="flex flex-col gap-2">
      <label for="location">{{ t('recruitment.field.location') }}</label>
      <InputText id="location" v-model="location" />
    </div>

    <div v-if="!hidePayment" class="flex items-center gap-2">
      <Checkbox v-model="paymentEnabled" input-id="paymentEnabled" :binary="true" />
      <label for="paymentEnabled">{{ t('recruitment.field.paymentEnabled') }}</label>
    </div>

    <template v-if="!hidePayment && paymentEnabled">
      <div class="flex flex-col gap-2">
        <label for="price">{{ t('recruitment.field.price') }}</label>
        <InputNumber id="price" v-model="price" :min="0" required />
      </div>

      <!-- F22.1 市 謝礼決済: 受領者種別 -->
      <div class="flex flex-col gap-2">
        <label for="payeeKind">
          {{ t('recruitment.field.payeeKind') }}
          <span class="ml-1 text-red-500">*</span>
        </label>
        <Select
          id="payeeKind"
          v-model="payeeKind"
          :options="payeeKindOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('recruitment.field.payeeKind')"
          :invalid="!!payeeKindError"
        />
        <small v-if="payeeKindError" class="text-red-500">{{ payeeKindError }}</small>
      </div>

      <!-- F22.1 市 謝礼決済: 受領者ユーザー（payeeKind=USER のときのみ表示） -->
      <div v-if="payeeKind === 'USER'" class="flex flex-col gap-2">
        <label for="payeeUserId">
          {{ t('recruitment.field.payeeUser') }}
          <span class="ml-1 text-red-500">*</span>
        </label>
        <Select
          id="payeeUserId"
          v-model="payeeUserId"
          :options="scopeMembers"
          option-label="displayName"
          option-value="userId"
          :placeholder="t('recruitment.field.payeeUserPlaceholder')"
          :loading="membersLoading"
          :invalid="!!payeeUserError"
        />
        <small v-if="payeeUserError" class="text-red-500">{{ payeeUserError }}</small>
      </div>
    </template>

    <div v-if="!hideVisibility" class="flex flex-col gap-2">
      <label for="visibility">{{ t('recruitment.field.visibility') }}</label>
      <Select
        id="visibility"
        v-model="visibility"
        :options="[
          { value: 'SCOPE_ONLY', label: t('recruitment.visibility.scopeOnly') },
          { value: 'PUBLIC', label: t('recruitment.visibility.public') },
          { value: 'SUPPORTERS_ONLY', label: t('recruitment.visibility.supportersOnly') },
        ]"
        option-label="label"
        option-value="value"
      />
    </div>

    <Button type="submit" :label="submitButtonLabel" :loading="loading" icon="pi pi-check" />
  </form>
</template>
