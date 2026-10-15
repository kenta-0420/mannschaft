<template>
  <div class="geo-page" role="main">
    <!-- マーキー風スクロールバナー -->
    <div class="marquee" aria-label="welcome banner">
      <span class="marquee__inner">{{ $t('landing.era_themes.y1998.welcome_message') }}</span>
    </div>

    <!-- ヘッダー -->
    <header class="geo-header">
      <h1 class="rainbow-title" aria-label="MANNSCHAFT">
        <span
          v-for="letter in brandLetters"
          :key="letter.index"
          :style="{ color: letter.color }"
          class="rainbow-char"
        >{{ letter.char }}</span>
      </h1>

      <p class="blink geo-badge">{{ $t('landing.hero.badge') }}</p>

      <p class="construction-row">
        <span class="construction-icon" aria-hidden="true"/>
        <span class="construction-text">{{ $t('landing.era_themes.y1998.under_construction') }}</span>
        <span class="construction-icon" aria-hidden="true"/>
      </p>
    </header>

    <!-- 訪問者カウンター -->
    <section class="visitor-section" aria-label="visitor counter">
      <span class="visitor-label">{{ $t('landing.era_themes.y1998.visitor_count') }}</span>
      <span class="counter" role="img" aria-label="visitor count 001337042">
        <span
          v-for="(digit, idx) in counterDigits"
          :key="idx"
          class="counter__digit"
        >{{ digit }}</span>
      </span>
      <span class="visitor-label">{{ $t('landing.era_themes.y1998.visitor_count_suffix') }}</span>
    </section>

    <!-- メインコンテンツ テーブル風2カラム -->
    <div class="geo-table" role="region" aria-label="main content">
      <!-- 左カラム: 機能リスト -->
      <div class="geo-row">
        <div class="geo-cell geo-cell--left">
          <h2 class="geo-section-title">&#x2605; FEATURES &#x2605;</h2>
          <ul class="feature-list">
            <li v-for="i in 4" :key="i" class="feature-item">
              <span class="star-bullet" aria-hidden="true">&#x2605;</span>
              {{ $t(`landing.features.items.${i - 1}.title`) }}
            </li>
          </ul>
        </div>

        <!-- 右カラム: サブタイトル + CTA -->
        <div class="geo-cell geo-cell--right">
          <h2 class="geo-section-title">&#x00BB; ABOUT &#x00AB;</h2>
          <p class="geo-subtitle">{{ $t('landing.hero.subtitle') }}</p>
          <div class="cta-box">
            <a href="/register" class="geo-cta-link">
              &#x25B6; {{ $t('landing.hero.cta_register') }}
            </a>
          </div>
        </div>
      </div>
    </div>

    <!-- フッター -->
    <footer class="geo-footer">
      <div class="rainbow-hr" aria-hidden="true"/>
      <p class="best-viewed">{{ $t('landing.era_themes.y1998.best_viewed') }}</p>
      <p class="made-with">{{ $t('landing.era_themes.y1998.made_with') }}</p>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

// 虹色カラーパレット
const rainbowColors: string[] = [
  '#ff0000',
  '#ff7700',
  '#ffff00',
  '#00cc00',
  '#0000ff',
  '#8800ff',
  '#ff00ff',
]

// "MANNSCHAFT" を文字ごとに色付き配列へ変換
const brandLetters = computed(() =>
  'MANNSCHAFT'.split('').map((char, i) => ({
    char,
    color: rainbowColors[i % rainbowColors.length],
    index: i,
  }))
)

// 訪問者カウンター固定値をdigit配列へ
const counterDigits = computed((): string[] => '001337042'.split(''))

// i18n（テンプレート内で $t を直接使用しているため参照のみ）
void t
</script>

<style scoped>
/* ===== ページ全体 ===== */
.geo-page {
  background-color: #000000;
  background-image:
    linear-gradient(45deg, #111 25%, transparent 25%),
    linear-gradient(-45deg, #111 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #111 75%),
    linear-gradient(-45deg, transparent 75%, #111 75%);
  background-size: 20px 20px;
  background-position: 0 0, 0 10px, 10px -10px, -10px 0px;
  color: #ffffff;
  font-family: 'Times New Roman', Times, serif;
  min-height: 100vh;
  padding: 0 0 24px;
}

/* ===== マーキー ===== */
.marquee {
  overflow: hidden;
  white-space: nowrap;
  background: #000080;
  border-bottom: 2px solid #ffff00;
  padding: 4px 0;
  color: #ffff00;
  font-family: 'Courier New', Courier, monospace;
  font-size: 14px;
}

.marquee__inner {
  display: inline-block;
  animation: marquee 12s linear infinite;
}

@keyframes marquee {
  0% { transform: translateX(100%); }
  100% { transform: translateX(-100%); }
}

@media (prefers-reduced-motion: reduce) {
  .marquee__inner {
    animation: none;
  }
}

/* ===== ヘッダー ===== */
.geo-header {
  text-align: center;
  padding: 24px 16px 16px;
  border-bottom: 4px double #ffff00;
}

/* ===== 虹色タイトル ===== */
.rainbow-title {
  font-size: clamp(2rem, 8vw, 4rem);
  font-family: 'Arial Black', Arial, sans-serif;
  font-weight: 900;
  letter-spacing: 4px;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.8);
  margin: 0 0 12px;
  line-height: 1.1;
}

.rainbow-char {
  display: inline-block;
}

/* ===== 点滅テキスト ===== */
.blink {
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .blink {
    animation: none;
  }
}

.geo-badge {
  color: #00ffff;
  font-size: 14px;
  margin: 8px 0;
}

/* ===== 工事中アイコン ===== */
.construction-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 12px 0 0;
}

.construction-icon {
  display: inline-block;
  width: 32px;
  height: 32px;
  background: repeating-linear-gradient(
    45deg,
    #ffcc00,
    #ffcc00 4px,
    #000000 4px,
    #000000 8px
  );
  border-radius: 2px;
  animation: construction-spin 1s linear infinite;
  flex-shrink: 0;
}

@keyframes construction-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .construction-icon {
    animation: none;
  }
}

.construction-text {
  color: #ffff00;
  font-size: 16px;
  font-weight: bold;
  font-family: 'Courier New', Courier, monospace;
}

/* ===== 訪問者カウンター ===== */
.visitor-section {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px;
  background: linear-gradient(180deg, #000033 0%, #000066 100%);
  border-top: 2px solid #0000ff;
  border-bottom: 2px solid #0000ff;
  flex-wrap: wrap;
}

.visitor-label {
  color: #ffffff;
  font-family: 'Courier New', Courier, monospace;
  font-size: 14px;
}

.counter {
  display: inline-flex;
  gap: 2px;
  background: #000000;
  border: 2px inset #888888;
  padding: 2px 4px;
}

.counter__digit {
  background: #001100;
  color: #00ff00;
  font-family: 'Courier New', Courier, monospace;
  font-size: 18px;
  padding: 2px 4px;
  border: 1px solid #004400;
  min-width: 16px;
  text-align: center;
}

/* ===== テーブル風レイアウト ===== */
.geo-table {
  display: table;
  width: 90%;
  max-width: 860px;
  border-spacing: 4px;
  margin: 20px auto;
}

.geo-row {
  display: table-row;
}

.geo-cell {
  display: table-cell;
  padding: 16px;
  border: 1px solid #999999;
  vertical-align: top;
  width: 50%;
}

.geo-cell--left {
  background: rgba(0, 0, 80, 0.6);
}

.geo-cell--right {
  background: rgba(0, 60, 0, 0.6);
}

.geo-section-title {
  color: #ffff00;
  font-size: 16px;
  font-family: 'Arial Black', Arial, sans-serif;
  margin: 0 0 12px;
  text-align: center;
  letter-spacing: 2px;
}

/* ===== 機能リスト ===== */
.feature-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.feature-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  color: #00ffff;
  font-size: 14px;
  border-bottom: 1px dotted #333333;
}

.feature-item:last-child {
  border-bottom: none;
}

.star-bullet {
  color: #ffff00;
  flex-shrink: 0;
}

/* ===== サブタイトル ===== */
.geo-subtitle {
  color: #cccccc;
  font-size: 13px;
  line-height: 1.6;
  margin: 0 0 16px;
}

/* ===== CTAボックス ===== */
.cta-box {
  text-align: center;
  margin-top: 16px;
}

.geo-cta-link {
  display: inline-block;
  background: #000080;
  color: #00ffff;
  border: 2px outset #aaaaaa;
  padding: 8px 20px;
  font-family: 'Courier New', Courier, monospace;
  font-size: 14px;
  font-weight: bold;
  text-decoration: none;
  letter-spacing: 1px;
  transition: background 0.15s;
}

.geo-cta-link:hover {
  background: #0000cc;
  color: #ffffff;
}

.geo-cta-link:visited {
  color: #ff00ff;
}

/* ===== フッター ===== */
.geo-footer {
  text-align: center;
  padding: 16px;
  margin-top: 8px;
}

/* 虹グラデーション hr */
.rainbow-hr {
  height: 4px;
  background: linear-gradient(
    90deg,
    #ff0000 0%,
    #ff7700 16%,
    #ffff00 33%,
    #00ff00 50%,
    #0000ff 66%,
    #8800ff 83%,
    #ff00ff 100%
  );
  border: none;
  margin: 0 0 16px;
}

.best-viewed {
  color: #888888;
  font-size: 11px;
  font-family: 'Courier New', Courier, monospace;
  margin: 0 0 8px;
}

.made-with {
  color: #ffff00;
  font-size: 13px;
  font-family: 'Courier New', Courier, monospace;
  margin: 0;
}

/* ===== レスポンシブ: 小画面ではテーブルを縦並びに ===== */
@media (max-width: 600px) {
  .geo-table,
  .geo-row,
  .geo-cell {
    display: block;
    width: 100%;
  }

  .geo-cell {
    margin-bottom: 4px;
  }
}
</style>
