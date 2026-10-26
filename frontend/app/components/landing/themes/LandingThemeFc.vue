<script setup lang="ts">
/**
 * LandingThemeFc: ファミコン（1983年）時代テーマのランディングページ。
 * NESカラーパレット・ピクセルアート風ボーダー・Press Start 2Pフォントを使用。
 * モバイルでは useEraTheme.ts 側で表示制御済みのため、このコンポーネントはデスクトップ専用。
 */
import { useKonamiCommand } from '~/composables/useKonamiCommand'

const { t } = useI18n()

/**
 * Konamiコマンド成功時にファミコン風矩形波ビープ音を再生する。
 * ユーザー操作（キーボード入力）後の実行なのでAutoplay Policyに準拠。
 */
function playKonamiSound() {
  try {
    const AudioCtx = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!AudioCtx) return
    const ctx = new AudioCtx()
    const oscillator = ctx.createOscillator()
    const gainNode = ctx.createGain()
    oscillator.connect(gainNode)
    gainNode.connect(ctx.destination)
    oscillator.type = 'square' // ファミコン風矩形波
    oscillator.frequency.setValueAtTime(440, ctx.currentTime) // A4
    oscillator.frequency.setValueAtTime(880, ctx.currentTime + 0.1) // A5
    gainNode.gain.setValueAtTime(0.3, ctx.currentTime)
    gainNode.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.3)
    oscillator.start(ctx.currentTime)
    oscillator.stop(ctx.currentTime + 0.3)
  } catch {
    // Web Audio API非対応環境では無視
  }
}

useKonamiCommand(playKonamiSound)
</script>

<template>
  <div class="fc-root">
    <!-- タイトル画面風レイアウト -->
    <main class="fc-main" role="main">
      <!-- ブランドロゴ -->
      <h1 class="fc-brand">MANNSCHAFT</h1>
      <!-- サブタイトル -->
      <p class="fc-subtitle">TEAM MANAGEMENT SYSTEM</p>

      <!-- ピクセルセパレーター -->
      <div class="fc-separator" aria-hidden="true"/>

      <!-- ピクセルボックス：機能一覧 -->
      <div class="pixel-box" role="list" :aria-label="t('landing.features.heading')">
        <div class="fc-feature-item" role="listitem">
          <span class="fc-star" aria-hidden="true">★</span>
          <span>{{ t('landing.features.items.0.title') }}</span>
        </div>
        <div class="fc-feature-item" role="listitem">
          <span class="fc-star" aria-hidden="true">★</span>
          <span>{{ t('landing.features.items.1.title') }}</span>
        </div>
        <div class="fc-feature-item" role="listitem">
          <span class="fc-star" aria-hidden="true">★</span>
          <span>{{ t('landing.features.items.2.title') }}</span>
        </div>
        <div class="fc-feature-item" role="listitem">
          <span class="fc-star" aria-hidden="true">★</span>
          <span>{{ t('landing.features.items.3.title') }}</span>
        </div>
      </div>

      <!-- 点滅PUSH START + CTA -->
      <NuxtLink to="/register" class="fc-push-start" :aria-label="t('landing.era_themes.fc.push_start')">
        {{ t('landing.era_themes.fc.push_start') }}
      </NuxtLink>
    </main>

    <!-- コナミコマンドヒント（さりげなく小さく） -->
    <div class="fc-konami-hint" aria-label="Konami Code hint" aria-hidden="true">
      ↑↑↓↓←→←→BA
    </div>

    <!-- フッター -->
    <footer class="fc-footer">
      <p>{{ t('landing.era_themes.fc.copyright') }}</p>
    </footer>
  </div>
</template>

<style scoped>
/* NESカラーパレット変数 */
.fc-root {
  --fc-black: #000000;
  --fc-white: #fcfcfc;
  --fc-red: #b53120;
  --fc-orange: #f87858;
  --fc-yellow: #f8b800;
  --fc-green: #008038;
  --fc-cyan: #00e8d8;
  --fc-blue: #0000fc;
  --fc-gray: #bcbcbc;

  background-color: var(--fc-black);
  color: var(--fc-white);
  font-family: 'Press Start 2P', monospace;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  padding: 40px 24px 24px;
  box-sizing: border-box;
  image-rendering: pixelated;
}

/* メインコンテンツ */
.fc-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  width: 100%;
  max-width: 640px;
  text-align: center;
}

/* ブランドロゴ */
.fc-brand {
  font-family: 'Press Start 2P', monospace;
  font-size: clamp(20px, 4vw, 32px);
  color: var(--fc-yellow);
  letter-spacing: 4px;
  text-transform: uppercase;
  margin: 0;
  line-height: 1.4;
}

/* サブタイトル */
.fc-subtitle {
  font-family: 'Press Start 2P', monospace;
  font-size: clamp(8px, 1.5vw, 12px);
  color: var(--fc-white);
  letter-spacing: 2px;
  margin: 0;
  line-height: 1.8;
}

/* ピクセルセパレーター */
.fc-separator {
  width: 100%;
  height: 4px;
  background: var(--fc-white);
  box-shadow:
    0 4px 0 var(--fc-black),
    0 8px 0 var(--fc-white);
  margin: 8px 0;
}

/* ピクセルボックス（二重ボーダー枠） */
.pixel-box {
  border: 4px solid var(--fc-white);
  box-shadow:
    0 0 0 4px var(--fc-black),
    0 0 0 8px var(--fc-white);
  padding: 16px 24px;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 機能一覧アイテム */
.fc-feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: 'Press Start 2P', monospace;
  font-size: clamp(7px, 1.2vw, 10px);
  color: var(--fc-white);
  letter-spacing: 1px;
  text-align: left;
}

.fc-star {
  color: var(--fc-yellow);
  flex-shrink: 0;
}

/* 点滅PUSH START（CTA兼用） */
.fc-push-start {
  display: inline-block;
  animation: fc-blink 1s step-end infinite;
  color: var(--fc-white);
  font-family: 'Press Start 2P', monospace;
  font-size: 12px;
  letter-spacing: 2px;
  text-decoration: none;
  cursor: pointer;
  padding: 8px 16px;
  margin-top: 8px;
}

.fc-push-start:hover,
.fc-push-start:focus {
  color: var(--fc-yellow);
  outline: 2px solid var(--fc-yellow);
  outline-offset: 4px;
}

@keyframes fc-blink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .fc-push-start {
    animation: none;
  }
}

/* コナミコマンドヒント */
.fc-konami-hint {
  font-family: 'Press Start 2P', monospace;
  font-size: 8px;
  color: var(--fc-gray);
  opacity: 0.4;
  letter-spacing: 1px;
  text-align: center;
  margin-top: auto;
  padding: 16px 0 8px;
}

/* フッター */
.fc-footer {
  font-family: 'Press Start 2P', monospace;
  font-size: 8px;
  color: var(--fc-gray);
  letter-spacing: 1px;
  text-align: center;
  padding-top: 8px;
}

.fc-footer p {
  margin: 0;
}
</style>
