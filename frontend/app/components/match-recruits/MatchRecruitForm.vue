<script setup lang="ts">
/**
 * F17.1 村機能 — 練習試合・募集タブ 募集作成 Dialog
 *
 * Dialog の開閉状態は v-model:visible で親と連携し、
 * 確定時は submit イベントで作成リクエストボディを親へ通知する。
 * API 呼び出しは親 (villages/[id]/match-recruits.vue) が担当する。
 */
import type {
  VillageMatchRecruitCategory,
  VillageMatchRecruitCreateRequest,
} from '~/types/village'
import type { PostingIdentitySelection } from '~/components/VillagePostingIdentitySelector.vue'

interface CategoryOption {
  value: VillageMatchRecruitCategory
  label: string
}

const props = defineProps<{
  visible: boolean
  villageId: string
  categoryOptions: CategoryOption[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', body: VillageMatchRecruitCreateRequest): void
}>()

const { t } = useI18n()

interface RecruitFormState {
  category: VillageMatchRecruitCategory
  title: string
  description: string
  matchDate: string
  matchTimeStart: string
  matchTimeEnd: string
  venue: string
  requiredCount: string
  contactMethod: string
  applicationDeadline: string
}

function emptyForm(): RecruitFormState {
  return {
    category: 'PRACTICE_MATCH',
    title: '',
    description: '',
    matchDate: '',
    matchTimeStart: '',
    matchTimeEnd: '',
    venue: '',
    requiredCount: '',
    contactMethod: '',
    applicationDeadline: '',
  }
}

const form = ref<RecruitFormState>(emptyForm())
const postingIdentity = ref<PostingIdentitySelection | null>(null)

const visibleModel = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

// Dialog が開かれた瞬間にフォームを初期化する（元コードの openCreateDialog 相当）
watch(
  () => props.visible,
  (next) => {
    if (next) {
      form.value = emptyForm()
      postingIdentity.value = null
    }
  },
)

/**
 * BE の必須項目（`MatchRecruitCreateRequest`）:
 *   category @NotNull / title @NotBlank / matchDate @NotNull
 * matchDate を空のまま送ると 400 になるため、送信前に FE でも塞ぐ。
 */
const canSubmit = computed(
  () => form.value.title.trim() !== '' && form.value.matchDate !== '',
)

function onSubmit() {
  if (!canSubmit.value) return
  const postedByTeamId
    = postingIdentity.value?.subjectType === 'TEAM'
      ? postingIdentity.value.subjectId
      : null
  const body: VillageMatchRecruitCreateRequest = {
    category: form.value.category,
    title: form.value.title.trim(),
    description: form.value.description || null,
    // BE は @NotNull。canSubmit で空を弾いているため必ず値が入る
    matchDate: form.value.matchDate,
    matchTimeStart: form.value.matchTimeStart || null,
    matchTimeEnd: form.value.matchTimeEnd || null,
    venue: form.value.venue || null,
    requiredCount: form.value.requiredCount ? Number(form.value.requiredCount) : null,
    contactMethod: form.value.contactMethod || null,
    applicationDeadline: form.value.applicationDeadline
      ? `${form.value.applicationDeadline}:00`
      : null,
    postedByTeamId,
  }
  emit('submit', body)
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    modal
    :draggable="false"
    :header="t('village.matchRecruit.createTitle')"
    :style="{ width: '36rem' }"
    :breakpoints="{ '640px': '92vw' }"
  >
    <div class="flex flex-col gap-3">
      <!-- Phase 2: 投稿主体 Selector を有効化 -->
      <VillagePostingIdentitySelector
        :village-id="villageId"
        :model-value="postingIdentity"
        :visible="true"
        @update:model-value="(v) => (postingIdentity = v)"
      />
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.field.category') }}
        </label>
        <Select
          v-model="form.category"
          :options="categoryOptions"
          option-value="value"
          option-label="label"
          class="w-full"
        />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.matchRecruit.recruitTitle') }}
          <span class="text-red-600">*</span>
        </label>
        <InputText v-model="form.title" :maxlength="100" class="w-full" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.matchRecruit.description') }}
        </label>
        <Textarea v-model="form.description" class="w-full" rows="3" />
      </div>
      <div class="grid grid-cols-3 gap-3">
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.matchRecruit.matchDate') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText v-model="form.matchDate" type="date" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.matchRecruit.matchTimeStart') }}
          </label>
          <InputText v-model="form.matchTimeStart" type="time" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.matchRecruit.matchTimeEnd') }}
          </label>
          <InputText v-model="form.matchTimeEnd" type="time" class="w-full" />
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.matchRecruit.venue') }}
          </label>
          <InputText v-model="form.venue" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.matchRecruit.requiredCount') }}
          </label>
          <InputText
            v-model="form.requiredCount"
            type="number"
            min="0"
            class="w-full"
          />
        </div>
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.matchRecruit.contactMethod') }}
        </label>
        <InputText v-model="form.contactMethod" class="w-full" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.matchRecruit.deadline') }}
        </label>
        <InputText
          v-model="form.applicationDeadline"
          type="datetime-local"
          class="w-full"
        />
      </div>
    </div>
    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="visibleModel = false"
      />
      <Button
        :label="t('village.action.save')"
        icon="pi pi-check"
        severity="primary"
        :disabled="!canSubmit"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
