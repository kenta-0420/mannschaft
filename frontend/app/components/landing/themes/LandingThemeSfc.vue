<script setup lang="ts">
/**
 * LandingThemeSfc: スーパーファミコン（1990年）時代テーマのランディングページ。
 * RPGダイアログウィンドウ・DotGothic16フォント・SFCカラーパレットを使用。
 * モバイルでは useEraTheme.ts 側で表示制御済みのため、このコンポーネントはデスクトップ専用。
 */
const { t } = useI18n()
const router = useRouter()

/** 現在選択中のメニュー項目 (0: はじめる, 1: つづきから, 2: きのう一覧) */
const selectedMenu = ref(0)

/** セーブダイアログの表示状態 */
const showSaveDialog = ref(false)

/** 機能セクションへのアンカーID */
const FEATURES_ANCHOR = 'sfc-features'

function handleMenuSelect(index: number) {
  selectedMenu.value = index
}

function handleMenuActivate(index: number) {
  if (index === 0) {
    router.push('/register')
  } else if (index === 1) {
    router.push('/login')
  } else if (index === 2) {
    // 機能セクションへスクロール
    const el = document.getElementById(FEATURES_ANCHOR)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth' })
    }
  }
}

function handleSaveYes() {
  router.push('/register')
}

function handleSaveNo() {
  showSaveDialog.value = false
}
</script>

<template>
  <div class="sfc-root">
    <!-- SFCロゴ風ヘッダー -->
    <header class="sfc-header">
      <span class="sfc-brand">MANNSCHAFT</span>
    </header>

    <main class="sfc-main" role="main">
      <!-- メインメニュー画面（RPGタイトル画面風） -->
      <section class="sfc-section" aria-label="メインメニュー">
        <div class="sfc-dialog" role="menu" :aria-label="t('landing.era_themes.sfc.menu_start')">
          <button
            class="sfc-menu-item"
            :class="{ 'sfc-menu-item--selected': selectedMenu === 0 }"
            role="menuitem"
            @mouseenter="handleMenuSelect(0)"
            @focus="handleMenuSelect(0)"
            @click="handleMenuActivate(0)"
          >
            <span class="sfc-cursor" :class="{ 'sfc-cursor--visible': selectedMenu === 0 }" aria-hidden="true">▶</span>
            <span>{{ t('landing.era_themes.sfc.menu_start') }}</span>
          </button>
          <button
            class="sfc-menu-item"
            :class="{ 'sfc-menu-item--selected': selectedMenu === 1 }"
            role="menuitem"
            @mouseenter="handleMenuSelect(1)"
            @focus="handleMenuSelect(1)"
            @click="handleMenuActivate(1)"
          >
            <span class="sfc-cursor" :class="{ 'sfc-cursor--visible': selectedMenu === 1 }" aria-hidden="true">▶</span>
            <span>{{ t('landing.era_themes.sfc.menu_continue') }}</span>
          </button>
          <button
            class="sfc-menu-item"
            :class="{ 'sfc-menu-item--selected': selectedMenu === 2 }"
            role="menuitem"
            @mouseenter="handleMenuSelect(2)"
            @focus="handleMenuSelect(2)"
            @click="handleMenuActivate(2)"
          >
            <span class="sfc-cursor" :class="{ 'sfc-cursor--visible': selectedMenu === 2 }" aria-hidden="true">▶</span>
            <span>{{ t('landing.era_themes.sfc.menu_features') }}</span>
          </button>
        </div>
      </section>

      <!-- 機能紹介セクション（RPGステータス画面風） -->
      <section :id="FEATURES_ANCHOR" class="sfc-section" aria-label="機能一覧">
        <div class="sfc-dialog">
          <div class="sfc-status-row">
            <span class="sfc-status-label">HP</span>
            <span class="sfc-status-name">{{ t('landing.features.items.0.title') }}</span>
            <span class="sfc-status-bar" aria-hidden="true">████████</span>
            <span class="sfc-status-value">100</span>
          </div>
          <div class="sfc-status-row">
            <span class="sfc-status-label">MP</span>
            <span class="sfc-status-name">{{ t('landing.features.items.1.title') }}</span>
            <span class="sfc-status-bar" aria-hidden="true">████████</span>
            <span class="sfc-status-value">100</span>
          </div>
          <div class="sfc-status-row">
            <span class="sfc-status-label">ATK</span>
            <span class="sfc-status-name">{{ t('landing.features.items.2.title') }}</span>
            <span class="sfc-status-bar" aria-hidden="true">████████</span>
            <span class="sfc-status-value">100</span>
          </div>
          <div class="sfc-status-row">
            <span class="sfc-status-label">DEF</span>
            <span class="sfc-status-name">{{ t('landing.features.items.3.title') }}</span>
            <span class="sfc-status-bar" aria-hidden="true">████████</span>
            <span class="sfc-status-value">100</span>
          </div>
        </div>
      </section>

      <!-- CTAセクション（セーブダイアログ風） -->
      <section class="sfc-section" aria-label="登録CTA">
        <div v-if="!showSaveDialog" class="sfc-cta-trigger">
          <button class="sfc-dialog sfc-cta-btn" @click="showSaveDialog = true">
            <span class="sfc-dialog-text">{{ t('landing.era_themes.sfc.save_dialog') }}</span>
            <span class="sfc-cursor sfc-cursor--blink" aria-hidden="true">▼</span>
          </button>
        </div>
        <div v-else class="sfc-dialog sfc-save-dialog" role="dialog" :aria-label="t('landing.era_themes.sfc.save_dialog')">
          <p class="sfc-dialog-text">{{ t('landing.era_themes.sfc.save_dialog') }}</p>
          <div class="sfc-save-choices">
            <button class="sfc-choice-btn sfc-choice-btn--yes" @click="handleSaveYes">
              【{{ t('landing.era_themes.sfc.yes') }}】
            </button>
            <button class="sfc-choice-btn" @click="handleSaveNo">
              【{{ t('landing.era_themes.sfc.no') }}】
            </button>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* SFCカラーパレット変数 */
.sfc-root {
  --sfc-bg: #201c31;
  --sfc-panel: #2c2854;
  --sfc-border: #7b68ee;
  --sfc-text: #e8e8e8;
  --sfc-accent: #ffdf00;
  --sfc-cursor: #ff6b6b;
  --sfc-header: #4169e1;

  background-color: var(--sfc-bg);
  color: var(--sfc-text);
  font-family: 'DotGothic16', monospace;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* SFCロゴ風ヘッダー */
.sfc-header {
  background: linear-gradient(to right, #4169e1, #1e3fa8, #4169e1);
  border-bottom: 4px solid #ffd700;
  padding: 12px 24px;
  text-align: center;
}

.sfc-brand {
  font-family: 'DotGothic16', monospace;
  font-size: clamp(18px, 3vw, 28px);
  color: var(--sfc-accent);
  letter-spacing: 4px;
  font-weight: bold;
}

/* メインコンテンツ */
.sfc-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32px;
  padding: 40px 24px;
  width: 100%;
  max-width: 680px;
  margin: 0 auto;
  box-sizing: border-box;
}

.sfc-section {
  width: 100%;
}

/* RPGダイアログウィンドウ */
.sfc-dialog {
  background: var(--sfc-panel);
  border: 4px solid var(--sfc-border);
  box-shadow:
    inset 2px 2px 0 rgba(255, 255, 255, 0.1),
    4px 4px 0 rgba(0, 0, 0, 0.5);
  border-radius: 4px;
  padding: 16px 24px;
  font-family: 'DotGothic16', monospace;
  color: var(--sfc-text);
  width: 100%;
  box-sizing: border-box;
}

/* メニュー項目 */
.sfc-menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  background: none;
  border: none;
  color: var(--sfc-text);
  font-family: 'DotGothic16', monospace;
  font-size: clamp(14px, 2vw, 18px);
  padding: 10px 8px;
  cursor: pointer;
  text-align: left;
  border-radius: 2px;
  transition: background-color 0.1s;
}

.sfc-menu-item:hover,
.sfc-menu-item:focus,
.sfc-menu-item--selected {
  background-color: rgba(123, 104, 238, 0.2);
  color: var(--sfc-accent);
  outline: none;
}

/* カーソル（▶）アニメーション */
.sfc-cursor {
  color: var(--sfc-cursor);
  width: 16px;
  flex-shrink: 0;
  opacity: 0; /* デフォルト非表示 */
}

.sfc-cursor--visible {
  animation: sfc-cursor-blink 0.8s step-end infinite;
}

.sfc-cursor--blink {
  opacity: 1;
  animation: sfc-cursor-blink 0.8s step-end infinite;
}

@keyframes sfc-cursor-blink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .sfc-cursor--visible,
  .sfc-cursor--blink {
    animation: none;
    opacity: 1;
  }
}

/* ステータス行 */
.sfc-status-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  font-family: 'DotGothic16', monospace;
  font-size: clamp(12px, 1.5vw, 16px);
  border-bottom: 1px solid rgba(123, 104, 238, 0.3);
}

.sfc-status-row:last-child {
  border-bottom: none;
}

.sfc-status-label {
  color: var(--sfc-accent);
  font-weight: bold;
  width: 36px;
  flex-shrink: 0;
}

.sfc-status-name {
  flex: 1;
  color: var(--sfc-text);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sfc-status-bar {
  color: #4caf50;
  letter-spacing: -2px;
  font-size: 14px;
}

.sfc-status-value {
  color: var(--sfc-accent);
  width: 36px;
  text-align: right;
  flex-shrink: 0;
}

/* CTAダイアログ */
.sfc-cta-btn {
  background: var(--sfc-panel);
  cursor: pointer;
  text-align: left;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sfc-cta-btn:hover,
.sfc-cta-btn:focus {
  background-color: rgba(123, 104, 238, 0.3);
  outline: 2px solid var(--sfc-border);
  outline-offset: 2px;
}

.sfc-dialog-text {
  font-family: 'DotGothic16', monospace;
  font-size: clamp(14px, 2vw, 18px);
  color: var(--sfc-text);
  line-height: 1.7;
}

/* セーブダイアログ */
.sfc-save-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sfc-save-choices {
  display: flex;
  gap: 16px;
  justify-content: center;
}

.sfc-choice-btn {
  background: rgba(123, 104, 238, 0.2);
  border: 2px solid var(--sfc-border);
  border-radius: 2px;
  color: var(--sfc-text);
  font-family: 'DotGothic16', monospace;
  font-size: clamp(14px, 2vw, 18px);
  padding: 10px 24px;
  cursor: pointer;
  transition: background-color 0.1s, color 0.1s;
}

.sfc-choice-btn:hover,
.sfc-choice-btn:focus {
  background-color: rgba(123, 104, 238, 0.5);
  color: var(--sfc-accent);
  outline: 2px solid var(--sfc-accent);
  outline-offset: 2px;
}

.sfc-choice-btn--yes {
  border-color: var(--sfc-accent);
  color: var(--sfc-accent);
}

.sfc-choice-btn--yes:hover,
.sfc-choice-btn--yes:focus {
  background-color: rgba(255, 223, 0, 0.15);
}
</style>
