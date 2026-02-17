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

## 8. パフォーマンス (Performance)
- **NuxtImg**: 画像には `NuxtImg` モジュールを活用し、フォーマット最適化を行う。
- **Lazy Loading**: ページ下部の重いコンポーネントは `Lazy` プレフィックスコンポーネント（例: `<LazyHeavyChart />`）として読み込む。

## 9. テスト (Testing)
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
