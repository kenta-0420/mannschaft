# 🎨 FRONTEND_CODING_CONVENTION.md

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
- **Files / Directories**: 原則 `kebab-case` とする。
- **Stores**: `use[Name]Store` 形式で命名する（例: `useAuthStore`）。

## 3. スタイリング 🎨
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
| `stores/` | Piniaによるグローバル状態管理。 |
| `utils/` | 状態を持たない純粋な関数（Helper）。 |
| `assets/` | CSSや画像などの静的リソース。 |

### 状態管理の使い分け
- **Pinia stores**: ログイン情報やカート情報など、アプリケーション全体で共有し画面を跨いで保持すべき状態に使用する。
- **Composables**: 各機能（ページ）に閉じたロジックや、その画面内だけで使う一時的な状態に使用する。
- 基本方針: **Composables を優先**し、グローバルな管理が必要になった時のみ Pinia を導入する。



## 5. API通信規約 (API Client & Fetch Settings) 📡
Nuxt 3 標準の `ofetch` をベースにした共通クライアントを使用する。

### 共通クライアント (`composables/useApi.ts`) の実装指針
- **Base URL**: `runtimeConfig.public.apiBase` から取得する。
- **Authentication**: リクエストヘッダーに `Authorization: Bearer <JWT>` を自動付与する（AuthStoreから取得）。
- **Method Usage**:
    - **`useFetch` / **`useAsyncData`**: SSRが必要なページ初期データの取得に使用。
    - **`$fetch`**: ユーザー操作に伴うデータ送信（POST/PUT/DELETE等）に使用。
- **Error Handling**: 401 (Unauthorized) 発生時は、認証ストアをクリアしログイン画面へリダイレクトする共通処理を実装する。

## 6. コンポーネント設計 (Component Design)
- **Template**: HTML構造は意味論的（Semantic HTML）に記述する。
- **Script**: `defineProps` および `defineEmits` を用いて、コンポーネントの入出力を型定義する。
- **Styles**: 原則 `<style scoped>` を使用。共通変数は `assets/css` から読み込む。
- **Line Limit**: 1つのコンポーネントが **300行** を超える場合は、ロジックを `composables` に、UIをサブコンポーネントに分割する。

## 7. セキュリティ (Security) 🔐
- **XSS対策**: `v-html` は原則禁止。使用する場合はサニタイズ処理を必須とする。
- **CSRF対策**: APIクライアント側で適切なヘッダー管理を行う。
- **Validation**: フォーム入力は **Zod** と **VeeValidate** を併用する。Zod でバリデーションスキーマ（ルール）を定義し、VeeValidate の `useForm` 等に渡してフォーム管理を行う。Zod の `z.infer<>` を活用して TypeScript 型も抽出すること。
- **Environment Variables**: APIキー等の機密情報は `runtimeConfig` を介し、ブラウザに公開するもの（public）とサーバー側限定のもの（private）を厳密に区別する。

## 8. パフォーマンス (Performance)
- **NuxtImg**: 画像には `NuxtImg` モジュールを活用し、フォーマット最適化を行う。
- **Lazy Loading**: ページ下部の重いコンポーネントは `Lazy` プレフィックスコンポーネント（例: `<LazyHeavyChart />`）として読み込む。