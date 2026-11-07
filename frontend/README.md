
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

## Spring Pageを受け取る一覧

`ApiResponse<SpringPage<T>>` の `data` は配列ではなく、`content` と `totalElements` を持つPageです。outbox、委員会一覧・配布履歴、運営ユーザー一覧は、受信型を明示して `toPagedResponse` で既存の `{ data: T[], meta }` へ変換します。総件数には `meta.total` を使い、現在ページの行数で代用しません。委員会2画面は20件の標準Paginatorで次ページへ進めます。取得失敗は空状態と区別し、古い行と総件数を消して再試行を表示します。

公開APIの直接Page、認証済みAPIのcontent直接参照、BE独自PagedResponse、Cursorは、それぞれの既存契約を維持します。`app/types/springPage.contract.ts` が6境界の型を、`springPage.coverage.spec.ts` がOpenAPIの27経路集合を照合します。

outboxの既存文言は各localeのsystem_admin_email_outbox.jsonをnuxt.configのfilesへ登録して読み込みます。試験だけで辞書を補完せず、実i18nを通して空状態・filter・既存操作を検証します。

システム管理ダッシュボードの既存管理メニューからメール送信管理へ進めます。再取得したmetaで現在pageが範囲外なら有効な末尾pageへ補正して再取得し、正規0件や総数が縮まないALL表示では余分な再取得やpage0への移動を行いません。
## タイムラインの所属識別子

ダッシュボードとチーム・組織タイムラインは、取得済みの所属一覧・詳細シェルにある内部IDをフィード・投稿・添付・お知らせ登録へ渡す。公開slugは遷移先と権限確認URLに使い、数字だけのslugを内部IDへ変換しない。内部ID未取得中やURLと旧シェルが不一致の間は、フィードと投稿フォームを描画しない。追加の所属取得APIは呼ばず、バックエンドの所属確認と拒否応答は維持する。

## ダッシュボードのダイアログ読み込み

既定レイアウトのインストール案内・メモ・目安箱、個人パネルのチーム／組織作成・表示設定、カレンダーの日別一覧・予定作成／編集、チーム／組織グリッドの詳細プレビューは、初めて開く操作で読み込む。初回以降はインスタンスを保持し、既存の閉じる／保存時のリセットと再表示時の初期化を維持する。遅延読み込み時点で既に `visible=true` でも、下書き・選択日・編集対象・タグ・権限設定を初回から反映する。必須資産の preload と SSR/CSP の設定は維持する。

## ダッシュボードのパネル読み込み

Carouselの外枠3section・タブ・トラックは常設し、復元された初期選択先のbodyだけを読み込む。未訪問bodyはPageLoadingを表示し、選択時にNuxt Lazy componentを初めて生成する。訪問後は非表示でも同じinstanceを保持し、入力途中の値・内部state・取得済みデータを戻るたびに破棄しない。未確定dragとtouchcancelは未訪問bodyの取得を始めず、cancel時は閾値超過後でも選択を確定しない。通常touchend・連打queue・reduced-motionと、共通scope-tabs/容量の同期は維持する。

### PrimeVue テーマ

`app/themes/mannschaft.ts` の Aura と既存オプションを `primevue.importTheme` で SSR と CSR に渡す。preset を `options.theme` に置くと public runtimeConfig に複製され、静的 JS 配信にも設定の環境変数走査が毎回かかるため、テーマ実体はモジュールに保持する。テーマ変更はビルドへ反映する。ripple・filled 入力・`.p-dark`・CSP は従来どおり。

チーム・組織タイムラインの投稿フォームは、同じ内部IDのフィード取得が成功してから表示する。読取成立は投稿権限の付与ではなく、既存の送信・配信範囲条件とバックエンドの投稿認可は維持する。403/404は既存の拒否文言を継続表示し、投稿・固定投稿・追加cursorとフォームを取り除く。更新時の一時的な500は、既に表示済みのフォームと下書きを保持する。スコープ切替と同一スコープの並行取得では、古い成功が新しい拒否を上書きしない。
