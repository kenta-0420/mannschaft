# Mannschaft — 汎用組織管理プラットフォーム（バックエンド）

あらゆる組織・チーム・個人をシームレスに管理するWebアプリケーション。

## プロジェクト基本情報

| 項目 | 値 |
|------|-----|
| 正式名称 | Mannschaft |
| リポジトリ名 | mannschaft |
| パッケージ名 | com.mannschaft |
モジュール式テンプレートにより、スポーツチーム、整骨院、学校、会社、飲食店、美容室、ジム、町内会など業種を問わず柔軟に対応する。
独自のタイムライン（X風UI）やSlack風チャットを備え、メンバー間のコミュニケーションと外部への情報発信を両立する。

---

## 想定ユースケース

スポーツチーム / 整骨院・治療院 / 学校（クラス連絡・学年連絡・同窓会） / 会社 / 飲食店・店舗 / 美容室・サロン / コミュニティ・サークル / 医療・福祉（病院・介護施設・薬局） / ジム・フィットネス / 宗教・町内会 / その他（カスタムテンプレートで対応）

---

## 技術スタック

### バックエンド

| 区分 | 技術 |
|------|------|
| 言語 | Java 21 LTS |
| フレームワーク | Spring Boot 3.3.x |
| ビルドツール | Gradle (Kotlin DSL) |
| データベース | MySQL 8.0 (utf8mb4) |
| DBマイグレーション | Flyway |
| 認証 | JWT (jjwt) + Spring Security 6 |
| APIドキュメント | Springdoc OpenAPI (Swagger UI) |
| マッピング | MapStruct |
| リアルタイム通信 | WebSocket (STOMP), Redis (メッセージブローカー) |
| キャッシュ/セッション | Redis (トークン無効化管理) |
| テスト | Testcontainers (MySQL 8.0) |
| その他 | Lombok, Virtual Threads (Project Loom) |

### フロントエンド（別リポジトリ）

| 区分 | 技術 |
|------|------|
| フレームワーク | Nuxt.js 3 (Vue 3 ベース) |
| レンダリング | SSR + SPA ハイブリッド |
| 状態管理 | Pinia |
| SSR対象 | SEOが必要な公開ページ（ブログ、チーム紹介、活動記録等） |
| SPA対象 | ログイン後のダッシュボード、チャット等のインタラクティブ画面 |

## アプリケーション形態

- **SSR/SPA ハイブリッド**: Nuxt.js 3 によるサーバーサイドレンダリングとSPAの両立。バックエンドはJSON APIのみ提供
- **スマホ対応**: PWA対応、画像アップロード最適化、チャンクアップロード、WebSocketリアルタイム通知
- **フロントエンドは別リポジトリ**で管理

---

## アカウント構造

### 3層構造

| 層 | 説明 | 例 |
|----|------|----|
| **個人アカウント** | ユーザー単位。実名登録＋表示用ニックネーム。複数チーム/組織にマルチ所属可能。QR会員証を保有 | 田中太郎 |
| **チームアカウント** | チーム/店舗/教室等の運用単位。都道府県・市区町村を設定し地域検索可能。テンプレート選択で機能モジュールを決定 | FCマンシャフト / 田中整骨院 / 3年B組 |
| **組織アカウント** | 複数チームを束ねる上位組織。組織内に別の組織を登録する階層構造も可能 | 〇〇サッカー協会 / 〇〇高等学校 / 〇〇株式会社 |

### グループ階層

組織内にサブグループを自由に作成可能。グループ単位での連絡・スケジュール・チャット配信に対応。

例: 学校（組織）→ 学年（グループ）→ クラス（サブグループ）→ 同窓会（派生グループ）

### 紐付けと所属

- **招待URL連携**: チーム発行のURLから個人が紐付けされ、スムーズにメンバー加入
- **マルチ所属**: 1つの個人アカウントで複数のチームや組織に同時に所属可能
- **サポーター枠**: 招待不要で、外部から特定のチームを支援・フォローできる独立した枠組み

### ロール・パーミッション

| ロール | スコープ | アクセス範囲 |
|--------|----------|-------------|
| システム管理者 (SYSTEM_ADMIN) | プラットフォーム全体 | 全組織・チームの管理、テンプレート管理、システム設定、ユーザーBAN |
| 管理者 (ADMIN) | チーム/組織内 | 全機能（メンバー管理、備品管理、広告設定、投稿編集、ロール管理、モジュール設定） |
| メンバー (MEMBER) | チーム/組織内 | 出欠回答、限定コンテンツ閲覧、ギャラリー、タイムライン投稿、掲示板、チャット |
| サポーター (SUPPORTER) | チーム/組織内 | サポーター限定コンテンツ、活動速報 |
| ゲスト (GUEST) | チーム/組織内 | スケジュール・活動記録、公式ブログ、SNSフィード |

※ ロール・パーミッションは管理者画面から自由に追加・編集・削除可能
※ グループ単位でもロール設定可能

### セキュリティ・認証

- **2要素認証 (2FA)**: TOTP（Google Authenticator等）対応。SYSTEM_ADMIN・ADMINには必須化
- **OAuth2ソーシャルログイン**: Google / LINE / Apple によるワンクリック登録・ログイン
- **パスワードリセット**: メールによるリセットフロー（トークン有効期限付き）
- **アカウント凍結・退会フロー**: 管理者によるアカウント凍結、ユーザー自身の退会申請、退会時のデータ保持ポリシー（一定期間後に完全削除）

---

## モジュール式テンプレートシステム

モジュールは**デフォルト機能**と**選択式モジュール**の2種類に分かれる。

### 仕組み

- **デフォルト機能**: 全チームに常時提供される基本機能。選択リストには表示されない
- **選択式モジュール**: 運営側が提供するカタログから、チーム管理者が必要なものを選択して有効化
- **運営による随時追加**: 新しい選択式モジュールは運営側が開発・追加
- **テンプレート**: 業種別に推奨する選択式モジュールの組み合わせをプリセットとして用意
- **権限と公開設定**: 組織内でのチーム情報の開示レベル（組織内のみ、全公開、非公開）を各チームの裁量で設定可能

### 料金プラン

| プラン | 選択式モジュール数 | 料金 |
|--------|-------------------|------|
| 無料プラン | 10モジュールまで | 無料 |
| 有料プラン | 11モジュール以上 | 課金（従量 or 定額） |

※ デフォルト機能は無料プランに含まれ、モジュール数にカウントされない

### デフォルト機能（全チーム常時有効・選択リスト非表示）

| No. | 機能名 | 説明 |
|-----|--------|------|
| 1 | ダッシュボード | 個人/チーム/組織ダッシュボード・ウィジェットカスタマイズ |
| 2 | TODO管理 | タスク管理・担当者割り振り・進捗管理 |
| 3 | タイムライン | X風UIのチーム内投稿・交流 |
| 4 | CMS（ブログ・お知らせ） | 外部公開/メンバー限定ブログ・お知らせ配信 |
| 5 | チャット | Slack風階層スレッド・絵文字リアクション・グループチャット |
| 6 | プッシュ通知 | リマインド・LINE連携・WebSocket通知 |
| 7 | アンケート・投票 | カスタム設問・匿名/記名・集計グラフ |
| 8 | ファイル共有 | ドキュメント共有・フォルダ管理・アクセス権限 |
| 9 | 検索 | グローバル検索・フィルタリング |
| 10 | 通報・モデレーション | 不適切コンテンツ通報・レビュー・BAN対応 |
| 11 | メンション | @ユーザー名 通知・メンション一覧 |
| 12 | 監査ログ | 管理者操作履歴の自動記録・閲覧 |
| 13 | データエクスポート | CSV/JSON一括エクスポート |
| 14 | 多言語対応 (i18n) | 日本語/英語切替 |
| 15 | スケジュール・出欠管理 | カレンダー・Googleカレンダー同期・出欠回答 |
| 16 | 活動記録 | 活動内容記録・カスタムフィールド・統計 |
| 17 | 掲示板 | カテゴリ別・ツリー構造返信・重要度5段階・既読機能 |
| 18 | マッチング・対外交流 | 対戦/交流相手募集・NGチーム設定 |
| 19 | パフォーマンス管理 | カスタムデータ記録・統計ダッシュボード |
| 20 | メンバー紹介 | メンバー一覧・プロフィール・拡張フィールド |

### 選択式モジュール（カタログから選択・課金対象）

| No. | モジュール名 | 説明 |
|-----|-------------|------|
| 1 | QR会員証 | メンバーID発行・QRコード生成・スキャン認証 |
| 2 | 会費・決済 | 月会費/年会費/都度払い・集金管理 |
| 3 | 予約管理 | 時間枠予約・スタッフ別枠・リマインド |
| 4 | サービス履歴 | 施術/来店/対応履歴・カスタムフィールド |
| 5 | 備品管理 | 在庫管理・貸出ステータス |
| 6 | ギャラリー | 写真アルバム・アップロード・閲覧権限 |

※ 運営側が随時新モジュールを追加予定。選択式モジュールが10個を超えた場合、11個目以降の有効化には有料プランが必要

### レベル別モジュール適用

モジュールは**組織・チーム・個人**の各レベルで利用可能なものが異なる。SYSTEM_ADMINがシステム管理画面から各レベルへのモジュール適用設定を調整可能。

#### デフォルト機能のレベル別適用

| No. | 機能名 | 組織 | チーム | 個人 |
|-----|--------|:----:|:------:|:----:|
| 1 | ダッシュボード | ○ | ○ | ○ |
| 2 | TODO管理 | ○ | ○ | ○ |
| 3 | タイムライン | ○ | ○ | ○ |
| 4 | CMS（ブログ・お知らせ） | ○ | ○ | - |
| 5 | チャット | ○ | ○ | ○ |
| 6 | プッシュ通知 | ○ | ○ | ○ |
| 7 | アンケート・投票 | ○ | ○ | - |
| 8 | ファイル共有 | ○ | ○ | ○ |
| 9 | 検索 | ○ | ○ | ○ |
| 10 | 通報・モデレーション | ○ | ○ | ○ |
| 11 | メンション | ○ | ○ | ○ |
| 12 | 監査ログ | ○ | ○ | - |
| 13 | データエクスポート | ○ | ○ | ○ |
| 14 | 多言語対応 (i18n) | ○ | ○ | ○ |
| 15 | スケジュール・出欠管理 | ○ | ○ | ○ |
| 16 | 活動記録 | ○ | ○ | - |
| 17 | 掲示板 | ○ | ○ | - |
| 18 | マッチング・対外交流 | - | ○ | - |
| 19 | パフォーマンス管理 | - | ○ | ○ |
| 20 | メンバー紹介 | ○ | ○ | - |

#### 選択式モジュールのレベル別適用

| No. | モジュール名 | 組織 | チーム | 個人 | 備考 |
|-----|-------------|:----:|:------:|:----:|------|
| 1 | QR会員証 | ○ | ○ | ○ | |
| 2 | 会費・決済 | ○ | ○ | - | |
| 3 | 予約管理 | - | ○（枠管理） | ○（予約のみ） | チーム: 予約枠の作成・管理 / 個人: 予約・キャンセル・履歴閲覧 |
| 4 | サービス履歴 | - | ○ | ○ | チーム: 記録管理 / 個人: 自分の履歴閲覧 |
| 5 | 備品管理 | ○ | ○ | - | |
| 6 | ギャラリー | ○ | ○ | - | |

※ SYSTEM_ADMINはシステム管理画面から各レベルへのモジュール適用ON/OFFを調整可能

### テンプレート × 推奨選択式モジュール対応表（プリセット）

テンプレート選択時に、以下の選択式モジュールが自動で有効化される。管理者が後からON/OFF変更可能。

| 選択式モジュール | スポーツ | 整骨院 | 学校 | 会社 | 飲食店 | 美容室 | ジム | サークル | 町内会 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| QR会員証 | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| 会費・決済 | ○ | ○ | ○ | ○ | - | - | ○ | ○ | ○ |
| 予約管理 | - | ○ | - | - | ○ | ○ | ○ | - | - |
| サービス履歴 | - | ○ | - | - | - | ○ | ○ | - | - |
| 備品管理 | ○ | ○ | ○ | ○ | ○ | ○ | ○ | - | ○ |
| ギャラリー | ○ | - | ○ | ○ | ○ | ○ | - | ○ | ○ |

※ デフォルト機能（20個）は全テンプレートで常時有効のため表に含まない

---

## 主要機能

### デフォルト機能詳細

#### A. ダッシュボード（ログイン後のホーム画面）

組織・チーム・個人それぞれにダッシュボードを持ち、全ウィジェットの表示/非表示をトグルスイッチで自由にカスタマイズ可能。

**個人ダッシュボード:**
- お知らせ欄（重要度付き通知）、直近イベント + 出欠状況、自分の投稿一覧、未読スレッド、最近のアクティビティ
- 所属するチーム/組織ごとのパフォーマンスサマリー表示
- 個人カレンダー搭載（**Googleカレンダーとの同期**対応）
- 個人TODOリスト（期限設定、優先度、完了チェック）

**チームダッシュボード:**
- チーム全体のお知らせ、直近イベント、メンバー出欠状況一覧
- チームTODO（担当者の割り振り、期限設定、優先度、進捗ステータス管理）
- チーム活動サマリー、最新投稿、未読スレッド数

**組織ダッシュボード:**
- 傘下チーム一覧と各チームの活動状況
- 組織TODO（担当者・担当チームの割り振り、期限設定、優先度、進捗ステータス管理）
- 組織全体のお知らせ、統計サマリー

**共通:**
- 全ウィジェットはトグルスイッチで表示/非表示を切り替え可能
- ウィジェットの追加・並び替えによるレイアウトカスタマイズ

#### B. TODO管理
- 個人TODOリスト（期限設定、優先度、完了チェック）
- チームTODO（担当者の割り振り、期限設定、優先度、進捗ステータス管理）
- 組織TODO（担当者・担当チームの割り振り）

#### C. タイムライン・コミュニケーション
- X（旧Twitter）風UIのチーム内限定投稿・交流
- LINE連携による自動通知（日程更新、出欠督促）
- アカウントごとのポップアップ通知（WebSocket）

#### D. コンテンツ管理 (CMS)
- **ブログ**: 「外部公開用」と「メンバー限定用」の出し分け
- **お知らせ**: 重要度付き通知配信
- **広告枠**: Amazonアフィリエイト広告の埋め込み
- **スポンサー**: バナー表示（GOLD/SILVER/BRONZE ティア）

#### E. チャット
- **階層化スレッド**: 特定のコメントに対して返答を階層化（Slack/Teamsスタイル）。情報の埋没を防止
- **絵文字リアクション**: メッセージへの絵文字のみの返答（Slack/Teams風）
- **グループ管理**: 複数チーム間、またはチーム代表者・管理者限定のチャットルーム作成
- **チーム間メッセージング**: 組織を横断したコミュニケーション

#### F. プッシュ通知
- 予約リマインド（予約時間が近づいたら自動通知）
- 出欠督促、お知らせ配信
- LINE連携による自動通知
- WebSocketによるリアルタイムポップアップ通知
- 重要度に応じた通知チャネル自動選択

#### G. アンケート・投票
- カスタム設問作成（単一選択・複数選択・自由記述等）
- 匿名/記名の切替
- 集計結果のグラフ表示
- 回答期限の設定

#### H. ファイル共有
- ドキュメント・資料の共有ストレージ
- フォルダ管理（階層構造）
- アクセス権限設定（チーム内/グループ内/個別指定）

#### I. 検索
- チーム横断のグローバル検索（投稿、スレッド、ファイル、メンバー等）
- フィルタリング（日付、カテゴリ、投稿者等）
- 検索結果のハイライト表示

#### J. 通報・モデレーション
- 不適切コンテンツの通報機能（投稿、チャット、マッチング等）
- 通報内容のレビュー・対応フロー（SYSTEM_ADMIN / ADMIN）
- ユーザーBAN・コンテンツ削除・警告の段階的対応

#### K. メンション
- タイムライン / チャット / 掲示板での `@ユーザー名` によるメンション通知
- メンションされた投稿の一覧表示

#### L. 監査ログ
- 管理者操作の履歴記録（誰がいつ何を変更したか）
- ロール変更、メンバー追加/削除、設定変更等を自動記録
- SYSTEM_ADMINによる監査ログ閲覧・検索

#### M. データエクスポート
- チーム解散時や移行時にデータをまとめて出力（CSV / JSON）
- メンバー一覧、活動記録、スケジュール等の一括エクスポート

#### N. 多言語対応 (i18n)
- 初期対応: 日本語 / 英語
- メッセージ・ラベルの外部化（Spring MessageSource + フロントエンドi18nプラグイン）
- 将来の言語追加に対応可能な設計

#### O. スケジュール・出欠管理
- カレンダー表示（イベント日程）
- **Googleカレンダーとの双方向同期**
- ボタン一つで出欠回答（出席・欠席・保留）、管理者による集計・CSV出力
- カレンダー作成時の出欠機能ON/OFF、締切日時設定

#### P. 活動記録
- 活動内容の記録（スコア、参加者、評価等）
- カスタムフィールドで業種に応じた記録項目を自由定義
- 統計・集計機能

#### Q. 掲示板
- **カテゴリ別**: 目的ごとにカテゴリを自由作成（連絡事項、相談、雑談等）
- **ネスト返信（ツリー構造）**: 特定の発言に返信すると、その下に深くなっていく（LINEのように流れない）
- **重要度5段階**: CRITICAL → IMPORTANT → WARNING → INFO → LOW
  - 重要度に応じてお知らせ欄・LINE・ポップアップへ自動通知
- **既読機能（投稿時に選択可）**:
  - 数字のみ: 「既読 15」のように人数表示
  - 閲覧者表示: アイコンが並び、クリックでアカウント名表示
- ピン留め・ロック機能

#### R. マッチング・対外交流
- 地域やレベルに応じた対戦相手・交流先の募集・応募
- マッチング成立時、**双方のスケジュールへ自動反映**
- **NGチーム設定**: トラブル防止のため、特定の相手をブロック・非表示にする機能

#### S. パフォーマンス管理
- カスタムデータ記録（得点、出場時間、走行距離等を自由に定義）
- チームで記録されたデータが個人の専用ダッシュボードへ自動反映
- 個人・チーム統計ダッシュボード

#### T. メンバー紹介
- **メインページ** + 月/年ごとの詳細ページ（階層構造）
- メインページから各詳細ページへ遷移可能
- **メンバー一覧ページ**（年度ごと作成）: 画像、名前、一言、拡張フィールド対応
- 管理者が「作成ボタン」でプロフィール項目を自由に追加可能

### 選択式モジュール詳細

#### U. QR会員証
- メンバーごとに一意のIDを発行し、QRコードを生成
- スマホ画面での表示に最適化
- 来店・受付時のスキャンによる本人確認

#### V. 会費・決済
- 月会費・年会費・都度払いの設定
- 集金管理・支払い状況の一覧表示
- 組織やチームに対する会費の支払い・集金機能

#### W. 予約管理
- **チームアカウント（枠管理）**: 時間枠の作成・編集・削除、スタッフ別の予約枠管理、予約状況の一覧管理
- **個人アカウント（予約のみ）**: 予約の申込・キャンセル、予約履歴の閲覧
- 予約時間が近づいたらプッシュ通知でリマインド

#### X. サービス履歴
- 施術記録・来店記録・対応履歴等の汎用記録システム
- チームごとに保存したい項目を自由に設定（カスタムフィールド対応）
- メンバー個人のダッシュボードへ自動反映

#### Y. 備品管理
- 備品・在庫の管理 +「誰が持っているか」のステータス管理

#### Z. ギャラリー
- メンバーのみが閲覧・アップロードできる写真アルバム
- アルバム管理・閲覧権限設定

### 管理者ダッシュボード

#### AA. 管理者ダッシュボード
- 全管理機能を `/admin` 配下に集約
- ユーザー管理、ロール・パーミッション管理、モジュール設定、スケジュール管理、ブログ管理、掲示板カテゴリ管理、備品管理、メンバー紹介管理、広告・スポンサー管理、予約管理設定、Googleカレンダー設定、LINE設定、SNS設定
- ロール・パーミッションの動的管理（チェックボックスで権限ON/OFF）
- テンプレート・モジュールのON/OFF管理

#### AB. システム管理者ダッシュボード
- プラットフォーム全体の管理を `/system-admin` 配下に集約
- 全組織・チームの一覧・管理
- 定型テンプレート・モジュールの作成・編集・管理
- **モジュールのレベル別適用管理**: 各モジュール（デフォルト・選択式）を組織/チーム/個人のどのレベルで利用可能にするかをON/OFFで制御
- ユーザーBAN・通報管理
- 監査ログ閲覧
- システム設定・メンテナンス

### 外部連携

#### AC. 外部連携
- LINE Messaging API（通知、アカウント連携）
- Googleカレンダー（双方向同期）
- Instagram / X API（SNSフィードキャッシュ）
- Amazonアソシエイト

---

## DB設計

### 認証・権限 (10テーブル)
`users`, `roles`, `user_roles`, `permissions`, `role_permissions`, `refresh_tokens`, `system_admins`, `two_factor_auth`, `oauth_accounts`, `password_reset_tokens`

### 組織・マルチ所属 (4テーブル)
`organizations`, `organization_members`, `team_memberships`, `invitation_links`

### グループ階層 (3テーブル)
`groups`, `group_members`, `group_hierarchy`

### テンプレート・モジュール (6テーブル)
`team_templates`, `template_modules`, `template_fields`, `module_definitions`, `module_field_definitions`, `module_level_availability`

### プラン・課金 (3テーブル)
`subscription_plans`, `team_subscriptions`, `subscription_invoices`

### TODO管理 (3テーブル)
`todos`, `todo_assignees`, `todo_comments`

### ダッシュボード設定 (1テーブル)
`dashboard_widget_settings`

### QR会員証 (1テーブル)
`member_cards`

### タイムライン・通知 (4テーブル)
`timeline_posts`, `timeline_post_attachments`, `timeline_post_reactions`, `notifications`

### チャット (4テーブル)
`chat_channels`, `chat_messages`, `chat_channel_members`, `chat_message_reactions`

### スケジュール・出欠 (4テーブル)
`schedule_events`, `attendance_responses`, `event_surveys`, `event_survey_responses`

### 予約管理 (3テーブル)
`reservation_slots`, `reservations`, `reservation_reminders`

### サービス履歴 (2テーブル)
`service_records`, `service_record_fields`

### コンテンツ管理 (9テーブル)
`blog_posts`, `blog_tags`, `blog_post_tags`, `activity_results`, `activity_participants`, `bulletin_categories`, `bulletin_threads`, `bulletin_replies`, `bulletin_read_status`

### ギャラリー (2テーブル)
`photo_albums`, `photos`

### アンケート・投票 (4テーブル)
`surveys`, `survey_questions`, `survey_options`, `survey_responses`

### ファイル共有 (3テーブル)
`shared_files`, `shared_folders`, `file_permissions`

### 運営ツール (2テーブル)
`equipment_items`, `equipment_assignments`

### メンバー紹介 (4テーブル)
`team_pages`, `team_page_sections`, `member_profiles`, `member_profile_fields`

### マッチング (4テーブル)
`match_requests`, `match_proposals`, `match_reviews`, `ng_teams`

### パフォーマンス管理 (2テーブル)
`performance_metrics`, `performance_records`

### 決済・会費 (3テーブル)
`payment_plans`, `payment_transactions`, `membership_fees`

### 通報・モデレーション (2テーブル)
`reports`, `moderation_actions`

### 監査ログ (1テーブル)
`audit_logs`

### 外部連携・広告 (5テーブル)
`line_integration_config`, `google_calendar_sync`, `sns_feed_cache`, `ad_slots`, `sponsors`

---

## API設計

全APIプレフィックス: `/api/v1`

### 認証
| Method | Path | 説明 |
|--------|------|------|
| POST | `/auth/register` | ユーザー登録 |
| POST | `/auth/login` | ログイン |
| POST | `/auth/refresh` | トークンリフレッシュ |
| POST | `/auth/logout` | ログアウト |
| GET | `/auth/me` | プロフィール取得 |
| POST | `/auth/oauth/{provider}` | OAuth2ソーシャルログイン（Google/LINE/Apple） |
| GET | `/auth/oauth/{provider}/callback` | OAuthコールバック |
| POST | `/auth/password-reset/request` | パスワードリセット要求 |
| POST | `/auth/password-reset/confirm` | パスワードリセット実行 |
| POST | `/auth/2fa/setup` | 2FA設定 |
| POST | `/auth/2fa/verify` | 2FA検証 |
| DELETE | `/auth/2fa` | 2FA無効化 |
| POST | `/auth/deactivate` | アカウント退会申請 |

### プラン・課金
`GET /plans`, `GET /teams/{id}/subscription`, `POST /teams/{id}/subscription`, `PUT /teams/{id}/subscription`, `GET /teams/{id}/subscription/invoices`

### 組織管理
`CRUD /organizations`, `GET /organizations/{id}/teams`, `POST /organizations/{id}/invite`, `POST /invitations/{token}/accept`, `GET /users/{id}/teams`, `GET /users/{id}/organizations`

### グループ管理
`CRUD /groups`, `GET /groups/{id}/members`, `POST /groups/{id}/members`, `DELETE /groups/{id}/members/{userId}`, `GET /organizations/{id}/groups`

### テンプレート・モジュール
`GET /templates`, `GET /templates/{id}`, `GET /templates/{id}/modules`, `PUT /templates/{id}/modules`

### テンプレート管理（SYSTEM_ADMIN専用）
`POST /system-admin/templates`, `PUT /system-admin/templates/{id}`, `DELETE /system-admin/templates/{id}`, `POST /system-admin/modules`, `PUT /system-admin/modules/{id}`, `DELETE /system-admin/modules/{id}`, `GET /system-admin/modules/level-availability`, `PUT /system-admin/modules/{id}/level-availability`

### QR会員証
`GET /members/{id}/card`, `POST /members/{id}/card/generate`, `GET /members/card/verify/{code}`

### マイダッシュボード
| Method | Path | 説明 |
|--------|------|------|
| GET | `/dashboard` | ダッシュボード一括取得 |
| GET | `/dashboard/notices` | お知らせ欄 |
| GET | `/dashboard/my-posts` | 自分の投稿一覧 |
| GET | `/dashboard/upcoming-events` | 直近イベント + 出欠状況 |
| GET | `/dashboard/unread-threads` | 未読スレッド |
| GET | `/dashboard/activity` | 最近のアクティビティ |
| PUT | `/dashboard/widgets` | ウィジェット表示設定 |
| GET | `/dashboard/team/{id}` | チームダッシュボード取得 |
| GET | `/dashboard/organization/{id}` | 組織ダッシュボード取得 |

### TODO管理
`GET/POST /todos`, `GET/PUT/DELETE /todos/{id}`, `PATCH /todos/{id}/status`, `POST /todos/{id}/assignees`, `DELETE /todos/{id}/assignees/{userId}`, `POST /todos/{id}/comments`, `GET /todos/my`, `GET /teams/{id}/todos`, `GET /organizations/{id}/todos`

### タイムライン
`GET/POST /timeline`, `GET/PUT/DELETE /timeline/{id}`, `POST /timeline/{id}/replies`, `POST/DELETE /timeline/{id}/reactions`, `PATCH /timeline/{id}/pin`

### チャット
`CRUD /chat/channels`, `GET /chat/channels/{id}/messages`, `POST /chat/channels/{id}/messages`, `PUT/DELETE /chat/messages/{id}`, `POST /chat/messages/{id}/reactions`, `DELETE /chat/messages/{id}/reactions/{emoji}`, `WS /ws/chat`

### スケジュール・出欠
`GET/POST /schedules`, `GET/PUT/DELETE /schedules/{id}`, `POST /schedules/{id}/attendance`, `GET /schedules/{id}/attendance`, `GET /schedules/{id}/attendance/export`, `GET /schedules/calendar`

### 予約管理
`GET/POST /reservations`, `GET/PUT/DELETE /reservations/{id}`, `GET /reservation-slots`, `POST /reservation-slots`, `PUT/DELETE /reservation-slots/{id}`, `GET /reservations/upcoming`

### サービス履歴
`GET/POST /service-records`, `GET/PUT/DELETE /service-records/{id}`, `GET /members/{id}/service-history`, `CRUD /service-record-fields`

### ブログ
`GET/POST /blog/posts`, `GET /blog/posts/{slug}`, `PUT/DELETE /blog/posts/{id}`, `PATCH /blog/posts/{id}/publish`

### 活動記録
`GET/POST /activities`, `GET/PUT/DELETE /activities/{id}`, `POST/DELETE /activities/{id}/participants`

### ギャラリー
`GET/POST /gallery/albums`, `GET/PUT/DELETE /gallery/albums/{id}`, `POST /gallery/albums/{id}/photos`, `DELETE /gallery/photos/{id}`

### 備品管理
`GET/POST /equipment`, `GET/PUT/DELETE /equipment/{id}`, `POST /equipment/{id}/assign`, `PATCH /equipment/{id}/return`

### 掲示板
`GET/POST /bulletin/categories`, `PUT/DELETE /bulletin/categories/{id}`, `GET/POST /bulletin/threads`, `GET/PUT/DELETE /bulletin/threads/{id}`, `PATCH /bulletin/threads/{id}/priority`, `POST /bulletin/threads/{id}/read`, `GET /bulletin/threads/{id}/readers`, `PATCH /bulletin/threads/{id}/pin`, `PATCH /bulletin/threads/{id}/lock`, `POST /bulletin/threads/{id}/replies`, `POST /bulletin/replies/{id}/replies`, `PUT/DELETE /bulletin/replies/{id}`

### アンケート・投票
`GET/POST /surveys`, `GET/PUT/DELETE /surveys/{id}`, `POST /surveys/{id}/responses`, `GET /surveys/{id}/results`

### ファイル共有
`GET/POST /files`, `GET/DELETE /files/{id}`, `GET /files/{id}/download`, `CRUD /folders`, `PUT /files/{id}/permissions`

### マッチング
`GET/POST /matching/requests`, `GET/PUT/DELETE /matching/requests/{id}`, `POST /matching/requests/{id}/propose`, `PATCH /matching/proposals/{id}/accept`, `PATCH /matching/proposals/{id}/reject`, `POST /matching/reviews`, `CRUD /matching/ng-teams`

### パフォーマンス
`CRUD /performance/metrics`, `POST /performance/records`, `GET /performance/stats`, `GET /members/{id}/performance`

### 決済・会費
`POST /payments/plans`, `GET /payments/plans`, `POST /payments/charge`, `GET /payments/history`, `POST /membership-fees/collect`, `GET /membership-fees/status`

### 検索
`GET /search?q={query}&type={type}&scope={scope}`, `GET /search/suggestions`

### 通報・モデレーション
`POST /reports`, `GET /admin/reports`, `PATCH /admin/reports/{id}/resolve`, `POST /admin/moderation/ban/{userId}`, `DELETE /admin/moderation/ban/{userId}`

### 監査ログ
`GET /admin/audit-logs`, `GET /system-admin/audit-logs`

### データエクスポート
`POST /export/team/{id}`, `GET /export/status/{jobId}`, `GET /export/download/{jobId}`

### 通知
`GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all`, `WS /ws`

### メンバー紹介
`GET/POST /team/pages`, `GET/PUT/DELETE /team/pages/{id}`, `PATCH /team/pages/{id}/publish`, `GET/POST /team/pages/{id}/sections`, `PUT/DELETE /team/sections/{id}`, `GET/POST /team/members`, `GET/PUT/DELETE /team/members/{id}`, `POST /team/members/bulk`, `GET/POST /team/member-fields`, `PUT/DELETE /team/member-fields/{id}`

### 管理者ダッシュボード
`GET /admin/dashboard`, `/admin/users/**`, `/admin/roles/**`, `/admin/permissions/**`, `/admin/modules/**`, `/admin/schedules/**`, `/admin/blog/**`, `/admin/bulletin/categories/**`, `/admin/equipment/**`, `/admin/team/**`, `/admin/ads/**`, `/admin/sponsors/**`, `/admin/reservations/**`, `/admin/google-calendar/**`, `/admin/line/**`, `/admin/sns/**`

### システム管理者
`GET /system-admin/dashboard`, `/system-admin/organizations/**`, `/system-admin/teams/**`, `/system-admin/users/**`, `/system-admin/templates/**`, `/system-admin/modules/**`, `/system-admin/reports/**`, `/system-admin/audit-logs/**`, `/system-admin/settings/**`

### Googleカレンダー連携
`GET /admin/google-calendar/status`, `POST /admin/google-calendar/connect`, `GET /admin/google-calendar/callback`, `DELETE /admin/google-calendar/disconnect`, `POST /admin/google-calendar/sync`, `PUT /admin/google-calendar/settings`, `GET /admin/google-calendar/preview`

### LINE連携
`POST /line/webhook`, `POST/DELETE /line/link`

### 広告・スポンサー
`GET /ads`, `GET /sponsors`, `CRUD /admin/ads`, `CRUD /admin/sponsors`

---

## 実装フェーズ

| Phase | 内容 | 成果物 |
|-------|------|--------|
| **1** | プロジェクト基盤 + 3層アカウント構造 + 認証 + ロール・パーミッション + テンプレート・モジュール基盤 + 全DB設計(Flyway) | Auth API + 組織API + テンプレートAPI + Swagger UI |
| **2** | QR会員証 + マイダッシュボード + ダッシュボードUXカスタマイズ | QR API + ダッシュボードAPI |
| **3** | スケジュール・出欠管理 + 予約管理 + Googleカレンダー同期 | カレンダー + 出欠API + 予約API + Google連携 |
| **4** | タイムライン + チャット（階層スレッド・絵文字リアクション）+ プッシュ通知 | X風フィード + チャットAPI + WebSocket通知 |
| **5** | 掲示板 + アンケート・投票 + ファイル共有 | 掲示板API + アンケートAPI + ファイルAPI |
| **6** | CMS（ブログ・活動記録）+ メンバー紹介 + ギャラリー | CMS API群 |
| **7** | サービス履歴 + パフォーマンス管理 + 備品管理 | サービス履歴API + パフォーマンスAPI + 備品API |
| **8** | マッチング・対外交流 + 会費・決済 | マッチングAPI + 決済API |
| **9** | LINE連携・SNS・広告・スポンサー | 外部連携API群 |
| **10** | 管理者ダッシュボード + システム管理者ダッシュボード + 通報・モデレーション + 監査ログ | 管理画面 + 運用ツール |
| **11** | ポリッシュ・テスト・Docker化 | 本番デプロイ可能 |

---

## インフラ構成

- **初期**: AWS EC2 + MySQL + Redis
- **将来**: ロリポップ等のレンタルサーバーへの移行も考慮
- ファイルストレージ: S3互換オブジェクトストレージ（Local / S3 切替可能な抽象化設計）
- Spring Boot fat JAR（Java 21 があればどこでも動作）
- 全シークレットは環境変数管理
- WAR パッケージングへの変更も容易
- 決済機能導入時にはセキュリティ要件の追加検討

---

## アーキテクチャ・開発ガイドライン

### ストレージ戦略
- 画像および大容量ファイルは外部オブジェクトストレージ（S3互換）へ保存
- クライアントからのアップロードは、バックエンドが発行する**署名付きURL（Pre-signed URL）**を利用してストレージへ直接送信する設計
- サーバーを経由しないため、アップロード時のサーバー負荷を軽減

### スケーラビリティと通信
- WebSocket (STOMP) のメッセージブローカーとして **Redis** を利用（将来的な複数台構成を想定）
- 認証情報の無効化（ログアウト・トークンブラックリスト）管理にも Redis を活用
- Java 21 の **Virtual Threads** (Project Loom) を活用し、大量の同時接続を効率的に処理

### 開発・テスト環境
- データベースを用いたテストには **Testcontainers** を使用し、Dockerコンテナ上で MySQL 8.0 を立ち上げて実行
- Flyway のマイグレーションファイルは `src/main/resources/db/migration` にバージョン管理して配置
- CORS設定は開発環境と本番環境で適切に切り替えられるようプロファイル管理

### フロントエンド・バックエンド連携
- APIの型定義は **Springdoc OpenAPI** が生成する `swagger.json` を基に、フロントエンド（Nuxt 3）側で **OpenAPI Generator** 等を用いて型定義を自動生成
- バックエンドのAPI変更がフロントエンドの型に即座に反映されるワークフローを構築

### パフォーマンス最適化
- Nuxt 3 の SSR を活用し、チーム紹介などの公開ページにおける **LCP（Largest Contentful Paint）を 2.5s 以内** に抑える
- 画像は適切なフォーマット（WebP/AVIF）と遅延読み込みで最適化
- API レスポンスの適切なキャッシュ戦略（Redis + HTTP キャッシュヘッダー）

---

## Git運用ルール

- ファイルを修正するたびに `git commit` を実行する
- コミットメッセージには **変更内容の要約を日本語で記載** する
- フォーマット例: `機能追加: ユーザー認証APIの実装`、`修正: 出欠回答のバリデーション追加`

## Instructions
- 開発にあたっては、必ずルートの `.claudecode.md` および `BACKEND_CODING_CONVENTION.md`,`FRONTEND_CODING_CONVENTION.md` に記載された規約を最優先で遵守すること。
- ディレクトリ構成は機能別（Feature-based）パッケージングを採用すること。

## 開発フローの制約（必須）
新しい機能の実装に取り掛かる際は、勝手にコードを書き始めず、必ず以下の手順を踏むこと：

1. **要件の確認とすり合わせ**: 実装する機能の詳細と、必要なテーブル定義（DDL）の案を提示し、ユーザーの承認を得ること。
2. **設計案の提示**: 機能パッケージ内のクラス構成や、APIのインターフェース案（エンドポイント名など）を提示すること。
3. **承認後の実装**: ユーザーから「Goサイン」が出た後に、初めて実際のコーディングを開始すること。