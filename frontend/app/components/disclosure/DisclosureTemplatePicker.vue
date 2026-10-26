<script setup lang="ts">
/**
 * 重説書 様式テンプレート選択モーダル（F09.14 Phase 2-β-5）。
 *
 * - 都道府県でフィルタ（JIS コード）
 * - システム提供（国交省標準）と組織カスタムを別セクションで提示
 * - 選択 → 親へ emit('select', template)
 */
import type { DisclosureFormTemplate } from '~/types/disclosure'

const props = defineProps<{
  organizationId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  select: [template: DisclosureFormTemplate]
}>()

const { t } = useI18n()
const { error: showError } = useNotification()

const api = computed(() => useDisclosureApi(String(props.organizationId)))

const templates = ref<DisclosureFormTemplate[]>([])
const loading = ref(false)
const selectedPrefecture = ref<string | null>(null)

/** JIS 都道府県コード一覧（簡易: 主要 8 件 + 全国共通）。Phase 3 で 47 件に拡張。 */
const prefectureOptions = computed(() => [
  { label: t('disclosure.templatePicker.allPrefectures'), value: null },
  { label: '東京都 (13)', value: '13' },
  { label: '大阪府 (27)', value: '27' },
  { label: '神奈川県 (14)', value: '14' },
  { label: '愛知県 (23)', value: '23' },
  { label: '埼玉県 (11)', value: '11' },
  { label: '千葉県 (12)', value: '12' },
  { label: '兵庫県 (28)', value: '28' },
  { label: '福岡県 (40)', value: '40' },
])

const systemTemplates = computed(() => templates.value.filter((tpl) => tpl.isSystemTemplate))
const customTemplates = computed(() => templates.value.filter((tpl) => !tpl.isSystemTemplate))

async function load() {
  if (!props.visible) return
  loading.value = true
  try {
    templates.value = await api.value.listTemplates(selectedPrefecture.value)
  } catch {
    showError(t('disclosure.errors.loadFailed'))
    templates.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) load()
})

watch(selectedPrefecture, () => {
  if (props.visible) load()
})

function onSelect(template: DisclosureFormTemplate) {
  emit('select', template)
  emit('update:visible', false)
}

function close() {
  emit('update:visible', false)
}

const internalVisible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})
</script>

<template>
  <Dialog
    v-model:visible="internalVisible"
    :header="t('disclosure.templatePicker.title')"
    modal
    :style="{ width: '50rem' }"
    :breakpoints="{ '768px': '95vw' }"
    data-testid="disclosure-template-picker"
  >
    <div class="space-y-4">
      <!-- 都道府県フィルタ -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('disclosure.templatePicker.prefecture') }}
        </label>
        <Dropdown
          v-model="selectedPrefecture"
          :options="prefectureOptions"
          option-label="label"
          option-value="value"
          class="w-full md:w-80"
          data-testid="disclosure-template-prefecture"
        />
      </div>

      <div v-if="loading" class="rounded-md border p-6 text-center text-sm text-surface-500">
        {{ t('disclosure.loading') }}
      </div>

      <div
        v-else-if="templates.length === 0"
        class="rounded-md border border-dashed p-6 text-center text-sm text-surface-500"
      >
        {{ t('disclosure.templatePicker.noTemplates') }}
      </div>

      <template v-else>
        <!-- システム提供 -->
        <section v-if="systemTemplates.length > 0">
          <h3 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
            {{ t('disclosure.templatePicker.systemTemplates') }}
          </h3>
          <ul class="space-y-2">
            <li
              v-for="tpl in systemTemplates"
              :key="tpl.id"
              class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-surface-200 p-3 dark:border-surface-700"
              :data-testid="`disclosure-template-${tpl.id}`"
            >
              <div>
                <p class="font-medium">{{ tpl.name }}</p>
                <p class="text-xs text-surface-500">
                  {{ tpl.code }} · {{ t('disclosure.templatePicker.version', { version: tpl.version }) }}
                </p>
              </div>
              <Button
                :label="t('disclosure.templatePicker.select')"
                size="small"
                severity="primary"
                @click="onSelect(tpl)"
              />
            </li>
          </ul>
        </section>

        <!-- 組織カスタム -->
        <section v-if="customTemplates.length > 0">
          <h3 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
            {{ t('disclosure.templatePicker.customTemplates') }}
          </h3>
          <ul class="space-y-2">
            <li
              v-for="tpl in customTemplates"
              :key="tpl.id"
              class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-surface-200 p-3 dark:border-surface-700"
              :data-testid="`disclosure-template-${tpl.id}`"
            >
              <div>
                <p class="font-medium">{{ tpl.name }}</p>
                <p class="text-xs text-surface-500">
                  {{ tpl.code }} · {{ t('disclosure.templatePicker.version', { version: tpl.version }) }}
                </p>
              </div>
              <Button
                :label="t('disclosure.templatePicker.select')"
                size="small"
                severity="secondary"
                @click="onSelect(tpl)"
              />
            </li>
          </ul>
        </section>
      </template>
    </div>

    <template #footer>
      <Button
        :label="t('disclosure.actions.cancel')"
        severity="secondary"
        text
        @click="close"
      />
    </template>
  </Dialog>
</template>
