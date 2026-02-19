# FRONTEND_CODING_CONVENTION.md

## 1. 基本方針 (Core Principles)
- **Nuxt 3 / Vue 3 (Composition API)**: 全てのコンポーネントで `<script setup lang="ts">` を必須とする。
- **TypeScript First**: 全ての変数、プロパティ、関数に型定義を行い、`any` の使用を原則禁止する。
- **Auto-imports**: Nuxt 3 の自動インポート機能を活用し、明示的な `import` 文を最小限に抑える。
- **Single Responsibility**: 1つのコンポーネントや関数には1つの責務のみを持たせる。
- **インデント**: **半角スペース2つ**を使用してください。

## 2. 命名規則 (Naming Conventions)
- **Components**: `PascalCase` で命名し、2語以上の単語を組み合わせる（例: `TeamList.vue`, `UserBaseButton.vue`）。
- **Variables / Functions**: `camelCase` を使用する。
- **Constants**: `UPPER_SNAKE_CASE` を使用する。
- **Files / Directories**: 原則 `kebab-case` とする。ただし以下は例外として `camelCase` を使用する:
    - `composables/` 配下: `use[Feature].ts`（例: `useAuth.ts`, `useErrorHandler.ts`）
    - `stores/` 配下: `use[Name]Store.ts`（例: `useAuthStore.ts`）
- **Stores**: `use[Name]Store` 形式で命名する（例: `useAuthStore`）。

## 3. スタイリング
- **Tailwind CSS**: スタイリングには **Tailwind CSS** を使用してください。
- **ユーティリティ優先**: インラインの `<style>` は最小限にし、可能な限り Tailwind のユーティリティクラスで完結させること。
- **共通化**: 繰り返し利用されるデザインパターンは、コンポーネント化して再利用性を高めること。
- **ダークモード**: Tailwind の `dark:` バリアントで実装する。`tailwind.config` の `darkMode: 'class'` を使用し、`<html>` 要素へのクラス付与で切替を制御する。ユーザー設定（`user_appearance_settings`）に応じて `useAppearance` composable が自動付与する。OS設定追従（SYSTEM）には `window.matchMedia('(prefers-color-scheme: dark)')` を使用する。
- **背景色プリセット・シーズナル壁紙**: CSS カスタムプロパティ（`--bg-color`, `--bg-image`）を `<html>` または `<body>` に付与し、Tailwind の任意値（`bg-[var(--bg-color)]`）で参照する。シーズナル壁紙はAPIから取得した `image_url` を設定する。

## 4. ディレクトリ構成と責務 (Directory Structure)
| ディレクトリ | 役割・責務 |
| :--- | :--- |
| `components/` | 再利用可能なUI部品。機能単位のサブディレクトリで管理。 |
| `composables/` | 状態を持つロジック、および共通APIクライアント。 |
| `pages/` | ファイルシステムルーティングに基づく各画面。 |
| `layouts/` | 共通のページ外装。 |
| `middleware/` | ルートガード（認証チェック、ロール制御、モジュール有効化チェック）。 |
| `stores/` | Piniaによるグローバル状態管理。 |
| `utils/` | 状態を持たない純粋な関数（Helper）。 |
| `types/` | TypeScript型定義。`types/generated/` にOpenAPI Generator自動生成型を配置。 |
| `assets/` | CSSや画像などの静的リソース。 |

### 状態管理の使い分け
- **Pinia stores**: ログイン情報やカート情報など、アプリケーション全体で共有し画面を跨いで保持すべき状態に使用する。
- **Composables**: 各機能（ページ）に閉じたロジックや、その画面内だけで使う一時的な状態に使用する。
- 基本方針: **Composables を優先**し、グローバルな管理が必要になった時のみ Pinia を導入する。

## 5. API通信規約 (API Client & Fetch Settings)
Nuxt 3 標準の `ofetch` をベースにした共通クライアントを使用する。

### 共通クライアント (`composables/useApi.ts`) の実装指針
- **Base URL**: `runtimeConfig.public.apiBase` から取得する。
- **Authentication**: リクエストヘッダーに `Authorization: Bearer <JWT>` を自動付与する（AuthStoreから取得）。
- **Method Usage**:
    - `useFetch` / `useAsyncData`: SSRが必要なページ初期データの取得に使用。
    - `$fetch`: ユーザー操作に伴うデータ送信（POST/PUT/DELETE等）に使用。
- **Error Handling**: 401 (Unauthorized) 発生時は、認証ストアをクリアしログイン画面へリダイレクトする共通処理を実装する。
- **SSR と認証の制約**: JWT は `localStorage` に保存するため、SSR（サーバーサイドレンダリング）時にはアクセスできない。**SSR で実行されるリクエストに認証トークンは付与しない**。SSR は公開ページ（SEO目的）にのみ使用し、認証が必要なページは SPA モードで動作させること。

### API通信ロジックの配置ルール
- **`useApi.ts` の役割**: 共通の通信設定（BaseURL、認証ヘッダーの付与、共通エラーハンドリング等）のみを記述し、特定のビジネスロジックを持たせない。
- **機能別 Composable への分散**: 具体的・機能的なAPI呼び出し（例：`fetchOrders`, `updateProfile` 等）は、各機能ごとの `composables/use[Feature].ts` に定義する。
- **理由**: `useApi.ts` が巨大な一枚岩（モノリス）になるのを防ぎ、機能単位でのテストやメンテナンスを容易にするため。

## 6. コンポーネント設計 (Component Design)
- **Template**: HTML構造は意味論的（Semantic HTML）に記述する。
- **Script**: `defineProps` および `defineEmits` を用いて、コンポーネントの入出力を型定義する。
- **Styles**: 原則 `<style scoped>` を使用。共通変数は `assets/css` から読み込む。
- **Line Limit**: 1つのコンポーネントが **300行** を超える場合は、ロジックを `composables` に、UIをサブコンポーネントに分割する。

## 7. セキュリティ (Security)
- **XSS対策**: `v-html` は原則禁止。使用する場合はサニタイズ処理を必須とする。
- **Validation**: フォーム入力は **Zod** と **VeeValidate** を併用する。Zod でバリデーションスキーマ（ルール）を定義し、VeeValidate の `useForm` 等に渡してフォーム管理を行う。Zod の `z.infer<>` を活用して TypeScript 型も抽出すること。
- **Environment Variables**: APIキー等の機密情報は `runtimeConfig` を介し、ブラウザに公開するもの（public）とサーバー側限定のもの（private）を厳密に区別する。

### 認証トークン管理ルール
- **保存先**: JWT（Access Token / Refresh Token）は、ブラウザの `localStorage` または `sessionStorage` に保存し、JavaScript から制御する。
- **送信方法**: APIリクエストごとに `Authorization: Bearer <token>` ヘッダーをプログラムで付与する。
- **Cookie 使用禁止**: 認証トークンを Cookie に格納しないこと。Cookie を利用しないことで CSRF 攻撃を構造的に排除する。
- **XSS対策との併用**: トークン漏洩（XSS）リスクに対しては、Access Token の有効期限を短く（15分）設定し、Refresh Token Rotation を併用することで軽減する。

### フロント・バック間のバリデーション同期
- **方針**: バックエンドが提供する OpenAPI (Swagger) 仕様書を正（Single Source of Truth）とする。
- **自動生成の導入**: `openapi-zod-client` 等のツールを活用し、OpenAPI 定義からフロントエンド用の Zod スキーマを自動生成するワークフローを構築する。
- **目的**: 二重定義による実装漏れや、フロントとバックでのバリデーションルールの乖離を防止し、メンテナンスコストを削減するため。

## 8. 静的解析・フォーマッター (ESLint + Prettier)
- **ESLint**: TypeScript / Vue ファイルのコード品質チェックに使用する。バックエンドにおける Checkstyle に相当する役割。
    - **設定ベース**: `@nuxt/eslint-config` をベースに、本規約に合わせてカスタマイズする。
    - **主な検査項目**:
        - `any` 型の使用禁止（`@typescript-eslint/no-explicit-any`）
        - 未使用変数の検出（`@typescript-eslint/no-unused-vars`）
        - `v-html` の使用警告（`vue/no-v-html`）
        - コンポーネント命名の PascalCase 強制（`vue/component-name-in-template-casing`）
    - **CI 統合**: GitHub Actions の CI パイプラインに `npx eslint .` を組み込み、エラーがある場合はビルドを失敗させる。
- **Prettier**: コードフォーマットの自動統一に使用する。ESLint とはルールが競合しないよう `eslint-config-prettier` で Prettier 側を優先する。
    - **主な設定**: `semi: false`, `singleQuote: true`, `tabWidth: 2`, `trailingComma: 'all'`（Nuxt 3 コミュニティの標準に準拠）。
    - **保存時自動整形**: IDE（VS Code 等）の設定で保存時に Prettier を自動実行し、手動フォーマットの手間を排除する。
- **抑制**: やむを得ず規約に従えない箇所は `// eslint-disable-next-line ルール名 -- 理由` で1行単位で抑制すること。ファイル単位の `/* eslint-disable */` は原則禁止する。

### 設定ファイル一覧
プロジェクトルートに以下の設定ファイルを配置し、チーム全体で統一する。

**`eslint.config.mjs`**（Flat Config 形式）:
```js
import { createConfigForNuxt } from '@nuxt/eslint-config/flat'

export default createConfigForNuxt({
  features: { tooling: true, stylistic: true },
}).append({
  rules: {
    '@typescript-eslint/no-explicit-any': 'error',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    'vue/no-v-html': 'warn',
    'vue/component-name-in-template-casing': ['error', 'PascalCase'],
  },
})
```

**`.prettierrc`**:
```json
{
  "semi": false,
  "singleQuote": true,
  "tabWidth": 2,
  "trailingComma": "all",
  "printWidth": 100,
  "endOfLine": "lf"
}
```

**`.prettierignore`**:
```
dist/
.nuxt/
.output/
node_modules/
types/generated/
```

### IDE 設定（フロントエンド）
開発者がコーディング中にリアルタイムでミスに気づけるよう、以下の IDE 設定を整備する。

#### VS Code
**`.vscode/extensions.json`**（推奨拡張機能）:
```json
{
  "recommendations": [
    "vue.volar",
    "dbaeumer.vscode-eslint",
    "esbenp.prettier-vscode",
    "editorconfig.editorconfig",
    "bradlc.vscode-tailwindcss"
  ]
}
```

**`.vscode/settings.json`**（ワークスペース設定）:
```json
{
  "editor.defaultFormatter": "esbenp.prettier-vscode",
  "editor.formatOnSave": true,
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": "explicit"
  },
  "[vue]": {
    "editor.defaultFormatter": "esbenp.prettier-vscode"
  },
  "[typescript]": {
    "editor.defaultFormatter": "esbenp.prettier-vscode"
  },
  "eslint.validate": ["javascript", "typescript", "vue"],
  "files.eol": "\n",
  "files.trimTrailingWhitespace": true,
  "files.insertFinalNewline": true,
  "typescript.tsdk": "node_modules/typescript/lib"
}
```

上記により、保存時に Prettier によるフォーマットと ESLint による自動修正が同時に実行される。

### pre-commit フック セットアップ手順
コミット前に ESLint / Prettier を自動実行し、規約違反がリポジトリに混入するのを防止する。

1. **依存インストールと Husky 初期化**:
```bash
npm install -D husky lint-staged
npx husky init
```

2. **`package.json` に prepare スクリプトを確認**（`npx husky init` で自動追加される）:
```json
{
  "scripts": {
    "prepare": "husky"
  }
}
```

3. **`.husky/pre-commit` を編集**:
```bash
npx lint-staged
```

4. **`package.json` に lint-staged 設定を追加**:
```json
{
  "lint-staged": {
    "*.{ts,vue}": ["eslint --fix", "prettier --write"],
    "*.{json,md,yml,css}": ["prettier --write"]
  }
}
```

5. **動作確認**: `git add` でステージしたファイルのみを対象にチェックが実行される。全ファイルを対象にしないことで高速性を維持する。

6. **新規開発者のオンボーディング**: `npm install` 実行時に `prepare` スクリプト経由で Husky が自動セットアップされるため、追加の手順は不要。

- **強制力**: フックのスキップ（`git commit --no-verify`）は緊急時のみに限定し、通常の開発では禁止する。

## 9. ドラッグ＆ドロップ (Drag & Drop)

コルクボード機能など、カードの自由配置が必要な画面では **`@vueuse/core` の `useDraggable`** を使用する。

- **採用理由**: Nuxt 3 / Vue 3 の Composition API と親和性が高く、リスト並び替えではなく**自由座標配置**に適している。SSR との相性も問題なし（`client-only` ラッパーを使用）
- **位置の保存**: ドラッグ終了時（`pointerup`）にのみ `PATCH /corkboards/{id}/cards/{cardId}/position` を呼び出す。ドラッグ中は CSS transform でリアルタイム移動させ、APIコールは行わない（過剰なリクエストを防止）
- **リスト並び替え**（TODO、シフト管理等）が必要な場合は `vue-draggable-next`（Sortable.js ベース）を使用する

```ts
// 使用例: コルクボードカードの自由配置
const cardEl = ref<HTMLElement | null>(null)
const { x, y, isDragging } = useDraggable(cardEl, {
  initialValue: { x: card.pos_x, y: card.pos_y },
  onEnd: () => updateCardPosition(card.id, x.value, y.value),
})
```

## 10. パフォーマンス (Performance)
- **NuxtImg**: 画像には `NuxtImg` モジュールを活用し、フォーマット最適化を行う。
- **Lazy Loading**: ページ下部の重いコンポーネントは `Lazy` プレフィックスコンポーネント（例: `<LazyHeavyChart />`）として読み込む。

## 11. テスト (Testing)
- **基本ツール**: **Vitest** と **Vue Test Utils** を採用する。
- **テスト対象の優先順位**:
    1. **Composables**: 共通ロジック（`useApi.ts`, `useErrorHandler.ts` 等）
    2. **Zodバリデーション定義**: スキーマの正常系・異常系
    3. **Piniaストア**: 状態管理のアクション・ゲッター
    4. **UIコンポーネント**: 重要な操作フロー（フォーム送信、条件分岐表示等）
- **テストファイルの配置ルール**:
    - テストファイル（`.spec.ts`）は必ず対象ソースファイルと**同一ディレクトリ**に配置すること（例: `composables/useAuth.spec.ts`）。
    - 同一ディレクトリ内であっても、`__tests__/` などのサブディレクトリを作成してテストを分離することは**禁止**する。
    - プロジェクト全体でテスト配置のパターンを1つに絞り、ディレクトリ構造の迷いを完全に排除する。
- **E2Eテスト**: **Playwright** を将来的に導入予定。現時点では方針策定のみとし、プロジェクト成熟後に着手する。

## 12. エラーレポート (Error Reporting)

アプリケーション全体で発生した予期せぬエラーをユーザー経由で開発者へ通知するための仕組みを設ける。

### グローバルエラーハンドラーの設置

**`plugins/errorHandler.client.ts`** にクライアント専用プラグインを作成し、以下2つのエラーソースを捕捉する。

```ts
export default defineNuxtPlugin((nuxtApp) => {
  // Vue コンポーネント内の同期・非同期エラー
  nuxtApp.vueApp.config.errorHandler = (error, instance, info) => {
    useErrorReport().capture(error, { context: info })
  }

  // Promise の未処理 rejection（fetch失敗等）
  window.addEventListener('unhandledrejection', (event) => {
    useErrorReport().capture(event.reason)
  })
})
```

### `useErrorReport` composable

**`composables/useErrorReport.ts`** に実装する。

- **`capture(error, meta?)`**: エラーを受け取り、エラーレポートモーダル（`ErrorReportModal.vue`）を表示状態にする。自動収集情報（エラーメッセージ・スタックトレース・URL・UA・タイムスタンプ）を内部 state に保持する。
- **`submit(userComment?)`**: バックエンドの公開エンドポイント `POST /api/v1/error-reports` へ送信する。認証不要のため `$fetch` のベースURLのみ使用し、JWT は付与しない。送信後はモーダルを「送信完了」状態に切り替え、お礼メッセージを表示する。
- **エラー連鎖の防止**: `capture` 内でさらにエラーが発生した場合は `console.error` に留め、再帰的なモーダル表示を行わない。

### `ErrorReportModal.vue` コンポーネント

- 通常のページ遷移をブロックしない **モーダル（オーバーレイ）** として実装する。`Teleport` を利用して `<body>` 直下に描画する。
- **表示状態**:
  1. **入力画面**: エラー発生の旨を伝えるメッセージ + 任意のコメント入力欄 + 「送信する」ボタン + 「閉じる」リンク
  2. **送信中**: ボタンをローディング状態にし、二重送信を防止する。
  3. **完了画面**: 「ご報告ありがとうございます。開発チームが確認します。」とお礼を表示し、数秒後に自動クローズ。
- エラーの技術的詳細（スタックトレース等）はユーザーに表示しない。

### 送信データ仕様

| フィールド | 取得方法 |
| :--- | :--- |
| `error_message` | `error.message` |
| `stack_trace` | `error.stack`（先頭2,000文字） |
| `page_url` | `window.location.href` |
| `user_agent` | `navigator.userAgent` |
| `user_comment` | ユーザー入力（任意） |
| `user_id` | AuthStore から取得（未ログイン時は null） |
| `occurred_at` | `new Date().toISOString()` |

### 注意事項
- エラーレポートの送信エンドポイントは認証不要（`POST /api/v1/error-reports` は公開）。ただしレート制限をバックエンドで実施する。
- 開発環境（`NODE_ENV === 'development'`）では `console.error` へのフォールスルーのみとし、モーダルを表示しないオプションを設ける（開発中の誤送信防止）。

## 13. メール送信フィードバック (Email Send Feedback)

**メールを送信するすべての操作は、送信完了を画面でユーザーへ通知すること。**
APIが 200/201/202 を返した時点で、以下のルールに従いフィードバックを表示する。

### 表示方針

| 状況 | UIパターン | 理由 |
|------|-----------|------|
| フォーム送信後にページ遷移しない（例: パスワードリセット申請） | **インラインメッセージ**（フォームの下部に差し込む） | トーストより視線が近く、次のアクションを促しやすい |
| フォーム送信後にページが切り替わる（例: 会員登録完了画面） | **専用の完了ページ**またはページ上部のバナー | 送信先や有効期限を詳しく伝えられる |
| 操作中に副次的にメールが送られる（例: OAuth衝突時） | **トースト通知**（画面上部または下部） | 主要フローを妨げずに通知できる |

### メッセージに含める情報

1. **送信先メールアドレス**（マスクせず表示する。ユーザー自身が送信先を把握できることが優先）
2. **有効期限**（例: 「24時間以内に」「30分以内に」）
3. **次のアクション**（例: 「メール内のリンクをクリックしてください」）
4. **届かない場合の対処法**（例: 「メールが届かない場合は迷惑メールフォルダを確認するか、再送信してください」）

### 再送信ボタン

- 「再送信する」ボタンを提供するフローでは、**送信後60秒間はボタンをdisabledにしてカウントダウンを表示**する。
  - 連打による過剰リクエストを防ぎつつ、ユーザーに待機が必要であることを伝える。
  - カウントダウン終了後にボタンをアクティブに戻す。

```vue
<!-- 再送信ボタンの実装例 -->
<script setup lang="ts">
const cooldown = ref(0)
const canResend = computed(() => cooldown.value === 0)

const handleResend = async () => {
  await resendVerificationEmail()
  cooldown.value = 60
  const timer = setInterval(() => {
    cooldown.value--
    if (cooldown.value <= 0) clearInterval(timer)
  }, 1000)
}
</script>

<template>
  <button :disabled="!canResend" @click="handleResend">
    {{ canResend ? '再送信する' : `再送信まで ${cooldown}秒` }}
  </button>
</template>
```

### 各フローの表示例

| フロー | 表示メッセージ例 |
|-------|----------------|
| 会員登録 | 「確認メールを user@example.com に送信しました。24時間以内にメール内のリンクをクリックして登録を完了してください。」|
| 確認メール再送信 | 「確認メールを再送信しました。届かない場合は迷惑メールフォルダをご確認ください。」|
| パスワードリセット申請 | 「パスワードリセット用のメールを user@example.com に送信しました。30分以内に手続きを完了してください。」|
| メールアドレス変更 | 「確認メールを new@example.com に送信しました。24時間以内にメール内のリンクをクリックして変更を完了してください。」|
| OAuth衝突（統合確認） | 「このメールアドレスには既存のアカウントが存在します。連携承認メールを user@example.com に送信しました。メールを確認してください。」|
