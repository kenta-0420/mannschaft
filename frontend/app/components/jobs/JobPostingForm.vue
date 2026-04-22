<script setup lang="ts">
import type {
  CreateJobPostingRequest,
  JobPostingResponse,
  RewardType,
  VisibilityScope,
  WorkLocationType,
} from '~/types/jobmatching'
import { MVP_VISIBILITY_SCOPES } from '~/types/jobmatching'

/**
 * 求人投稿フォーム（新規作成・編集共用）。
 *
 * <p>{@code v-model} で {@link FormState} を双方向バインドする。
 * 送信の主体は親（/teams/[id]/jobs/new.vue, /teams/[id]/jobs/[jobId]/edit.vue）が担う。
 * このコンポーネント自体は「入力値の保持 + 軽い整合性チェック + 手数料プレビュー表示」まで。</p>
 *
 * <p>Slot {@code submit} にボタンを差し込めるようにしてあり、
 * 「下書き保存」「公開」「更新」のように親画面側のボタン構成に柔軟に合わせられる。</p>
 */

export interface JobPostingFormState {
  title: string
  description: string
  category: string
  workLocationType: WorkLocationType
  workAddress: string
  workStartAt: Date | null
  workEndAt: Date | null
  rewardType: RewardType
  baseRewardJpy: number | null
  capacity: number
  applicationDeadlineAt: Date | null
  visibilityScope: VisibilityScope
}

interface Props {
  modelValue: JobPostingFormState
  /** 編集モードの場合の既存求人（編集時のみ指定）。capacity/reward 変更可否の判定に使用する想定。 */
  existing?: JobPostingResponse | null
  /** true: 編集モード、false: 新規作成モード */
  editing?: boolean
  /** 外部から「送信中」状態をフォームに伝える。 */
  submitting?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: JobPostingFormState): void
}>()

const { t } = useI18n()

// v-model の糖衣
const form = computed<JobPostingFormState>({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

// SelectButton / Select の options（i18n 反映のため computed）
const workLocationOptions = computed(() => [
  { label: t('jobmatching.workLocationType.ONSITE'), value: 'ONSITE' as WorkLocationType },
  { label: t('jobmatching.workLocationType.ONLINE'), value: 'ONLINE' as WorkLocationType },
  { label: t('jobmatching.workLocationType.HYBRID'), value: 'HYBRID' as WorkLocationType },
])

const rewardTypeOptions = computed(() => [
  { label: t('jobmatching.rewardType.LUMP_SUM'), value: 'LUMP_SUM' as RewardType },
  { label: t('jobmatching.rewardType.HOURLY'), value: 'HOURLY' as RewardType },
  { label: t('jobmatching.rewardType.DAILY'), value: 'DAILY' as RewardType },
])

const visibilityOptions = computed(() =>
  MVP_VISIBILITY_SCOPES.map(v => ({
    label: t(`jobmatching.visibilityScope.${v}`),
    value: v,
  })),
)

const showWorkAddress = computed(
  () => form.value.workLocationType === 'ONSITE' || form.value.workLocationType === 'HYBRID',
)
</script>

<template>
  <form
    class="flex flex-col gap-5"
    @submit.prevent
  >
    <!-- タイトル -->
    <div>
      <label
        class="mb-1 block text-sm font-medium"
        for="job-title"
      >
        {{ t('jobmatching.form.title') }} <span class="text-red-500">*</span>
      </label>
      <InputText
        id="job-title"
        v-model="form.title"
        :placeholder="t('jobmatching.form.titlePlaceholder')"
        maxlength="100"
        class="w-full"
      />
    </div>

    <!-- 説明 -->
    <div>
      <label
        class="mb-1 block text-sm font-medium"
        for="job-description"
      >
        {{ t('jobmatching.form.description') }} <span class="text-red-500">*</span>
      </label>
      <Textarea
        id="job-description"
        v-model="form.description"
        :placeholder="t('jobmatching.form.descriptionPlaceholder')"
        maxlength="2000"
        rows="6"
        class="w-full"
        auto-resize
      />
    </div>

    <!-- カテゴリ -->
    <div>
      <label
        class="mb-1 block text-sm font-medium"
        for="job-category"
      >
        {{ t('jobmatching.form.category') }}
      </label>
      <InputText
        id="job-category"
        v-model="form.category"
        :placeholder="t('jobmatching.form.categoryPlaceholder')"
        maxlength="50"
        class="w-full"
      />
    </div>

    <!-- 業務場所種別 -->
    <div>
      <label class="mb-1 block text-sm font-medium">
        {{ t('jobmatching.form.workLocationType') }} <span class="text-red-500">*</span>
      </label>
      <SelectButton
        v-model="form.workLocationType"
        :options="workLocationOptions"
        option-label="label"
        option-value="value"
      />
    </div>

    <!-- 業務住所（ONSITE / HYBRID のみ） -->
    <div v-if="showWorkAddress">
      <label
        class="mb-1 block text-sm font-medium"
        for="job-address"
      >
        {{ t('jobmatching.form.workAddress') }}
      </label>
      <InputText
        id="job-address"
        v-model="form.workAddress"
        :placeholder="t('jobmatching.form.workAddressPlaceholder')"
        maxlength="255"
        class="w-full"
      />
    </div>

    <!-- 業務期間 -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('jobmatching.form.workStartAt') }} <span class="text-red-500">*</span>
        </label>
        <DatePicker
          v-model="form.workStartAt"
          show-time
          show-icon
          hour-format="24"
          date-format="yy-mm-dd"
          class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('jobmatching.form.workEndAt') }} <span class="text-red-500">*</span>
        </label>
        <DatePicker
          v-model="form.workEndAt"
          show-time
          show-icon
          hour-format="24"
          date-format="yy-mm-dd"
          class="w-full"
        />
      </div>
    </div>

    <!-- 応募締切 -->
    <div>
      <label class="mb-1 block text-sm font-medium">
        {{ t('jobmatching.form.applicationDeadlineAt') }} <span class="text-red-500">*</span>
      </label>
      <DatePicker
        v-model="form.applicationDeadlineAt"
        show-time
        show-icon
        hour-format="24"
        date-format="yy-mm-dd"
        class="w-full"
      />
    </div>

    <!-- 報酬タイプ -->
    <div>
      <label class="mb-1 block text-sm font-medium">
        {{ t('jobmatching.form.rewardType') }} <span class="text-red-500">*</span>
      </label>
      <SelectButton
        v-model="form.rewardType"
        :options="rewardTypeOptions"
        option-label="label"
        option-value="value"
      />
    </div>

    <!-- 業務報酬 + 募集定員 -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div>
        <label
          class="mb-1 block text-sm font-medium"
          for="job-base-reward"
        >
          {{ t('jobmatching.form.baseRewardJpy') }} <span class="text-red-500">*</span>
        </label>
        <InputNumber
          v-model="form.baseRewardJpy"
          input-id="job-base-reward"
          :min="500"
          :max="1000000"
          mode="currency"
          currency="JPY"
          locale="ja-JP"
          class="w-full"
          fluid
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ t('jobmatching.form.baseRewardJpyHint') }}
        </p>
      </div>
      <div>
        <label
          class="mb-1 block text-sm font-medium"
          for="job-capacity"
        >
          {{ t('jobmatching.form.capacity') }} <span class="text-red-500">*</span>
        </label>
        <InputNumber
          v-model="form.capacity"
          input-id="job-capacity"
          :min="1"
          :max="999"
          show-buttons
          class="w-full"
          fluid
        />
      </div>
    </div>

    <!-- 手数料プレビュー -->
    <FeePreviewPanel :base-reward-jpy="form.baseRewardJpy" />

    <!-- 公開範囲 -->
    <div>
      <label class="mb-1 block text-sm font-medium">
        {{ t('jobmatching.form.visibilityScope') }} <span class="text-red-500">*</span>
      </label>
      <Select
        v-model="form.visibilityScope"
        :options="visibilityOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
      <p class="mt-1 text-xs text-surface-500">
        {{ t('jobmatching.form.visibilityScopeHint') }}
      </p>
    </div>

    <!-- 送信ボタン（親から差し込む） -->
    <div class="flex flex-wrap justify-end gap-2 border-t border-surface-200 pt-4 dark:border-surface-700">
      <slot
        name="submit"
        :submitting="submitting ?? false"
      />
    </div>
  </form>
</template>
