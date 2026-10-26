<script setup lang="ts">
// F08.10 観戦の接続状態インジケーター（04_frontend_and_ux.md §G.17）。
// 「ライブ接続中／再接続中／オフライン（最新でない可能性）／観戦できません」を
// 色＋アイコン＋テキストで明示する（色覚多様性に配慮し色のみに依存しない・§G.12）。
import type { SpectatorConnectionState } from '~/types/match'

const props = defineProps<{
  state: SpectatorConnectionState
}>()

const { t } = useI18n()

interface Indicator {
  icon: string
  /** Tailwind 色クラス（背景・文字）。 */
  cls: string
  label: string
  /** ライブ（接続中）はアイコンを脈動させる。 */
  pulse: boolean
}

const indicator = computed<Indicator>(() => {
  switch (props.state) {
    case 'LIVE':
      return {
        icon: 'pi pi-circle-fill',
        cls: 'bg-green-100 text-green-700',
        label: t('match.live.spectator.connection.live'),
        pulse: true,
      }
    case 'CONNECTING':
      return {
        icon: 'pi pi-spinner pi-spin',
        cls: 'bg-surface-100 text-surface-600',
        label: t('match.live.spectator.connection.connecting'),
        pulse: false,
      }
    case 'RECONNECTING':
      return {
        icon: 'pi pi-spinner pi-spin',
        cls: 'bg-amber-100 text-amber-700',
        label: t('match.live.spectator.connection.reconnecting'),
        pulse: false,
      }
    case 'OFFLINE':
      return {
        icon: 'pi pi-wifi',
        cls: 'bg-red-100 text-red-700',
        label: t('match.live.spectator.connection.offline'),
        pulse: false,
      }
    case 'DENIED':
      return {
        icon: 'pi pi-lock',
        cls: 'bg-surface-200 text-surface-700',
        label: t('match.live.spectator.connection.denied'),
        pulse: false,
      }
  }
  return {
    icon: 'pi pi-circle',
    cls: 'bg-surface-100 text-surface-600',
    label: '',
    pulse: false,
  }
})
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
    :class="indicator.cls"
    role="status"
    :aria-label="indicator.label"
  >
    <i :class="[indicator.icon, indicator.pulse ? 'animate-pulse' : '', 'text-[0.6rem]']" />
    {{ indicator.label }}
  </span>
</template>
