
## 恐竜の部屋 Phase 1

本人専用 `/my/ranch`、診断 `/my/ranch/results`、出生本人確認、24問診断、永久装飾と3枠の導線を追加。牧場未参加・非表示でも設定から本人結果へ到達する。素材manifest未登録時は文字fallback、公開gateは解除しない。新ranch.jsonは統合隊がnuxt.configの六localeへ登録する。合成UTは実機や本番素材の完成証拠ではない。


### 正式 v3 検証の現在地

恐竜牧場の担当 4 spec は 808 件通過、担当差分 32 TS/Vue files の ESLint は通過。
全体 `nuxt typecheck` は 4096 MiB の heap 上限で OOM / exit 1 となり、型検査は未通過。
固定 Unicode 17 境界は `unicode-segmenter=0.17.3` と共有公式 fixture で検証し、native Intl fallback を使わない。
検証 head/UTC/exit と実機・素材・生成 DTO・Settings/locale 登録の残件は `docs/ranch-phase1-worker-report.md` を参照。
本番 64 mapping/assets と診断 master の承認・実 API の検分が揃うまで有効化しない。

## 予定の代理出席設定

チーム・組織予定の作成と編集で、代理出席の許可・自動受諾を保存し、詳細取得後の編集にも引き継ぐ。
API の省略/null の扱いと既存代理を維持する OFF 時の契約は [F03.10 代理出席仕様](../docs/features/F03.10_proxy_attendance.md) §7.1 を参照。

## 本番 SSR の資産ヒント

ビルド manifest では未訪問ページの機会的な `prefetch` を無効にし、ログイン画面で大量の JS を事前取得しない。必須資産の `preload`、スクリプトと import 関係、クライアント側の NuxtLink prefetch は維持する。
個人ダッシュボードでは「今すぐ」のカレンダーとお知らせを通常読み込みし、折りたたみセクションのウィジェットは初回表示時に読み込む。

## チーム一覧の同時取得

`useTeamStore.fetchMyTeams()` は同じ Pinia store から同時に始まった signal なしの閲覧用取得を1回の HTTP 要求で共有する。更新後の同期に使う `fetchMyTeamsWithResult()` は毎回新しい要求を開始し、成否を呼び出し元へ返す。signal 付き取得も独立し、`clear()` 後や失敗後の新しい閲覧用取得は旧要求に接続しない。

## タイムラインの所属識別子

ダッシュボードとチーム・組織タイムラインは、取得済みの所属一覧・詳細シェルにある内部IDをフィード・投稿・添付・お知らせ登録へ渡す。公開slugは遷移先と権限確認URLに使い、数字だけのslugを内部IDへ変換しない。内部ID未取得中やURLと旧シェルが不一致の間は、フィードと投稿フォームを描画しない。追加の所属取得APIは呼ばず、バックエンドの所属確認と拒否応答は維持する。

## ダッシュボードのダイアログ読み込み

既定レイアウトのインストール案内・メモ・目安箱、個人パネルのチーム／組織作成・表示設定、カレンダーの日別一覧・予定作成／編集、チーム／組織グリッドの詳細プレビューは、初めて開く操作で読み込む。初回以降はインスタンスを保持し、既存の閉じる／保存時のリセットと再表示時の初期化を維持する。遅延読み込み時点で既に `visible=true` でも、下書き・選択日・編集対象・タグ・権限設定を初回から反映する。必須資産の preload と SSR/CSP の設定は維持する。

### PrimeVue テーマ

`app/themes/mannschaft.ts` の Aura と既存オプションを `primevue.importTheme` で SSR と CSR に渡す。preset を `options.theme` に置くと public runtimeConfig に複製され、静的 JS 配信にも設定の環境変数走査が毎回かかるため、テーマ実体はモジュールに保持する。テーマ変更はビルドへ反映する。ripple・filled 入力・`.p-dark`・CSP は従来どおり。
