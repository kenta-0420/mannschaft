import { defineComponent, h, ref, type Ref } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import {
  CardEditorContextKey,
  CardEditorModeKey,
  type CardEditorContext,
} from '~/components/corkboard/card-editor/useCardEditorContext'
import { useCardEditorForm } from '~/composables/useCardEditorForm'
import type { CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditor 子コンポーネント用テストヘルパ。
 *
 * `<CardEditorModal>` を実マウントすると Dialog が Teleport されるなど噛み合わせが多い。
 * 子コンポーネント単体の挙動を素早く検証するため、ここでは
 * テスト用ラッパーコンポーネントを生成し、その中で `useCardEditorForm()` を呼んで
 * `provide(CardEditorContextKey, form)` / `provide(CardEditorModeKey, mode)` するだけの
 * 最小限のセットアップを提供する。
 *
 * 各 spec はこの `mountWithContext` を経由して子コンポーネントを描画し、
 * 戻り値の `form` を介して context の状態を直接操作・検証する。
 */

export interface MountWithContextOptions {
  mode?: 'create' | 'edit'
  card?: CorkboardCardDetail | null
  boardId?: number
  defaultPosition?: { x: number; y: number }
}

/** ダミー i18n 翻訳関数。キーをそのまま返す。 */
export const mockT = (key: string) => key

/**
 * 子コンポーネントを CardEditorContext / CardEditorMode で provide した状態でマウントする。
 *
 * @returns wrapper（`mountSuspended` の戻り値）と form（context 操作用）
 */
export async function mountWithContext(
  child: ReturnType<typeof defineComponent> | object,
  options: MountWithContextOptions = {},
) {
  const modeRef: Ref<'create' | 'edit'> = ref(options.mode ?? 'create')
  const cardRef = ref<CorkboardCardDetail | null>(options.card ?? null)
  const boardIdRef = ref<number>(options.boardId ?? 1)
  const defaultPositionRef = ref(options.defaultPosition ?? { x: 0, y: 0 })

  // tFn を注入することで setup 外の useI18n 呼出を回避
  const form: CardEditorContext = useCardEditorForm(
    modeRef,
    cardRef,
    boardIdRef,
    defaultPositionRef,
    mockT,
  )

  const Wrapper = defineComponent({
    name: 'CardEditorContextWrapper',
    setup() {
      provide(CardEditorContextKey, form)
      provide(CardEditorModeKey, modeRef)
      return () => h(child as object)
    },
  })

  const wrapper = await mountSuspended(Wrapper)
  return { wrapper, form, modeRef, cardRef }
}

/** テスト用 CorkboardCardDetail ファクトリ */
export function makeCardDetail(
  overrides: Partial<CorkboardCardDetail> = {},
): CorkboardCardDetail {
  return {
    id: 1,
    corkboardId: 10,
    sectionId: null,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: null,
    body: null,
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: 'WHITE',
    cardSize: 'MEDIUM',
    positionX: 0,
    positionY: 0,
    zIndex: null,
    userNote: null,
    noteColor: null,
    autoArchiveAt: null,
    isArchived: false,
    isPinned: false,
    pinnedAt: null,
    isRefDeleted: false,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  }
}
