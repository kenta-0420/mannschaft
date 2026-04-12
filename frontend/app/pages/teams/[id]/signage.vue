<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import type {
  SignageScreen,
  SignageSlot,
  SignageToken,
  SignageLayout,
  SignageTransition,
  SignageSlotType,
} from '~/types/signage'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)

const {
  getScreens,
  createScreen,
  updateScreen,
  deleteScreen,
  getSlots,
  addSlot,
  deleteSlot,
  getTokens,
  issueToken,
  deleteToken,
  sendEmergency,
} = useSignageApi()
const { success, error: showError } = useNotification()

// --- State ---
const screens = ref<SignageScreen[]>([])
const loadingScreens = ref(false)
const selectedScreen = ref<SignageScreen | null>(null)
const slots = ref<SignageSlot[]>([])
const loadingSlots = ref(false)
const tokens = ref<SignageToken[]>([])
const loadingTokens = ref(false)
const showDetailPanel = ref(false)

// Dialog state
const showScreenDialog = ref(false)
const editingScreen = ref<SignageScreen | null>(null)
const savingScreen = ref(false)

const showSlotDialog = ref(false)
const savingSlot = ref(false)

const showEmergencyDialog = ref(false)
const emergencyMessage = ref('')
const sendingEmergency = ref(false)

// --- Constants ---
const LAYOUT_OPTIONS: { label: string; value: SignageLayout }[] = [
  { label: 'フルスクリーン', value: 'FULLSCREEN' },
  { label: '水平分割', value: 'SPLIT_HORIZONTAL' },
  { label: '垂直分割', value: 'SPLIT_VERTICAL' },
  { label: '4分割', value: 'QUAD' },
]

const TRANSITION_OPTIONS: { label: string; value: SignageTransition }[] = [
  { label: 'なし', value: 'NONE' },
  { label: 'フェード', value: 'FADE' },
  { label: 'スライド', value: 'SLIDE' },
  { label: 'ズーム', value: 'ZOOM' },
]

const SLOT_TYPE_OPTIONS: { label: string; value: SignageSlotType }[] = [
  { label: '画像', value: 'IMAGE' },
  { label: '動画', value: 'VIDEO' },
  { label: 'URL', value: 'URL' },
  { label: 'お知らせ', value: 'ANNOUNCEMENT' },
  { label: 'スケジュール', value: 'SCHEDULE' },
  { label: '天気', value: 'WEATHER' },
]

// --- Zod schemas ---
const screenSchema = z.object({
  name: z.string().min(1, '画面名は必須です').max(100, '100文字以内で入力してください'),
  description: z.string().max(500, '500文字以内').optional(),
  layout: z.enum(['FULLSCREEN', 'SPLIT_HORIZONTAL', 'SPLIT_VERTICAL', 'QUAD']),
  defaultSlideDuration: z
    .number({ invalid_type_error: '数値を入力してください' })
    .int()
    .min(1, '1秒以上を指定してください')
    .max(3600, '3600秒以内で指定してください'),
  transitionEffect: z.enum(['NONE', 'FADE', 'SLIDE', 'ZOOM']),
})

type ScreenForm = z.infer<typeof screenSchema>

const {
  defineField: defineScreenField,
  handleSubmit: handleScreenSubmit,
  resetForm: resetScreenForm,
  errors: screenErrors,
  setValues: setScreenValues,
} = useForm<ScreenForm>({
  validationSchema: toTypedSchema(screenSchema),
  initialValues: {
    name: '',
    description: '',
    layout: 'FULLSCREEN',
    defaultSlideDuration: 10,
    transitionEffect: 'FADE',
  },
})

const [screenName, screenNameAttrs] = defineScreenField('name')
const [screenDescription, screenDescriptionAttrs] = defineScreenField('description')
const [screenLayout, screenLayoutAttrs] = defineScreenField('layout')
const [screenDuration, screenDurationAttrs] = defineScreenField('defaultSlideDuration')
const [screenTransition, screenTransitionAttrs] = defineScreenField('transitionEffect')

const slotSchema = z.object({
  slotType: z.enum(['IMAGE', 'VIDEO', 'URL', 'ANNOUNCEMENT', 'SCHEDULE', 'WEATHER']),
  contentSourceId: z.string().optional(),
  durationSeconds: z
    .number({ invalid_type_error: '数値を入力してください' })
    .int()
    .min(1, '1秒以上を指定してください')
    .max(3600, '3600秒以内で指定してください'),
  displayCondition: z.string().optional(),
})

type SlotForm = z.infer<typeof slotSchema>

const {
  defineField: defineSlotField,
  handleSubmit: handleSlotSubmit,
  resetForm: resetSlotForm,
  errors: slotErrors,
} = useForm<SlotForm>({
  validationSchema: toTypedSchema(slotSchema),
  initialValues: {
    slotType: 'ANNOUNCEMENT',
    contentSourceId: '',
    durationSeconds: 10,
    displayCondition: '',
  },
})

const [slotType, slotTypeAttrs] = defineSlotField('slotType')
const [slotContentSourceId, slotContentSourceIdAttrs] = defineSlotField('contentSourceId')
const [slotDuration, slotDurationAttrs] = defineSlotField('durationSeconds')
const [slotDisplayCondition, slotDisplayConditionAttrs] = defineSlotField('displayCondition')

// --- Data loading ---
async function loadScreens() {
  loadingScreens.value = true
  try {
    const res = await getScreens('TEAM', teamId)
    screens.value = res.data
  } catch {
    showError('サイネージ画面一覧の取得に失敗しました')
  } finally {
    loadingScreens.value = false
  }
}

async function loadSlots(screenId: number) {
  loadingSlots.value = true
  try {
    const res = await getSlots(screenId)
    slots.value = res.data
  } catch {
    showError('スロット一覧の取得に失敗しました')
  } finally {
    loadingSlots.value = false
  }
}

async function loadTokens(screenId: number) {
  loadingTokens.value = true
  try {
    const res = await getTokens(screenId)
    tokens.value = res.data
  } catch {
    showError('トークン一覧の取得に失敗しました')
  } finally {
    loadingTokens.value = false
  }
}

// --- Screen actions ---
function openCreateScreen() {
  editingScreen.value = null
  resetScreenForm()
  showScreenDialog.value = true
}

function openEditScreen(screen: SignageScreen) {
  editingScreen.value = screen
  setScreenValues({
    name: screen.name,
    description: screen.description ?? '',
    layout: screen.layout,
    defaultSlideDuration: screen.defaultSlideDuration,
    transitionEffect: screen.transitionEffect,
  })
  showScreenDialog.value = true
}

async function openDetailPanel(screen: SignageScreen) {
  selectedScreen.value = screen
  showDetailPanel.value = true
  await Promise.all([loadSlots(screen.id), loadTokens(screen.id)])
}

function closeDetailPanel() {
  showDetailPanel.value = false
  selectedScreen.value = null
  slots.value = []
  tokens.value = []
}

const onSubmitScreen = handleScreenSubmit(async (values) => {
  savingScreen.value = true
  try {
    if (editingScreen.value) {
      await updateScreen(editingScreen.value.id, values)
      success('サイネージ画面を更新しました')
    } else {
      await createScreen({
        scopeType: 'TEAM',
        scopeId: teamId,
        ...values,
      })
      success('サイネージ画面を作成しました')
    }
    showScreenDialog.value = false
    await loadScreens()
  } catch {
    showError(editingScreen.value ? '画面の更新に失敗しました' : '画面の作成に失敗しました')
  } finally {
    savingScreen.value = false
  }
})

async function handleDeleteScreen(screen: SignageScreen) {
  if (!confirm(`「${screen.name}」を削除しますか？`)) return
  try {
    await deleteScreen(screen.id)
    success('サイネージ画面を削除しました')
    if (selectedScreen.value?.id === screen.id) closeDetailPanel()
    await loadScreens()
  } catch {
    showError('画面の削除に失敗しました')
  }
}

async function handleToggleActive(screen: SignageScreen) {
  try {
    await updateScreen(screen.id, { isActive: !screen.isActive })
    success(screen.isActive ? '画面を無効化しました' : '画面を有効化しました')
    await loadScreens()
    if (selectedScreen.value?.id === screen.id) {
      selectedScreen.value = { ...screen, isActive: !screen.isActive }
    }
  } catch {
    showError('ステータスの更新に失敗しました')
  }
}

// --- Slot actions ---
function openAddSlot() {
  resetSlotForm()
  showSlotDialog.value = true
}

const onSubmitSlot = handleSlotSubmit(async (values) => {
  if (!selectedScreen.value) return
  savingSlot.value = true
  try {
    await addSlot(selectedScreen.value.id, {
      slotType: values.slotType,
      contentSourceId: values.contentSourceId || undefined,
      durationSeconds: values.durationSeconds,
      displayCondition: values.displayCondition || undefined,
    })
    success('スロットを追加しました')
    showSlotDialog.value = false
    await loadSlots(selectedScreen.value.id)
  } catch {
    showError('スロットの追加に失敗しました')
  } finally {
    savingSlot.value = false
  }
})

async function handleDeleteSlot(slot: SignageSlot) {
  if (!selectedScreen.value) return
  if (!confirm('このスロットを削除しますか？')) return
  try {
    await deleteSlot(selectedScreen.value.id, slot.id)
    success('スロットを削除しました')
    await loadSlots(selectedScreen.value.id)
  } catch {
    showError('スロットの削除に失敗しました')
  }
}

// --- Token actions ---
async function handleIssueToken() {
  if (!selectedScreen.value) return
  try {
    await issueToken(selectedScreen.value.id)
    success('トークンを発行しました')
    await loadTokens(selectedScreen.value.id)
  } catch {
    showError('トークンの発行に失敗しました')
  }
}

async function handleDeleteToken(token: SignageToken) {
  if (!confirm('このトークンを削除しますか？')) return
  try {
    await deleteToken(token.id)
    success('トークンを削除しました')
    if (selectedScreen.value) await loadTokens(selectedScreen.value.id)
  } catch {
    showError('トークンの削除に失敗しました')
  }
}

async function copyToken(tokenStr: string) {
  try {
    await navigator.clipboard.writeText(tokenStr)
    success('トークンをコピーしました')
  } catch {
    showError('コピーに失敗しました')
  }
}

// --- Emergency ---
function openEmergency(screen: SignageScreen) {
  selectedScreen.value = screen
  emergencyMessage.value = ''
  showEmergencyDialog.value = true
}

async function handleSendEmergency() {
  if (!selectedScreen.value || !emergencyMessage.value.trim()) return
  sendingEmergency.value = true
  try {
    await sendEmergency(selectedScreen.value.id, emergencyMessage.value.trim())
    success('緊急メッセージを送信しました')
    showEmergencyDialog.value = false
    emergencyMessage.value = ''
  } catch {
    showError('緊急メッセージの送信に失敗しました')
  } finally {
    sendingEmergency.value = false
  }
}

// --- Helpers ---
function getLayoutLabel(layout: string): string {
  return LAYOUT_OPTIONS.find((o) => o.value === layout)?.label ?? layout
}

function getSlotTypeLabel(type: string): string {
  return SLOT_TYPE_OPTIONS.find((o) => o.value === type)?.label ?? type
}

onMounted(loadScreens)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="デジタルサイネージ" />
    </div>

    <!-- Screen list -->
    <div v-if="loadingScreens" class="flex justify-center py-10">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else>
      <div class="mb-4 flex justify-end">
        <Button label="画面を追加" icon="pi pi-plus" @click="openCreateScreen" />
      </div>

      <DashboardEmptyState
        v-if="screens.length === 0"
        icon="pi pi-desktop"
        message="サイネージ画面がありません"
      />

      <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <SectionCard
          v-for="screen in screens"
          :key="screen.id"
          class="flex flex-col"
        >
          <div class="mb-3 flex items-start justify-between gap-2">
            <div class="min-w-0">
              <h3 class="truncate font-semibold">{{ screen.name }}</h3>
              <p v-if="screen.description" class="mt-0.5 truncate text-sm text-surface-500">
                {{ screen.description }}
              </p>
            </div>
            <Tag
              :value="screen.isActive ? '有効' : '無効'"
              :severity="screen.isActive ? 'success' : 'secondary'"
              class="shrink-0"
            />
          </div>

          <div class="mb-4 flex flex-wrap gap-2 text-xs text-surface-500">
            <span class="rounded bg-surface-100 px-2 py-0.5 dark:bg-surface-800">
              {{ getLayoutLabel(screen.layout) }}
            </span>
            <span class="rounded bg-surface-100 px-2 py-0.5 dark:bg-surface-800">
              {{ screen.defaultSlideDuration }}秒
            </span>
          </div>

          <div class="mt-auto flex flex-wrap gap-1">
            <Button
              label="管理"
              icon="pi pi-cog"
              size="small"
              severity="info"
              @click="openDetailPanel(screen)"
            />
            <Button
              :label="screen.isActive ? '無効化' : '有効化'"
              size="small"
              :severity="screen.isActive ? 'secondary' : 'success'"
              text
              @click="handleToggleActive(screen)"
            />
            <Button
              label="緊急"
              icon="pi pi-exclamation-triangle"
              size="small"
              severity="warn"
              text
              @click="openEmergency(screen)"
            />
            <Button
              label="編集"
              size="small"
              severity="secondary"
              text
              @click="openEditScreen(screen)"
            />
            <Button
              label="削除"
              size="small"
              severity="danger"
              text
              @click="handleDeleteScreen(screen)"
            />
          </div>
        </SectionCard>
      </div>
    </div>

    <!-- Detail panel (sidebar) -->
    <Dialog
      v-model:visible="showDetailPanel"
      header="画面管理"
      :style="{ width: '640px' }"
      modal
      @hide="closeDetailPanel"
    >
      <div v-if="selectedScreen">
        <div class="mb-4 flex items-center gap-3">
          <i class="pi pi-desktop text-2xl text-primary-500" />
          <div>
            <h2 class="font-bold">{{ selectedScreen.name }}</h2>
            <p class="text-sm text-surface-500">{{ getLayoutLabel(selectedScreen.layout) }}</p>
          </div>
        </div>

        <!-- Slots section -->
        <section class="mb-6">
          <div class="mb-2 flex items-center justify-between">
            <h3 class="font-semibold">スロット</h3>
            <Button
              label="スロット追加"
              icon="pi pi-plus"
              size="small"
              @click="openAddSlot"
            />
          </div>

          <div v-if="loadingSlots" class="flex justify-center py-4">
            <ProgressSpinner style="width: 32px; height: 32px" />
          </div>

          <DataTable
            v-else
            :value="slots"
            data-key="id"
            size="small"
            striped-rows
          >
            <template #empty>
              <p class="py-4 text-center text-sm text-surface-400">スロットがありません</p>
            </template>
            <Column header="種別" style="width: 120px">
              <template #body="{ data }">
                <Tag :value="getSlotTypeLabel(data.slotType)" severity="info" />
              </template>
            </Column>
            <Column header="表示時間" style="width: 90px">
              <template #body="{ data }">
                {{ data.durationSeconds }}秒
              </template>
            </Column>
            <Column header="コンテンツID">
              <template #body="{ data }">
                <span class="truncate text-sm text-surface-500">
                  {{ data.contentSourceId ?? '—' }}
                </span>
              </template>
            </Column>
            <Column style="width: 60px">
              <template #body="{ data }">
                <Button
                  icon="pi pi-trash"
                  text
                  rounded
                  severity="danger"
                  size="small"
                  @click="handleDeleteSlot(data)"
                />
              </template>
            </Column>
          </DataTable>
        </section>

        <!-- Token section -->
        <section>
          <div class="mb-2 flex items-center justify-between">
            <h3 class="font-semibold">表示端末トークン</h3>
            <Button
              label="トークン発行"
              icon="pi pi-key"
              size="small"
              severity="secondary"
              @click="handleIssueToken"
            />
          </div>

          <div v-if="loadingTokens" class="flex justify-center py-4">
            <ProgressSpinner style="width: 32px; height: 32px" />
          </div>

          <div v-else-if="tokens.length === 0" class="py-4 text-center text-sm text-surface-400">
            トークンがありません
          </div>

          <div v-else class="flex flex-col gap-2">
            <div
              v-for="token in tokens"
              :key="token.id"
              class="flex items-center gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
            >
              <div class="min-w-0 flex-1">
                <p v-if="token.label" class="text-sm font-medium">{{ token.label }}</p>
                <p class="truncate font-mono text-xs text-surface-500">{{ token.token }}</p>
                <p v-if="token.lastSeenAt" class="mt-0.5 text-xs text-surface-400">
                  最終接続: {{ new Date(token.lastSeenAt).toLocaleString('ja-JP') }}
                </p>
              </div>
              <Button
                icon="pi pi-copy"
                text
                rounded
                size="small"
                severity="secondary"
                @click="copyToken(token.token)"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click="handleDeleteToken(token)"
              />
            </div>
          </div>
        </section>
      </div>
    </Dialog>

    <!-- Create / Edit screen dialog -->
    <Dialog
      v-model:visible="showScreenDialog"
      :header="editingScreen ? 'サイネージ画面を編集' : 'サイネージ画面を作成'"
      :style="{ width: '520px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onSubmitScreen">
        <div>
          <label class="mb-1 block text-sm font-medium">
            画面名 <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="screenName"
            v-bind="screenNameAttrs"
            class="w-full"
            placeholder="例: エントランスモニター"
          />
          <p v-if="screenErrors.name" class="mt-1 text-xs text-red-500">
            {{ screenErrors.name }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea
            v-model="screenDescription"
            v-bind="screenDescriptionAttrs"
            class="w-full"
            rows="2"
            placeholder="任意の説明"
          />
          <p v-if="screenErrors.description" class="mt-1 text-xs text-red-500">
            {{ screenErrors.description }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">レイアウト</label>
          <Select
            v-model="screenLayout"
            v-bind="screenLayoutAttrs"
            :options="LAYOUT_OPTIONS"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            デフォルト表示時間（秒） <span class="text-red-500">*</span>
          </label>
          <InputNumber
            v-model="screenDuration"
            v-bind="screenDurationAttrs"
            :min="1"
            :max="3600"
            class="w-full"
          />
          <p v-if="screenErrors.defaultSlideDuration" class="mt-1 text-xs text-red-500">
            {{ screenErrors.defaultSlideDuration }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">トランジション効果</label>
          <Select
            v-model="screenTransition"
            v-bind="screenTransitionAttrs"
            :options="TRANSITION_OPTIONS"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div class="flex justify-end gap-2">
          <Button
            label="キャンセル"
            severity="secondary"
            text
            @click="showScreenDialog = false"
          />
          <Button
            :label="editingScreen ? '更新' : '作成'"
            type="submit"
            :loading="savingScreen"
          />
        </div>
      </form>
    </Dialog>

    <!-- Add slot dialog -->
    <Dialog
      v-model:visible="showSlotDialog"
      header="スロットを追加"
      :style="{ width: '480px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onSubmitSlot">
        <div>
          <label class="mb-1 block text-sm font-medium">コンテンツ種別</label>
          <Select
            v-model="slotType"
            v-bind="slotTypeAttrs"
            :options="SLOT_TYPE_OPTIONS"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">コンテンツソースID</label>
          <InputText
            v-model="slotContentSourceId"
            v-bind="slotContentSourceIdAttrs"
            class="w-full"
            placeholder="任意"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            表示時間（秒） <span class="text-red-500">*</span>
          </label>
          <InputNumber
            v-model="slotDuration"
            v-bind="slotDurationAttrs"
            :min="1"
            :max="3600"
            class="w-full"
          />
          <p v-if="slotErrors.durationSeconds" class="mt-1 text-xs text-red-500">
            {{ slotErrors.durationSeconds }}
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">表示条件</label>
          <InputText
            v-model="slotDisplayCondition"
            v-bind="slotDisplayConditionAttrs"
            class="w-full"
            placeholder="任意"
          />
        </div>

        <div class="flex justify-end gap-2">
          <Button
            label="キャンセル"
            severity="secondary"
            text
            @click="showSlotDialog = false"
          />
          <Button label="追加" type="submit" :loading="savingSlot" />
        </div>
      </form>
    </Dialog>

    <!-- Emergency dialog -->
    <Dialog
      v-model:visible="showEmergencyDialog"
      header="緊急メッセージ送信"
      :style="{ width: '480px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div class="flex items-center gap-2 rounded-lg bg-orange-50 p-3 dark:bg-orange-900/20">
          <i class="pi pi-exclamation-triangle text-lg text-orange-500" />
          <p class="text-sm text-orange-700 dark:text-orange-400">
            送信したメッセージは即座にサイネージ画面に表示されます。
          </p>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            メッセージ <span class="text-red-500">*</span>
          </label>
          <Textarea
            v-model="emergencyMessage"
            class="w-full"
            rows="3"
            placeholder="緊急時に表示するメッセージを入力してください"
          />
        </div>

        <div class="flex justify-end gap-2">
          <Button
            label="キャンセル"
            severity="secondary"
            text
            @click="showEmergencyDialog = false"
          />
          <Button
            label="送信"
            severity="warn"
            icon="pi pi-send"
            :loading="sendingEmergency"
            :disabled="!emergencyMessage.trim()"
            @click="handleSendEmergency"
          />
        </div>
      </div>
    </Dialog>
  </div>
</template>
