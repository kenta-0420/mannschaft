<script setup lang="ts">
/**
 * F17.1 村機能 — 練習試合・募集タブ 応募 Dialog
 *
 * 投稿主体 Selector とメッセージ入力欄を表示し、確定時は
 * VillageMatchApplicationCreateRequest を親へ通知する。
 * API 呼び出し・応募一覧の再取得は親 (villages/[id]/match-recruits.vue) が担当。
 */
import type {
  VillageMatchApplicationCreateRequest,
} from '~/types/village'
import type { PostingIdentitySelection } from '~/components/VillagePostingIdentitySelector.vue'

const props = defineProps<{
  visible: boolean
  villageId: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', body: VillageMatchApplicationCreateRequest): void
}>()

const { t } = useI18n()

const message = ref('')
const postingIdentity = ref<PostingIdentitySelection | null>(null)

const visibleModel = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

// Dialog が開かれた瞬間にフォームを初期化する（元コードの openApplyDialog 相当）
watch(
  () => props.visible,
  (next) => {
    if (next) {
      message.value = ''
      postingIdentity.value = null
    }
  },
)

function onSubmit() {
  const applicantTeamId
    = postingIdentity.value?.subjectType === 'TEAM'
      ? postingIdentity.value.subjectId
      : null
  const body: VillageMatchApplicationCreateRequest = {
    message: message.value || null,
    applicantTeamId,
  }
  emit('submit', body)
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    modal
    :draggable="false"
    :header="t('village.matchRecruit.applyTitle')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '92vw' }"
  >
    <div class="flex flex-col gap-3">
      <VillagePostingIdentitySelector
        :village-id="villageId"
        :model-value="postingIdentity"
        :visible="true"
        @update:model-value="(v) => (postingIdentity = v)"
      />
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.matchRecruit.applyMessage') }}
        </label>
        <Textarea v-model="message" class="w-full" rows="4" />
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
        :label="t('village.matchRecruit.apply')"
        icon="pi pi-send"
        severity="primary"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
