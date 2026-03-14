# Mannschaft — 汎用組織管理プラットフォーム（バックエンド）

あらゆる組織・チーム・個人をシームレスに管理するWebアプリケーション。

---

## 目次

- [プロジェクト基本情報](#プロジェクト基本情報)
- [想定ユースケース](#想定ユースケース)
- [技術スタック](#技術スタック)
- [アプリケーション形態](#アプリケーション形態)
- [アカウント構造](#アカウント構造)
  - [3層構造（3形態対応）](#3層構造3形態対応)
  - [ロール・パーミッション](#ロールパーミッション)
  - [セキュリティ・認証](#セキュリティ認証)
- [モジュール式テンプレートシステム](#モジュール式テンプレートシステム)
  - [料金プラン](#料金プラン)
  - [デフォルト機能一覧](#デフォルト機能全チーム常時有効選択リスト非表示)
  - [選択式モジュール一覧](#選択式モジュールカタログから選択課金対象)
  - [レベル別モジュール適用](#レベル別モジュール適用)
  - [テンプレート × 推奨モジュール対応表](#テンプレート--推奨選択式モジュール対応表プリセット)
- [主要機能](#主要機能)
  - [デフォルト機能詳細](#デフォルト機能詳細)
  - [選択式モジュール詳細](#選択式モジュール詳細)
  - [管理者ダッシュボード](#管理者ダッシュボード)
  - [外部連携](#外部連携)
- [インフラ・パフォーマンス設計](#インフラパフォーマンス設計)
- [DB設計](#db設計)
- [API設計](#api設計)
- [実装フェーズ](#実装フェーズ)
- [インフラ構成](#インフラ構成)
- [アーキテクチャ・開発ガイドライン](#アーキテクチャ開発ガイドライン)
- [Git運用ルール](#git運用ルール)

---

## プロジェクト基本情報

| 項目 | 値 |
|------|-----|
| 正式名称 | Mannschaft |
| リポジトリ名 | mannschaft |
| パッケージ名 | com.mannschaft.app |

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
| キャッシュ/セッション | Redis (トークン無効化・タイムラインフィードキャッシュ) |
| ストレージ | AWS S3 + CloudFront (CDN) |
| 画像処理 | AWS Lambda (WebP変換・サムネイル自動生成) |
| テスト | Testcontainers (MySQL 8.0) |
| その他 | Lombok, Virtual Threads (Project Loom) |

### フロントエンド（別リポジトリ）

| 区分 | 技術 |
|------|------|
| フレームワーク | Nuxt.js 3 (Vue 3 ベース) |
| レンダリング | SSR + SPA ハイブリッド |
| 状態管理 | Pinia |
| スタイリング | Tailwind CSS |
| フォームバリデーション | Zod + VeeValidate |
| データ可視化 | Chart.js (vue-chartjs) |
| テスト | Vitest + Vue Test Utils |
| SSR対象 | SEOが必要な公開ページ（ブログ、チーム紹介、活動記録等） |
| SPA対象 | ログイン後のダッシュボード、チャット等のインタラクティブ画面 |

## アプリケーション形態

- **SSR/SPA ハイブリッド**: Nuxt.js 3 によるサーバーサイドレンダリングとSPAの両立。バックエンドはJSON APIのみ提供
- **スマホ対応**: PWA対応、画像アップロード最適化、チャンクアップロード、WebSocketリアルタイム通知
- **データ可視化**: 数値・統計データを表示する機能はチャート・グラフ（Chart.js / vue-chartjs）で可視化する。どの機能に導入するかは各機能の個別設計フェーズで確定する
- **フロントエンドは別リポジトリ**で管理

---

## アカウント構造

### 3層構造（3形態対応）

本プロジェクトは「組織」「チーム」「個人」のいずれの単位でも独立して登録・利用できる柔軟な構造を持つ。すべてが組織配下である必要はない。

| 層 | 説明 | 例 |
|----|------|----|
| **個人アカウント** | ユーザー単位。実名（姓・名）＋愛称1（表示用ニックネーム）＋愛称2（nullable）を登録。複数チーム/組織にマルチ所属可能。QR会員証を保有。組織・チームに属さない個人利用も可能 | 田中太郎 |
| **チームアカウント** | チーム/店舗/教室等の運用単位。正式名称＋愛称1＋愛称2（両方 nullable）を登録。都道府県・市区町村を設定し地域検索可能。テンプレート選択で機能モジュールを決定。**組織に属さない独立チームも可能** | FCマンシャフト / 田中整骨院 / 3年B組 |
| **組織アカウント** | 複数チームを束ねる上位組織。組織内に別の組織を登録する階層構造も可能 | 〇〇サッカー協会 / 〇〇高等学校 / 〇〇株式会社 |

### グループ階層

組織内にサブグループを自由に作成可能。グループ単位での連絡・スケジュール・チャット配信に対応。

例: 学校（組織）→ 学年（グループ）→ クラス（サブグループ）→ 同窓会（派生グループ）

### 紐付けと所属

- **招待URL・QRコード**: チーム/組織が発行した招待URL またはQRコードから個人がスムーズにメンバー加入。QRコードには有効期限（1日/7日/30日/90日/無期限）と使用回数上限（nullable）を設定可能。期限切れ・上限到達・手動無効化で即座に失効する
- **マルチ所属**: 1つの個人アカウントで複数のチームや組織に同時に所属可能
- **サポーター枠**: 招待不要で、外部から特定のチームを支援・フォローできる独立した枠組み

### ロール・パーミッション

| ロール | スコープ | アクセス範囲 |
|--------|----------|-------------|
| システム管理者 (SYSTEM_ADMIN) | プラットフォーム全体 | 全組織・チームの管理、テンプレート管理、システム設定、ユーザーBAN |
| 管理者 (ADMIN) | チーム/組織内 | 全機能（メンバー管理、備品管理、広告設定、投稿編集、ロール管理、モジュール設定） |
| 副管理者 (DEPUTY_ADMIN) | チーム/組織内 | ADMIN が許可した操作のみ。操作権限はパーミッションを個別選択して構成した**権限グループ**単位で管理。複数の権限グループをテンプレートとして作成でき、ユーザーごとに割り当て可能。コンテンツ削除権限はデフォルト非付与（明示的許可が必要） |
| メンバー (MEMBER) | チーム/組織内 | プラットフォームデフォルトの操作（ADMIN が範囲を制限可能） |
| サポーター (SUPPORTER) | チーム/組織内 | サポーター限定コンテンツ、活動速報 |
| ゲスト (GUEST) | チーム/組織内 | スケジュール・活動記録、公式ブログ、SNSフィード |

※ Phase 1 では上記の固定ロールのみで実装する。ただし、DB設計は `roles` + `permissions` + 中間テーブル（RBAC）方式とし、将来的なカスタムロール追加に対応可能な構造にする
※ Phase 1 の管理者画面では「ユーザーへのロール割り当て」のみ、ロール自体の追加・編集は将来対応
※ グループ単位でもロール設定可能
※ DEPUTY_ADMIN・MEMBER の権限は 3 層で管理する: ① SYSTEM_ADMIN がロールごとの権限の上限（天井）を設定 → ② 各チーム/組織の ADMIN がその天井内でパーミッションを個別選択して権限グループを構成 → ③ ユーザーに権限グループを割り当て
※ **DEPUTY_ADMIN 権限グループ**: ADMIN は操作権限を個別選択した名前付きグループ（`permission_groups`）を複数作成・テンプレートとして保存できる。「Aさんにはグループ1（受付・安否確認）、B・CさんにはグループA2（スケジュール・ファイル管理）」のような運用が可能
※ **コンテンツ削除のデフォルト非付与**: 投稿・チャット・掲示板スレッド等の他者コンテンツ削除権限は DEPUTY_ADMIN のデフォルトパーミッションに含めない。コンテンツの責任はその組織・チームに帰属するため、削除は ADMIN が担う。DEPUTY_ADMIN への付与が必要な場合は権限グループで明示的に設定する
※ `user_roles` は `team_id` / `organization_id` のスコープカラムを持ち、「チームAでは DEPUTY_ADMIN・チームBでは MEMBER」のようなマルチ所属に対応

### セキュリティ・認証

> 📄 詳細設計: [docs/features/F01_auth.md](docs/features/F01_auth.md) | [docs/features/F03_org_team_member_role.md](docs/features/F03_org_team_member_role.md)

- **2要素認証 (2FA)**: TOTP（Google Authenticator等）対応。SYSTEM_ADMIN・ADMINには必須化
- **OAuth2ソーシャルログイン**: Google / LINE / Apple によるワンクリック登録・ログイン
- **パスワードリセット**: メールによるリセットフロー。トークン有効期限は **30分**
- **レート制限・ブルートフォース対策**: ログイン試行を IP + アカウント単位で Redis カウンター管理。5回失敗でアカウントを **30分** ロック。パスワードリセット・2FA検証エンドポイントにも同様のレート制限を適用する
- **アカウント凍結・退会フロー**: 管理者によるアカウント凍結、ユーザー自身の退会申請。退会申請後30日間は論理削除（猶予期間）、その後個人情報を物理削除。決済履歴は税法に基づき7年間保持。詳細は `.claudecode.md` §8「データ保持・削除規約」を参照
- **非アクティブアーカイブ**: 一定期間ログインがないユーザー・チーム・組織を段階的にアーカイブする。アーカイブ前にメール通知を送り、ログインで即時解除可能

  | 対象 | アーカイブトリガー | 根拠 |
  |------|-----------------|------|
  | 個人ユーザー | 本人の最終ログインから **6ヶ月** 経過 | 個人の責任範囲 |
  | チーム・組織 | **全メンバーの最終ログインのうち最新**が **12ヶ月** 経過 | 季節活動・年1回のイベント系組織を考慮。1人でもアクティブなメンバーがいれば維持 |

  | アーカイブ処理 | 内容 | 節約効果 |
  |--------------|------|---------|
  | Redis キャッシュ | フィードキャッシュ・未読カウンター等を全削除 | 大（Redis はメモリが高価） |
  | S3 ファイル | Glacier Instant Retrieval（低価格ティア）へ移行 | 中（ストレージ費用削減） |
  | MySQL データ | 移動しない。`archived_at` タイムスタンプのみ付与。検索・一覧クエリから除外 | 小 |

  ログイン復帰時は `archived_at` をクリアし、S3 復元（Glacier Instant Retrieval は体感遅延なし）と Redis キャッシュの遅延再構築を行う。フロントエンドは「アカウントを復元中...」のローディング表示でカバーする

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

#### 課金方式

| 方式 | 概要 |
|------|------|
| フリープラン | デフォルト機能（28個）＋ 選択式モジュール最大10個まで無料 |
| 個別モジュール課金 | 選択式モジュールを1個単位で有効化。月額または年額サブスクリプション |
| パッケージ課金 | 複数モジュールをセットにしたパッケージを割引価格で購入 |
| 組織数課金 | 組織配下のアクティブなチーム数に応じた月額課金。組織種別（非営利/営利）ごとに無料枠と単価を設定 |

- デフォルト機能はいずれの課金方式でも無料。モジュール数カウント対象外
- 月額・年額の選択制（年額は月額×12に対して割引率を設定可能）
- **全価格・パッケージ構成・割引はSYSTEM_ADMINが管理画面からリアルタイムに設定変更可能**
- 価格変更は翌請求サイクルから適用。既存契約中のチームへは移行猶予期間を設ける

#### 組織数課金

組織（`organizations`）は登録時に **組織種別（`org_type`）** を設定する。種別ごとに無料枠と超過課金単価が異なる。課金対象はアーカイブされていないアクティブなチーム数（`archived_at IS NULL`）を月次バッチで集計する。

| 組織種別 | 無料枠 | 超過課金単価（月額・1チームあたり） |
|---------|-------|-------------------------------|
| 非営利団体 (NONPROFIT) | 20チームまで無料 | 超過分はSYSTEM_ADMINが設定（将来検討） |
| 営利組織 (FORPROFIT) | 5チームまで無料（6チーム目から課金） | 200円 |

- 無料枠・単価はすべて **SYSTEM_ADMINが管理画面からリアルタイムに設定変更可能**
- 課金は翌月請求。チーム数が無料枠以下に戻った場合は翌月から無課金に戻る
- モジュール課金と組織数課金は **独立した請求軸**（合算して1枚の請求書に表示）
- 組織種別は組織作成時に選択。ADMIN が管理画面から自己申告で変更可能（承認不要）。変更は翌月の請求サイクルから反映される

#### 課金タイミング

- **月のどこで契約しても当月は課金対象（日割りなし）**。月15日にモジュールを有効化した場合も、その月の月額料金が全額発生する
- 翌月初日を次の請求サイクル開始日とする
- 解約・無効化した場合も、解約処理を行った月末まで有効。翌月から課金停止
- 全課金軸（モジュール課金・パッケージ課金・ストレージ課金・組織数課金）で統一ルールを適用

#### 割引・クーポン

- **期間限定割引キャンペーン**: 開始日時・終了日時・割引率（%）または割引額（固定）を設定。モジュール全体・特定モジュール・特定パッケージを対象に指定可能
- **クーポンコード**: キャンペーンにコードを紐付け、コード入力者のみが割引を受けられる方式を選択可能。利用上限回数（nullable）も設定可能

#### 消費税

- SYSTEM_ADMINが税名称・税率を設定（例: 消費税 10%）。複数税率の登録に対応できる設計とする
- 請求書・支払い画面では**税抜価格・税込価格を両方表示**する
- `is_included_in_price` フラグで表示価格を税込み統一か税抜き表示かを切り替え可能

※ 課金モデルの実装詳細は `.claudecode.md` §9「サブスクリプション設計指針」を参照

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
| 21 | 回覧板 | 文書の順番/一斉回覧・電子押印・完了管理 |
| 22 | 電子印鑑 | 登録姓を印鑑風に生成・各機能で横断利用 |
| 23 | 緊急安否確認 | 災害・緊急時の一斉安否確認・集計 |
| 24 | アクセス解析 | ページビュー数の日別・月別・累計表示（Chart.js グラフ） |
| 25 | テーマ・外観カスタマイズ | ライト/ダーク切替・背景色5色・期間限定シーズナル壁紙 |
| 26 | ダイレクトメール配信 | 組織/チーム内メンバーへのメール一斉・個別送信（週3回まで無料） |
| 27 | 広告表示 | フリープラン利用中のチーム/組織のページにプラットフォーム広告を自動表示。初期はAmazonアソシエイト。将来はターゲティング広告（組織カテゴリ・地域等）・内部組織ページへのリンク広告に拡張 |
| 28 | コルクボード | チャット・投稿・掲示板・ファイルをカード化してドラッグ＆ドロップで自由配置。重要情報の視覚的ストック・整理。個人/チーム/組織の3スコープ対応 |

### 選択式モジュール（カタログから選択・課金対象）

| No. | モジュール名 | 説明 |
|-----|-------------|------|
| 1 | QR会員証 | メンバーID発行・QRコード生成・スキャン認証 |
| 2 | 会費・決済 | 月会費/年会費/都度払い・集金管理 |
| 3 | 予約管理 | 時間枠予約・スタッフ別枠・リマインド |
| 4 | サービス履歴 | 施術/来店/対応履歴・カスタムフィールド |
| 5 | 備品管理 | 在庫管理・貸出ステータス |
| 6 | ギャラリー | 写真アルバム・アップロード・閲覧権限 |
| 7 | シフト管理 | 週/月シフト作成・希望収集・確定通知 |
| 8 | 議決権行使・委任状 | 総会議案への電子投票・委任状提出・結果集計 |
| 9 | 住民台帳 | 居室情報・居住者管理・物件売買/賃貸掲示（非公開設定対応） |
| 10 | 駐車場区画管理 | 区画登録・個別価格設定・割り当て・空き管理・譲渡希望掲示 |
| 11 | カルテ | 問診票・ビフォーアフター写真・身体チャート・薬剤レシピ・経過グラフ（**有料プラン必須**） |

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
| 21 | 回覧板 | ○ | ○ | - |
| 22 | 電子印鑑 | ○ | ○ | ○ |
| 23 | 緊急安否確認 | ○ | ○ | ○ |
| 24 | アクセス解析 | ○ | ○ | ○ |
| 25 | テーマ・外観カスタマイズ | ○ | ○ | ○ |
| 26 | ダイレクトメール配信 | ○ | ○ | - | 送信は ADMIN/DEPUTY_ADMIN のみ。個人は受信のみ |
| 27 | 広告表示 | ○ | ○ | ○ | フリープランのみ表示。有料プランは非表示。ユーザー自身は表示のみ（広告管理はSYSTEM_ADMINが担う） |
| 28 | コルクボード | ○ | ○ | ○ | 個人: 個人ボードのみ作成・管理可。チーム/組織: 共有ボードはADMINが作成、編集権限はADMINが設定 |

#### 選択式モジュールのレベル別適用

| No. | モジュール名 | 組織 | チーム | 個人 | 備考 |
|-----|-------------|:----:|:------:|:----:|------|
| 1 | QR会員証 | ○ | ○ | ○ | |
| 2 | 会費・決済 | ○ | ○ | - | |
| 3 | 予約管理 | - | ○（枠管理） | ○（予約のみ） | チーム: 予約枠の作成・管理 / 個人: 予約・キャンセル・履歴閲覧 |
| 4 | サービス履歴 | - | ○ | ○ | チーム: 記録管理 / 個人: 自分の履歴閲覧 |
| 5 | 備品管理 | ○ | ○ | - | |
| 6 | ギャラリー | ○ | ○ | - | |
| 7 | シフト管理 | - | ○（作成・管理） | ○（希望提出） | チーム: シフト作成・編集・確定 / 個人: 希望日時の提出・確定シフトの確認 |
| 8 | 議決権行使・委任状 | ○ | ○ | ○（投票・委任） | 組織/チーム: 議案作成・集計 / 個人: 議決権行使・委任状提出 |
| 9 | 住民台帳 | ○ | ○ | ○（自室のみ） | ADMIN/DEPUTY_ADMIN: 全件閲覧・編集 / 個人: 自室・自身の情報のみ閲覧（他居住者は非公開） |
| 10 | 駐車場区画管理 | ○ | ○ | ○（申請・確認） | 管理者: 区画管理・割り当て・価格設定 / 個人: 空き確認・申請・譲渡希望登録 |
| 11 | カルテ | - | ○（作成・管理） | ○（自分のカルテのみ） | チーム: カルテ作成・管理・閲覧 / 個人: 施術者が許可した情報のみ閲覧 |

※ SYSTEM_ADMINはシステム管理画面から各レベルへのモジュール適用ON/OFFを調整可能

### テンプレート × 推奨選択式モジュール対応表（プリセット）

テンプレート選択時に、以下の選択式モジュールが自動で有効化される。管理者が後からON/OFF変更可能。

| 選択式モジュール | スポーツ | 整骨院 | 学校 | 会社 | 飲食店 | 美容室 | ジム | サークル | 町内会 | マンション |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| QR会員証 | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| 会費・決済 | ○ | ○ | ○ | ○ | - | - | ○ | ○ | ○ | ○ |
| 予約管理 | - | ○ | - | - | ○ | ○ | ○ | - | - | ○ |
| サービス履歴 | - | ○ | - | - | - | ○ | ○ | - | - | - |
| 備品管理 | ○ | ○ | ○ | ○ | ○ | ○ | ○ | - | ○ | ○ |
| ギャラリー | ○ | - | ○ | ○ | ○ | ○ | - | ○ | ○ | ○ |
| シフト管理 | - | ○ | - | ○ | ○ | ○ | ○ | - | - | - |
| 議決権行使・委任状 | - | - | - | ○ | - | - | - | ○ | ○ | ○ |
| 住民台帳 | - | - | - | - | - | - | - | - | - | ○ |
| 駐車場区画管理 | - | - | - | ○ | - | - | - | - | ○ | ○ |
| カルテ | - | ○ | - | - | - | ○ | ○ | - | - | - |

※ デフォルト機能（28個）は全テンプレートで常時有効のため表に含まない

---

## 主要機能

### デフォルト機能詳細

#### 1. ダッシュボード（ログイン後のホーム画面）

組織・チーム・個人それぞれにダッシュボードを持ち、全ウィジェットの表示/非表示をトグルスイッチで自由にカスタマイズ可能。

**個人ダッシュボード:**
- お知らせ欄（重要度付き通知）、直近イベント + 出欠状況、自分の投稿一覧、未読スレッド、最近のアクティビティ
- 所属するチーム/組織ごとのパフォーマンスサマリー表示
- 個人カレンダー搭載（**Googleカレンダーとの同期**対応）
- 個人TODOリスト（期限設定、優先度、完了チェック）
- **今月の課金サマリー（将来対応）**: 現時点では個人向け課金なし。将来の個人プレミアム機能追加時にウィジェットを有効化する

**チームダッシュボード:**
- チーム全体のお知らせ、直近イベント、メンバー出欠状況一覧
- チームTODO（担当者の割り振り、期限設定、優先度、進捗ステータス管理）
- チーム活動サマリー、最新投稿、未読スレッド数
- **今月の課金サマリー（ADMIN/DEPUTY_ADMINのみ表示）**:
  - 今月の課金合計（税込）をウィジェット上部に表示
  - 内訳: モジュール課金 / パッケージ課金 / ストレージ課金 をそれぞれ明示
  - 次回請求日と請求履歴ページへのリンクを表示
  - 課金が発生していない場合は「今月の課金はありません」と表示

**組織ダッシュボード:**
- 傘下チーム一覧と各チームの活動状況
- 組織TODO（担当者・担当チームの割り振り、期限設定、優先度、進捗ステータス管理）
- 組織全体のお知らせ、統計サマリー
- **今月の課金サマリー（ADMIN/DEPUTY_ADMINのみ表示）**:
  - 今月の課金合計（税込）をウィジェット上部に表示
  - 内訳: モジュール課金 / パッケージ課金 / ストレージ課金 / 組織数課金（現在のチーム数・無料枠・超過チーム数）をそれぞれ明示
  - 次回請求日と請求履歴ページへのリンクを表示

**共通:**
- 全ウィジェットはトグルスイッチで表示/非表示を切り替え可能
- ウィジェットの追加・並び替えによるレイアウトカスタマイズ

#### 2. TODO管理
- 個人TODOリスト（期限設定、優先度、完了チェック）
- チームTODO（担当者の割り振り、期限設定、優先度、進捗ステータス管理）
- 組織TODO（担当者・担当チームの割り振り）

#### 3. タイムライン・コミュニケーション
- X（旧Twitter）風UIのチーム内限定投稿・交流（クローズドタイムライン）
- プラットフォーム全体のパブリックタイムライン（ソーシャルプロフィールによる匿名投稿 + チーム/組織の公式投稿）
- ソーシャルプロフィール（匿名人格、1ユーザー最大3つ）+ フォロー機能
- LINE連携による自動通知（日程更新、出欠督促）
- アカウントごとのポップアップ通知（WebSocket）

#### 4. コンテンツ管理 (CMS)

> 📄 詳細設計: [docs/features/F06_cms_blog.md](docs/features/F06_cms_blog.md)

- **ブログ**: 「外部公開用」と「メンバー限定用」の出し分け
- **お知らせ**: 重要度付き通知配信
- **広告枠**: Amazonアフィリエイト広告の埋め込み
- **スポンサー**: バナー表示（GOLD/SILVER/BRONZE ティア）

#### 5. チャット
- **階層化スレッド**: 特定のコメントに対して返答を階層化（Slack/Teamsスタイル）。情報の埋没を防止
- **絵文字リアクション**: メッセージへの絵文字のみの返答（Slack/Teams風）
- **グループ管理**: 複数チーム間、またはチーム代表者・管理者限定のチャットルーム作成
- **チーム間メッセージング**: 組織を横断したコミュニケーション

#### 6. プッシュ通知
- 予約リマインド（予約時間が近づいたら自動通知）
- 出欠督促、お知らせ配信
- LINE連携による自動通知
- WebSocketによるリアルタイムポップアップ通知
- 重要度に応じた通知チャネル自動選択
- **通知受信設定**: 個人アカウントが所属する組織・チームごとに通知の受け取り ON/OFF を設定可能

#### 7. アンケート・投票
- カスタム設問作成（単一選択・複数選択・自由記述等）
- 匿名/記名の切替
- 集計結果のグラフ表示
- 回答期限の設定

#### 8. ファイル共有
- ドキュメント・資料の共有ストレージ
- フォルダ管理（階層構造）
- アクセス権限設定（チーム内/グループ内/個別指定）
- **ストレージ容量管理**: チームごとに使用量をリアルタイム表示。無料枠を超えた場合は追加ストレージプランへのアップグレードを促す。無料枠・プラン価格は SYSTEM_ADMIN が管理画面から設定する

#### 9. 検索
- チーム横断のグローバル検索（投稿、スレッド、ファイル、メンバー等）
- フィルタリング（日付、カテゴリ、投稿者等）
- 検索結果のハイライト表示

#### 10. 通報・モデレーション
- 不適切コンテンツの通報機能（投稿、チャット、マッチング等）
- 通報内容のレビュー・対応フロー（SYSTEM_ADMIN / ADMIN）
- ユーザーBAN・コンテンツ削除・警告の段階的対応

#### 11. メンション
- タイムライン / チャット / 掲示板での `@ユーザー名` によるメンション通知
- メンションされた投稿の一覧表示

#### 12. 監査ログ

> 📄 詳細設計: [docs/features/F02_audit_logs.md](docs/features/F02_audit_logs.md)

- 管理者操作の履歴記録（誰がいつ何を変更したか）
- ロール変更、メンバー追加/削除、設定変更等を自動記録
- SYSTEM_ADMINによる監査ログ閲覧・検索

#### 13. データエクスポート
- チーム解散時や移行時にデータをまとめて出力（CSV / JSON）
- メンバー一覧、活動記録、スケジュール等の一括エクスポート

#### 14. 多言語対応 (i18n)
- 初期対応: 日本語 / 英語
- メッセージ・ラベルの外部化（Spring MessageSource + フロントエンドi18nプラグイン）
- 将来の言語追加に対応可能な設計

#### 15. スケジュール・出欠管理

> 📄 詳細設計: [docs/features/F05_schedule_shared.md](docs/features/F05_schedule_shared.md) | [docs/features/F05_schedule_personal.md](docs/features/F05_schedule_personal.md)

- カレンダー表示（イベント日程）
- **Googleカレンダーとの同期**（Phase 3: アプリ→Google 一方向、Phase 4+: 双方向）
- ボタン一つで出欠回答（出席・欠席・保留）、管理者による集計・CSV出力
- カレンダー作成時の出欠機能ON/OFF、締切日時設定

#### 16. 活動記録
- 活動内容の記録（スコア、参加者、評価等）
- カスタムフィールドで業種に応じた記録項目を自由定義
- 統計・集計機能

#### 17. 掲示板
- **カテゴリ別**: 目的ごとにカテゴリを自由作成（連絡事項、相談、雑談等）
- **ネスト返信（ツリー構造）**: 特定の発言に返信すると、その下に深くなっていく（LINEのように流れない）
- **重要度5段階**: CRITICAL → IMPORTANT → WARNING → INFO → LOW
  - 重要度に応じてお知らせ欄・LINE・ポップアップへ自動通知
- **既読機能（投稿時に選択可）**:
  - 数字のみ: 「既読 15」のように人数表示
  - 閲覧者表示: アイコンが並び、クリックでアカウント名表示
- ピン留め・ロック機能

#### 18. マッチング・対外交流
- 地域やレベルに応じた対戦相手・交流先の募集・応募
- マッチング成立時、**双方のスケジュールへ自動反映**
- **NGチーム設定**: トラブル防止のため、特定の相手をブロック・非表示にする機能

#### 19. パフォーマンス管理

> 📄 詳細設計: [docs/features/F07_performance.md](docs/features/F07_performance.md)

- カスタムデータ記録（得点、出場時間、走行距離等を自由に定義）
- チームで記録されたデータが個人の専用ダッシュボードへ自動反映
- 個人・チーム統計ダッシュボード

#### 20. メンバー紹介

> 📄 詳細設計: [docs/features/F06_member_gallery.md](docs/features/F06_member_gallery.md)

- **メインページ** + 月/年ごとの詳細ページ（階層構造）
- メインページから各詳細ページへ遷移可能
- **メンバー一覧ページ**（年度ごと作成）: 画像、名前、一言、拡張フィールド対応
- 管理者が「作成ボタン」でプロフィール項目を自由に追加可能

#### 21. 回覧板
- 文書・お知らせをメンバーに回覧し、全員の確認完了で完結
- 回覧順序: **順番あり**（前の人が押印後に次の人へ）/ **全員同時**（一斉配布）の2方式
- 確認 = 電子印鑑を押す操作（電子印鑑機能と連携）
- 未確認者への自動リマインド通知
- 回覧完了後は自動アーカイブ・押印ログを保持

#### 22. 電子印鑑
- ユーザー登録時の姓を印鑑風フォントで SVG 生成・保持
- 回覧板・同意書など複数機能で横断利用できる共通コンポーネント
- 押印日時・対象ドキュメントのログを記録
- 姓（`last_name`）の変更時は印鑑 SVG を自動的に再生成する。`POST /users/{id}/seal/regenerate` は手動再生成のためのエンドポイント

#### 23. 緊急安否確認
- 管理者がワンクリックで全メンバーへ安否確認を一斉送信（プッシュ通知 + WebSocket）

**メンバー側の表示フロー**
- **ログイン中の場合**: 送信直後にモーダル（子画面）をポップアップ。背景をオーバーレイでブロックし、回答するまで閉じられない
- **ログイン後の場合**: 未回答の安否確認が存在する状態でログインすると、直後に同モーダルを自動表示。回答済みの場合はスキップ

**安否報告フロー（モーダル内）**
1. 「安全を報告する」ボタンを表示
2. ボタン押下後に以下を表示:
   - GPS位置情報を送信するかどうかのチェックボックス（任意・デフォルト OFF）
   - メッセージ入力欄（自由記述、nullable）
   - 「送信する」ボタン
3. GPS 同意時はブラウザの Geolocation API で現在位置（緯度・経度）を取得して送信

**管理者側の機能**
- **専用掲示板の自動作成**: 安否確認実行時に対象スコープ（チーム/組織/グループ）内の掲示板へ専用スレッドを自動生成（タイトル: 「【安否確認】{実行日時}」、優先度: 最高）
- 掲示板スレッドにメンバー全員の安否ステータス（安全 / 要支援 / 未回答）と任意メッセージをリアルタイム掲載。回答のたびに自動更新（WebSocket）
- 管理者画面でも並行してリアルタイム集計（回答済み / 未回答 / 要支援の人数・一覧・GPS位置マップ）
- 未回答者への自動リマインド（時間間隔を設定可能）

#### 24. アクセス解析
- チームプロフィール・ブログ記事・活動記録等のページビュー数を自動集計
- **日別・月別・累計**グラフをダッシュボードウィジェットとして表示（Chart.js）
- ログイン済みメンバーの閲覧 / ゲスト閲覧を区別してカウント
- 生ログは **90日間** 保持後に物理削除。日次集計データは永続保持
- 管理者はコンテンツ別（投稿・ページ等）のランキングを閲覧可能

#### 25. テーマ・外観カスタマイズ
- **ライト/ダークモード**: ユーザーが個別に切替。`SYSTEM`（OS設定に追従）も選択可能
- **背景色プリセット**: 5色から選択（デフォルト / ブルー / グリーン / パープル / オレンジ）。ユーザー個人の設定として保存
- **シーズナル壁紙**: SYSTEM_ADMINが開始・終了日時を設定した期間限定壁紙を配信。有効期間中は全ユーザーの背景に適用される（ユーザーの色設定より優先）
- 設定はブラウザの `localStorage` にも保存し、ログイン前も適用

#### 26. ダイレクトメール配信
- ADMIN / DEPUTY_ADMIN（権限付与済み）が組織/チーム内のメンバーへメールを送信する機能
- **送信対象**: 全メンバー一斉 / グループ指定 / ロール指定 / 個別選択 から選択可能
- **件名・本文**（リッチテキスト対応）・送信日時（即時 or 予約送信）を設定
- **送信回数制限**: チーム/組織単位で **週3回まで無料**。制限到達後は翌週月曜0時にリセット。送信前に残回数を表示する
- **送信履歴**: 件名・送信日時・送信数・開封率を一覧表示（管理者のみ閲覧可）
- ※ 無料枠超過後の課金設定は決済機能（Phase 8）導入時に検討。それまでは週3回が上限

#### 27. 広告表示
- **フリープランのみ表示**: 有料プランに加入済みのチーム/組織は広告非表示。フリープランでのプラットフォーム収益化手段として機能する
- **初期実装（Phase 9）: Amazonアソシエイト**
  - SYSTEM_ADMINがアソシエイトタグIDと表示配置（サイドバー右・バナーフッター等）を管理画面から設定
  - フロントエンドが設定を取得してSSR時に描画。ユーザー属性（チームカテゴリ等）に応じたアソシエイトリンクのカテゴリ切り替えを将来対応
- **将来: ターゲティング広告プラットフォーム**
  - 広告クリエイティブ（画像・タイトル・リンク先）をSYSTEM_ADMINが審査・登録
  - **リンク先**: プラットフォーム内の組織/チームページ（内部リンク）または外部URL（外部HP等）を選択可能
  - **ターゲティング**: 組織カテゴリ（スポーツ/整骨院/学校等）・地域・ユーザーロールに応じた広告の絞り込み配信
  - **インプレッション・クリック計測**: 表示回数・クリック数を記録し、広告効果測定と将来の課金計算（CPM/CPC）に活用
  - 広告主は組織アカウントを持つ運営者が申請し、SYSTEM_ADMINが審査・承認後に掲載
- ※ Phase 1〜8 は `FEATURE_V9_ENABLED=false` により UI・API を完全非表示。DB テーブルは Phase 1 で先行作成（→ アーキテクチャ・開発ガイドライン「フィーチャーフラグ」参照）

#### 28. コルクボード
チャットや投稿の流れに埋もれがちな重要情報を「新聞の切り抜き」感覚でカード化し、ドラッグ＆ドロップで自由に配置・整理できる視覚型ストックボード。

**ボードのスコープ**
- **個人ボード**: 自分専用。他のメンバーには非公開。複数作成可能
- **チーム共有ボード**: チームメンバー全員が閲覧可能。作成はADMIN/DEPUTY_ADMIN、編集権限はADMINが設定
- **組織共有ボード**: 組織メンバー全員が閲覧可能。同上

**カードの種類**

| 種別 | 説明 | 元コンテンツ |
|------|------|------------|
| 参照カード | チャットメッセージ・タイムライン投稿・掲示板スレッド/返信・ブログ記事・ファイルをストック（初期対応。新機能追加時に `ref_type` を拡張して対応可能） | `content_snapshot` で内容を保持（元が削除されても残る） |
| メモカード | ボード専用の独立したテキストメモ | なし |
| URLカード | 外部URLとタイトル・OGPサムネイルを表示 | なし |
| 見出しカード | ボード上に横長のタイトルバーを配置。カードを束ねる意図を示す見出しとして機能（コンテナではなく視覚的なラベル） | なし |

**セクション（折りたたみグループ）**

ボードの最大の整理単位。**見出し付きの領域**にカードを格納し、折りたたみ（collapse）で領域をタイトルバーだけに縮小できる。

- **見出しを付ける**: セクション名（例: 「来週の議題」「決定事項まとめ」）を設定
- **カードを追加**: セクション内に参照カード・メモカード・URLカード・見出しカードを配置
- **折りたたみ**: セクションを折りたたむと、名前のタイトルバーだけが残り、内包するカードが非表示になる（データは保持）
- **展開**: タイトルバーをクリックすると全カードが再表示

> **見出しカード vs セクション の使い分け**
> - **見出しカード**: 「このあたりの話題は〇〇について」という視覚的な仕切り線。格納の概念なし
> - **セクション**: 関連カードを一つにまとめ、折りたたんでボードをスッキリさせたい場合

**ビジュアル整理**
- **自由配置**: カードをドラッグ＆ドロップで任意の座標に配置（X/Y座標をDBに保存）
- **カラーラベリング**: 付箋の色を6色から選択（白/黄/赤/青/緑/紫）
- **カードサイズ**: small / medium / large の3段階
- **ボード背景**: コルク風テクスチャ / ホワイト / ダーク から選択

**コンテキスト保持**
- 参照カードをクリック → サイドパネルで元コンテンツの**前後5件**のコンテキストを表示。元コンテンツへのリンクも提供
- `content_snapshot` により、元の投稿が削除された後もカード上でテキストを確認可能

**追加メモ・自動アーカイブ**
- 各カードに付箋メモ（`user_note`）を追記可能
- カードごとに **自動アーカイブ日時**（`auto_archive_at`）を設定可能。期限到達後はカードを折りたたみ表示（データは保持）
- ボード内キーワード検索・カラーフィルターで目的のカードを素早く特定
- ※ Phase 1〜8 は `FEATURE_V9_ENABLED=false` により UI・API を完全非表示。DB テーブルは Phase 1 で先行作成（→ アーキテクチャ・開発ガイドライン「フィーチャーフラグ」参照）

### 選択式モジュール詳細

#### 1. QR会員証
- メンバーごとに一意のIDを発行し、QRコードを生成
- スマホ画面での表示に最適化
- 来店・受付時のスキャンによる本人確認

#### 2. 会費・決済

> 📄 詳細設計: [docs/features/F04_payments_access_control.md](docs/features/F04_payments_access_control.md)

- 月会費・年会費・都度払いの設定
- 集金管理・支払い状況の一覧表示
- 組織やチームに対する会費の支払い・集金機能

#### 3. 予約管理
- **チームアカウント（枠管理）**: 時間枠の作成・編集・削除、スタッフ別の予約枠管理、予約状況の一覧管理
- **個人アカウント（予約のみ）**: 予約の申込・キャンセル、予約履歴の閲覧
- 予約時間が近づいたらプッシュ通知でリマインド

#### 4. サービス履歴

> 📄 詳細設計: [docs/features/F07_service_records.md](docs/features/F07_service_records.md)

- 施術記録・来店記録・対応履歴等の汎用記録システム
- チームごとに保存したい項目を自由に設定（カスタムフィールド対応）
- メンバー個人のダッシュボードへ自動反映

#### 5. 備品管理

> 📄 詳細設計: [docs/features/F07_equipment.md](docs/features/F07_equipment.md)

- 備品・在庫の管理 +「誰が持っているか」のステータス管理

#### 6. ギャラリー
- メンバーのみが閲覧・アップロードできる写真アルバム
- アルバム管理・閲覧権限設定

#### 7. シフト管理
- 管理者が週/月単位のシフト表を作成・編集・確定・公開
- メンバーが希望日時を事前に提出（希望収集フェーズ）
- 確定後はメンバーへプッシュ通知、個人ダッシュボードへ自動反映
- スタッフ別・日付別の一覧表示

#### 8. 議決権行使・委任状
- 総会・定期会議の議案に対して **電子で賛否を投票**（出席/賛成/反対/棄権）
- 出席できない場合は **委任状を電子提出**（代理人を指定、または白紙委任）
- 投票期限・定足数の設定、リアルタイム集計・結果表示
- 電子印鑑と連携し、提出の証跡を記録
- NPO・協同組合・株主総会など総会を持つ組織で汎用利用可能

#### 9. 住民台帳
- 居室（部屋番号）と区分所有者・賃借人を紐付けて管理
- 所有者 / 賃借人の区別、入退居日の記録
- **非公開設定**: 台帳全体を ADMIN/DEPUTY_ADMIN のみ閲覧可に設定可能。個人は自室・自身の情報のみ閲覧
- **物件掲示板**: 売買希望・賃貸希望を居住者間で掲示（公開範囲はメンバー内のみ）。駐車場区画の譲渡希望は駐車場区画管理モジュールで掲示する

#### 10. 駐車場区画管理
- 区画番号・種別（屋内/屋外/身障者用等）・**個別価格設定**（月額）を区画ごとに登録
- **車両登録**: 個人アカウントが車・バイク・自転車を最大3台まで登録（ナンバー・ニックネーム）
- 利用者の割り当て: 車両単位で紐付け。単一指定・**複数同時指定・一括指定**に対応
- 空き状況をリアルタイム表示（VACANT / OCCUPIED / MAINTENANCE）
- 空き申請・抽選機能（希望者が申請 → 管理者が抽選または先着で割り当て）
- **譲渡希望リスト**: 居住者が不要な区画の譲渡希望を掲示

#### 11. カルテ

> 📄 詳細設計: [docs/features/F07_chart.md](docs/features/F07_chart.md)

- 来店ごとにカルテを作成・蓄積し、顧客の施術履歴を体系的に管理
- **セクション選択式**: 以下のセクションを管理者が ON/OFF で選択して使用。不要なセクションは非表示にできる
  - 問診票・初回同意書（電子印鑑連携）
  - アレルギー・禁忌情報（施術前に目立つ警告表示）
  - ビフォーアフター写真（顧客への公開/非公開を施術者が選択）
  - 担当スタッフ記録
  - 身体チャート（整骨院向け: 人体図にタップでマーク）
  - カラー・薬剤レシピ記録（美容室向け: 配合比率・放置時間）
  - パッチテスト記録（美容室向け）
  - 経過観察グラフ（来店頻度・痛みレベルの時系列表示）
  - 次回推奨メモ（予約リマインドと連携）
  - 顧客への部分共有
  - PDF出力
- **カスタム項目**: デフォルト項目に加え、独自項目を最大5つまで追加可能（型: テキスト / 数値 / 日付 / 選択肢 / チェックボックス）
- ※ 選択式モジュール11個目のため**有料プランが必要**

### 管理者ダッシュボード

#### AA. 管理者ダッシュボード
- 全管理機能を `/admin` 配下に集約
- ユーザー管理、ロール・パーミッション管理、モジュール設定、スケジュール管理、ブログ管理、掲示板カテゴリ管理、備品管理、メンバー紹介管理、広告・スポンサー管理（将来: 自チーム/組織の広告出稿申請・クリエイティブ登録）、予約管理設定、Googleカレンダー設定、LINE設定、SNS設定
- **DEPUTY_ADMIN 権限グループ管理**: パーミッションを個別選択した名前付きグループの作成・編集・複製・削除。グループはテンプレートとして保存でき、DEPUTY_ADMIN ユーザーへ割り当てる。「グループ1（受付・安否確認担当）」「グループ2（スケジュール・ファイル管理担当）」など複数グループの運用が可能。SYSTEM_ADMIN が定めた権限の上限（天井）の範囲内で設定可能
- **DEPUTY_ADMIN の割り当て**: 複数ユーザーを DEPUTY_ADMIN に任命し、それぞれに権限グループを割り当て
- **MEMBER の権限調整**: チェックボックスで MEMBER のデフォルト操作を ON/OFF（`team_role_permissions` に保存）
- **コンテンツ削除ポリシー**: 掲示板・タイムライン・チャット等の他者コンテンツ削除は ADMIN が担う。DEPUTY_ADMIN への削除権限付与は権限グループで明示的に設定した場合のみ有効
- テンプレート・モジュールのON/OFF管理

#### AB. システム管理者ダッシュボード
- プラットフォーム全体の管理を `/system-admin` 配下に集約
- 全組織・チームの一覧・管理
- 定型テンプレート・モジュールの作成・編集・管理
- **モジュールのレベル別適用管理**: 各モジュール（デフォルト・選択式）を組織/チーム/個人のどのレベルで利用可能にするかをON/OFFで制御
- **ロール権限の上限設定**: DEPUTY_ADMIN・MEMBER がチーム/組織レベルで付与できる権限の上限（天井）を `role_permissions` で管理
- **モジュール価格管理**: 選択式モジュールごとの月額・年額価格をリアルタイムに設定変更
- **パッケージ管理**: モジュールをまとめたパッケージの作成・編集・公開/非公開・価格設定
- **割引キャンペーン管理**: 期間限定割引の作成・対象指定（全体/モジュール/パッケージ）・クーポンコード発行・利用状況確認
- **ストレージプラン管理**: ストレージプランの作成・編集（無料枠・月額/年額・超過従量単価・ハードキャップ）。各チームのストレージ使用状況の一覧確認
- **シーズナル壁紙管理**: 期間限定壁紙の作成・画像アップロード・公開期間設定（開始/終了日時）。プレビュー確認後に公開。有効期間中は全ユーザーへ自動適用
- **組織数課金設定**: 組織種別（非営利/営利）ごとの無料枠チーム数・超過課金単価（円/月）を設定。変更は翌月請求サイクルから反映。各組織の現在のチーム数・課金状況の一覧確認

- **消費税設定**: 税名称・税率・表示方式（税込/税抜）の設定
- **エラーレポート管理**: ユーザーから送信されたエラーレポートの一覧・詳細確認（スタックトレース・発生ページ・ユーザーエージェント・任意コメント）。ステータス管理（未対応 / 対応中 / 解決済み）
- **アフィリエイト設定**: AmazonアソシエイトのタグIDと表示配置（サイドバー右・バナーフッター等）を管理画面から設定・切り替え。将来は楽天アフィリエイト等の追加も対応できる設計とする
- **広告管理（将来）**: プラットフォーム全体の広告クリエイティブ（画像・タイトル・リンク先・リンク種別）の登録・審査・公開管理。キャンペーン（掲載期間・表示対象プランスコープ・ターゲティング条件）の作成・一覧確認。インプレッション・クリック数の統計確認
- ユーザーBAN・通報管理
- 監査ログ閲覧
- システム設定・メンテナンス

### 外部連携

#### AC. 外部連携

> 📄 詳細設計: [docs/features/F08_external_integration.md](docs/features/F08_external_integration.md)

- LINE Messaging API（通知、アカウント連携）
- Googleカレンダー（Phase 3: アプリ→Google 一方向同期、Phase 4+: 双方向同期）
- Instagram / X API（SNSフィードキャッシュ）
- Amazonアソシエイト（SYSTEM_ADMINがタグIDと表示配置を設定。フリープランのページのサイドバー/バナーにアソシエイトリンクを表示し、クリック報酬による収益化を実現）

---

## インフラ・パフォーマンス設計

### 画像ストレージ

- **Pre-signed URL**: クライアントがサーバーを経由せず S3 へ直接アップロード（サーバー転送コスト 0）
- **自動変換**: S3 への新規アップロードをトリガーに Lambda が WebP 変換 + 3サイズ生成（thumbnail: 150px / medium: 600px / original）
- **CloudFront 配信**: 画像は CloudFront (CDN) 経由で配信し、S3 への直接リクエストを排除
- **サイズ制限**:

| 種別 | 上限 | 保存形式 |
|------|------|---------|
| アバター | 2MB | WebP 変換, 150×150 固定 |
| 投稿画像 | 10MB / 枚・最大4枚 | WebP 変換, medium + original |
| チームロゴ | 2MB | WebP 変換, 400×400 固定 |

### 動画

- **外部リンクのみ**: 動画ファイルのアップロードは行わない。YouTube / Vimeo 等の URL を貼り付ける方式とする
- **メタデータ自動取得**: 投稿時に oEmbed API を呼び出し、サムネイル URL・タイトルを `timeline_post_attachments` に保存

### フィードキャッシュ（Redis）

- タイムライン最新フィードを Sorted Set でキャッシュ（key: `timeline:{scope}:{id}`, score: 投稿 timestamp）
- 個別投稿は Hash でキャッシュ（key: `post:{postId}`, TTL: 5分）
- 投稿作成・削除時にキャッシュを即時無効化
- **未読通知数**: `unread:{userId}` を Redis カウンターで管理（通知追加で +1、既読で -1）。ダッシュボード・ヘッダーの COUNT クエリを廃止
- **チームモジュール設定**: `team:modules:{teamId}` にキャッシュ（設定変更時に無効化）。モジュール有効化チェックのたびに JOIN する処理を排除
- **SNS フィードキャッシュ**: Instagram/X API の取得結果を `sns_feed:{teamId}:{provider}` に保存（TTL: 15分）。DB テーブルは使用しない

### クエリ最適化

- **ページネーション方式（適材適所）**: データ特性に応じて2方式を使い分ける
  - **Cursor-based（`cursor` / `limit`）**: フィード・タイムライン系（ブログ記事、お知らせ、活動記録、ギャラリー写真）。無限スクロール向き。`WHERE id < :cursor ORDER BY id DESC LIMIT N`。頻繁な更新でもデータ重複・欠落を防止
  - **Offset-based（`page` / `size`）**: 台帳・管理画面系（備品管理、サービス履歴、パフォーマンス、カルテ、監査ログ、メンバー一覧、支払い管理）。ページ番号ジャンプ・検索ソートに適合
  - **共通レスポンス**: 全ページネーション応答に `has_next: boolean` を含む `PaginationMeta` を返し、フロントエンドが方式を意識せず次ページ有無を判定できるようにする。Cursor 方式でも `total_count`（概算可）を返す
  - **Offset-based meta**: `{ "page": 0, "size": 20, "total_elements": 150, "total_pages": 8, "has_next": true }`
  - **Cursor-based meta**: `{ "next_cursor": 41, "limit": 20, "total_count": 150, "has_next": true }`
- **JOIN 一括取得**: タイムライン一覧は投稿者情報・リアクション数・添付ファイルを1クエリで取得
- **IN 句バッチ取得**: 関連エンティティは `WHERE id IN (...)` で N+1 を排除
- **カウンターキャッシュ（denormalize）**: `COUNT(*)` クエリを廃止するため、集計値や表示最適化カラムを保持してアトミック更新する（対象: `timeline_posts.reaction_count`, `timeline_posts.reply_count`, `teams.member_count`, `chat_channels.last_message_at`, `chat_channels.last_message_preview`（最新メッセージ冒頭100字）, `chat_channel_members.unread_count`, `bulletin_threads.reply_count`）
- **ダッシュボード一括取得**: `/dashboard` エンドポイントは内部でも JOIN / Redis を活用し、SQL 発行を最小化する
- **JWT ロール埋め込み**: JWT ペイロードにロール情報を含め、ロール・パーミッション確認の DB アクセスをゼロにする。ロール変更時は Redis 無効化フラグ（`token:invalidated:{userId}`）で即時反映
- **FULLTEXT インデックス**: `LIKE '%...%'` によるフルテーブルスキャンを排除。`blog_posts.body`, `bulletin_threads.title`, `timeline_posts.content`, `chat_messages.body` に MySQL FULLTEXT インデックスを付与し `MATCH() AGAINST()` 構文で検索する

### 通信量削減

- **gzip 圧縮**: Spring Boot の `server.compression.enabled=true` で JSON レスポンスを 60〜80% 圧縮
- **Conditional GET (ETag)**: チームプロフィール・モジュール設定など変化頻度が低いリソースに ETag を付与し、未変更時は 304 Not Modified（ボディ 0 バイト）を返す
- **WebSocket 差分更新**: タイムライン新着・通知は WebSocket で push し、定期ポーリングを廃止する

---

## DB設計

> 📄 各テーブルのカラム定義・制約・設計判断の詳細: [docs/db_design_details.md](docs/db_design_details.md)

### 認証・権限 (18テーブル)
`users`, `roles`, `user_roles`, `permissions`, `role_permissions`, `team_role_permissions`, `permission_groups`, `permission_group_permissions`, `user_permission_groups`, `refresh_tokens`, `two_factor_auth`, `oauth_accounts`, `password_reset_tokens`, `email_verification_tokens`, `email_change_tokens`, `oauth_link_tokens`, `mfa_recovery_tokens`, `webauthn_credentials`

### チーム管理 (1テーブル)
`teams` — 正式名称・愛称・member_count(denormalize)・検索可否・アーカイブ状態を管理

### 組織・マルチ所属 (5テーブル)
`organizations`, `organization_members`, `team_org_memberships`, `team_memberships`, `invite_tokens`

### グループ階層 (3テーブル)
`groups`, `group_members`, `group_hierarchy` — 隣接リスト＋クロージャテーブル方式、再帰CTE対応

### テンプレート・モジュール (9テーブル)
`team_templates`, `template_modules`, `template_fields`, `module_definitions`, `module_field_definitions`, `module_level_availability`, `team_enabled_modules`, `module_recommendations`, `team_module_snapshots`

### プラン・サブスクリプション (9テーブル)
`subscription_plans`, `module_prices`, `plan_packages`, `plan_package_modules`, `discount_campaigns`, `team_discount_usages`, `tax_settings`, `team_subscriptions`, `subscription_invoices`

### 組織数課金 (2テーブル)
`org_count_billing_tiers`, `org_count_invoices` — 組織種別(NONPROFIT/FORPROFIT)ごとの無料枠・課金単価管理

### TODO管理 (3テーブル)
`todos`, `todo_assignees`, `todo_comments`

### ダッシュボード設定 (1テーブル)
`dashboard_widget_settings`

### アクセス解析 (2テーブル)
`page_view_logs`, `page_view_daily_stats` — 生ログ90日保持→物理削除、日次集計は永続保持

### 外観設定 (2テーブル)
`user_appearance_settings`, `seasonal_themes`

### ダイレクトメール配信 (2テーブル)
`direct_mail_logs`, `direct_mail_recipients` — 週3回制限はRedisカウンター管理

### エラーレポート (1テーブル)
`error_reports` — 認証不要の公開エンドポイントで受付

### 広告・アフィリエイト (初期1テーブル / 将来6テーブル)
`affiliate_configs`（初期）/ `ads`, `ad_campaigns`, `ad_targeting_rules`, `ad_impressions`, `ad_clicks`（将来）

### コルクボード (4テーブル)
`corkboards`, `corkboard_cards`, `corkboard_groups`, `corkboard_card_groups`

### QR会員証 (1テーブル)
`member_cards`

### メンション (1テーブル)
`mentions` — ポリモーフィックテーブル（`target_type` + `target_id`）で複数機能横断

### タイムライン・通知 (8テーブル)
`timeline_posts`, `timeline_post_attachments`, `timeline_post_reactions`, `timeline_bookmarks`, `timeline_post_edits`, `user_mutes`, `notifications`, `notification_preferences`

### ソーシャルプロフィール・フォロー (2テーブル)
`user_social_profiles`, `follows`

### チャット (4テーブル)
`chat_channels`, `chat_messages`, `chat_channel_members`, `chat_message_reactions`

### スケジュール・出欠 (6テーブル)
`schedules`, `schedule_attendances`, `event_surveys`, `event_survey_responses`, `schedule_attendance_reminders`, `schedule_cross_refs`

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

### ストレージ課金 (2テーブル)
`storage_plans`, `team_storage_subscriptions`

### 運営ツール (2テーブル)
`equipment_items`, `equipment_assignments`

### メンバー紹介 (4テーブル)
`team_pages`, `team_page_sections`, `member_profiles`, `member_profile_fields`

### マッチング (4テーブル)
`match_requests`, `match_proposals`, `match_reviews`, `ng_teams`

### パフォーマンス管理 (2テーブル)
`performance_metrics`, `performance_records`

### 決済・会費 (6テーブル)
`payment_items`, `stripe_customers`, `member_payments`, `team_access_requirements`, `organization_access_requirements`, `content_payment_gates`

### 通報・モデレーション (1テーブル)
`content_reports` — ポリモーフィック通報管理（`target_type` + `target_id`）。対応アクション（削除・凍結）は API パラメータで実行

### 監査ログ (1テーブル)
`audit_logs` — 保持期間2年（設定変更可）、期限超過分はバッチ削除

### 回覧板 (2テーブル)
`circulation_documents`, `circulation_recipients`

### 電子印鑑 (2テーブル)
`electronic_seals`, `seal_stamp_logs`

### 緊急安否確認 (5テーブル)
`safety_checks`, `safety_responses`, `safety_check_message_presets`, `safety_check_templates`, `safety_response_followups`

### シフト管理 (3テーブル)
`shift_schedules`, `shift_slots`, `shift_requests`

### 議決権行使・委任状 (2テーブル)
`proxy_votes`, `proxy_delegations`

### 住民台帳・物件情報 (3テーブル)
`dwelling_units`, `resident_registry`, `property_listings`

### カルテ (7テーブル)
`chart_records`, `chart_intake_forms`, `chart_photos`, `chart_body_marks`, `chart_formulas`, `chart_section_settings`, `chart_custom_fields`

### 駐車場区画管理 (5テーブル)
`parking_spaces`, `parking_assignments`, `parking_applications`, `parking_listings`, `registered_vehicles`

### 外部連携・広告 (7テーブル)
`line_integration_config`, `user_google_calendar_connections`, `user_calendar_sync_settings`, `user_schedule_google_events`, `ad_slots`, `sponsors`, `ical_subscriptions`

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

#### チーム向け
`GET /modules/prices`, `GET /packages`, `GET /discount-campaigns`, `POST /discount-campaigns/validate` (クーポンコード検証), `GET /teams/{id}/subscription`, `POST /teams/{id}/subscription`, `PUT /teams/{id}/subscription`, `DELETE /teams/{id}/subscription` (解約), `GET /teams/{id}/subscription/invoices`, `GET /storage-plans`, `GET /teams/{id}/storage`, `POST /teams/{id}/storage/subscription`, `PUT /teams/{id}/storage/subscription`

#### システム管理者向け（`/system-admin`）
`GET/POST/PUT/DELETE /system-admin/module-prices`, `GET/POST/PUT/DELETE /system-admin/packages`, `GET/POST/PUT/DELETE /system-admin/discount-campaigns`, `GET /system-admin/discount-campaigns/{id}/usages`, `GET/PUT /system-admin/tax-settings`, `GET/POST/PUT/DELETE /system-admin/storage-plans`, `GET /system-admin/storage-usage` (全チームのストレージ使用状況一覧)

### チーム管理
| Method | Path | 説明 |
|--------|------|------|
| POST | `/teams` | 独立チームの作成 |
| GET | `/teams/{teamId}` | チーム情報取得 |
| PATCH | `/teams/{teamId}` | チーム情報更新 |
| DELETE | `/teams/{teamId}` | チーム削除 |
| GET | `/teams/{teamId}/members` | チームメンバー一覧 |
| POST | `/teams/{teamId}/members` | メンバー追加 |
| DELETE | `/teams/{teamId}/members/{userId}` | メンバー削除 |

※ チームと組織の紐付けは `team_org_memberships` で管理。組織配下へのチーム追加は `POST /organizations/{orgId}/teams/{teamId}/join` で既存チームを参加させる方式

### 組織管理
`CRUD /organizations`, `GET /organizations/{id}/teams`, `POST /organizations/{id}/invite`, `POST /invite-tokens/{token}/accept`, `GET /me/teams`, `GET /me/organizations`

### 招待・QRコード
`POST /teams/{id}/invite-tokens`, `GET /teams/{id}/invite-tokens`, `DELETE /teams/{id}/invite-tokens/{tokenId}`, `POST /organizations/{id}/invite-tokens`, `GET /organizations/{id}/invite-tokens`, `DELETE /organizations/{id}/invite-tokens/{tokenId}`, `POST /invite-tokens/{token}/join`, `GET /invite-tokens/{token}/verify`

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
`GET/POST /timeline`, `GET/PUT/DELETE /timeline/{id}`, `POST/GET /timeline/{id}/replies`, `POST/DELETE /timeline/{id}/reactions`, `GET /timeline/{id}/reactions`, `PATCH /timeline/{id}/pin`, `POST/DELETE /timeline/{id}/bookmark`, `GET /timeline/bookmarks`, `GET /timeline/my`, `GET /timeline/search`, `GET /timeline/drafts`, `GET /timeline/scheduled`, `GET /timeline/{id}/edits`, `PUT /timeline/{id}/read`, `POST/DELETE /mutes`, `GET /mutes`

### ソーシャルプロフィール・フォロー
`POST /social-profiles`, `GET /social-profiles/me`, `GET /social-profiles/handle/{handle}`, `PUT/DELETE /social-profiles/{id}`, `PATCH /admin/social-profiles/{id}/freeze`, `POST /follows`, `DELETE /follows/{followedType}/{followedId}`, `GET /follows/following`, `GET /follows/followers`, `GET /follows/check`

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
`POST /reports`, `GET /admin/reports`, `GET /admin/reports/{id}`, `PATCH /admin/reports/{id}`

### 監査ログ
`GET /admin/audit-logs`, `GET /system-admin/audit-logs`

### データエクスポート
`POST /export/team/{id}`, `GET /export/status/{jobId}`, `GET /export/download/{jobId}`

### 通知
`GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/{id}/read`, `PATCH /notifications/read-all`, `WS /ws`

### 通知受信設定
`GET /notification-preferences`, `PUT /notification-preferences/teams/{teamId}`, `PUT /notification-preferences/organizations/{orgId}`

### メンバー紹介
`GET/POST /team/pages`, `GET/PUT/DELETE /team/pages/{id}`, `PATCH /team/pages/{id}/publish`, `GET/POST /team/pages/{id}/sections`, `PUT/DELETE /team/sections/{id}`, `GET/POST /team/members`, `GET/PUT/DELETE /team/members/{id}`, `POST /team/members/bulk`, `GET/POST /team/member-fields`, `PUT/DELETE /team/member-fields/{id}`

### 管理者ダッシュボード
`GET /admin/dashboard`, `/admin/users/**`, `/admin/roles/**`, `/admin/permissions/**`, `/admin/modules/**`, `/admin/schedules/**`, `/admin/blog/**`, `/admin/bulletin/categories/**`, `/admin/equipment/**`, `/admin/team/**`, `/admin/ads/**`, `/admin/sponsors/**`, `/admin/reservations/**`, `/admin/google-calendar/**`, `/admin/line/**`, `/admin/sns/**`, `GET/POST /admin/permission-groups` (権限グループ一覧・作成), `GET/PUT/DELETE /admin/permission-groups/{id}` (編集・削除), `POST /admin/permission-groups/{id}/duplicate` (複製), `PUT /admin/permission-groups/{id}/assign/{userId}` (ユーザーへのグループ割り当て)`

### システム管理者
`GET /system-admin/dashboard`, `/system-admin/organizations/**`, `/system-admin/teams/**`, `/system-admin/users/**`, `/system-admin/templates/**`, `/system-admin/modules/**`, `/system-admin/reports/**`, `/system-admin/audit-logs/**`, `/system-admin/settings/**`

### Googleカレンダー連携
`GET /admin/google-calendar/status`, `POST /admin/google-calendar/connect`, `GET /admin/google-calendar/callback`, `DELETE /admin/google-calendar/disconnect`, `POST /admin/google-calendar/sync`, `PUT /admin/google-calendar/settings`, `GET /admin/google-calendar/preview`

### LINE連携
`POST /line/webhook`, `POST/DELETE /line/link`

### 広告・スポンサー
`GET /ads`, `GET /sponsors`, `CRUD /admin/ads`, `CRUD /admin/sponsors`

### 回覧板
`GET/POST /circulation`, `GET/PUT/DELETE /circulation/{id}`, `POST /circulation/{id}/stamp`, `GET /circulation/{id}/status`, `GET /circulation/my`

### 電子印鑑
`GET /users/{id}/seal`, `POST /users/{id}/seal/regenerate`, `GET /users/{id}/seal/stamps`, `POST /seal/stamps/{stampId}/revoke`, `GET /seal/stamps/{stampId}/verify`

### 緊急安否確認
`POST /safety-checks` (実行・掲示板スレッド自動生成), `GET /safety-checks/{id}`, `PATCH /safety-checks/{id}/close`, `POST /safety-checks/{id}/respond` (GPS座標・メッセージ・gps_shared フラグを含む), `GET /safety-checks/{id}/results` (集計・GPS位置一覧), `GET /safety-checks/my` (未回答確認・ログイン直後チェック用)

### シフト管理
`GET/POST /shifts/schedules`, `GET/PUT/DELETE /shifts/schedules/{id}`, `PATCH /shifts/schedules/{id}/publish`, `GET/POST /shifts/schedules/{id}/slots`, `PUT/DELETE /shifts/slots/{id}`, `GET/POST /shifts/requests`, `GET /shifts/my`

### 議決権行使・委任状
`GET/POST /proxy-votes`, `GET /proxy-votes/{id}`, `GET /proxy-votes/{id}/results`, `POST /proxy-votes/{id}/cast`, `POST /proxy-votes/{id}/delegate`, `GET /proxy-votes/my`

### 住民台帳・物件情報
`GET/POST /dwelling-units`, `GET/PUT/DELETE /dwelling-units/{id}`, `GET/POST /dwelling-units/{id}/residents`, `PUT/DELETE /dwelling-units/{unitId}/residents/{id}`, `GET/POST /property-listings`, `GET/PUT/DELETE /property-listings/{id}`

### カルテ
`GET/POST /charts`, `GET/PUT/DELETE /charts/{id}`, `POST /charts/{id}/photos`, `DELETE /charts/photos/{id}`, `GET/PUT /charts/{id}/intake-form`, `PUT /charts/{id}/body-marks`, `GET/POST /charts/{id}/formulas`, `PUT/DELETE /charts/formulas/{id}`, `GET /charts/{id}/pdf`, `PATCH /charts/{id}/share`, `POST /charts/{id}/stamp`, `GET /charts/customer/{userId}`, `GET/PUT /charts/settings/sections`, `GET/POST /charts/settings/custom-fields`, `PUT/DELETE /charts/settings/custom-fields/{id}`

### 駐車場区画管理
`GET/POST /parking/spaces`, `GET/PUT/DELETE /parking/spaces/{id}`, `POST /parking/spaces/bulk-assign`, `GET /parking/spaces/vacant`, `GET/POST /parking/applications`, `PATCH /parking/applications/{id}/approve`, `GET/POST /parking/listings`, `GET/PUT/DELETE /parking/listings/{id}`

### アクセス解析
`GET /teams/{id}/analytics` (累計), `GET /teams/{id}/analytics/daily` (日別), `GET /teams/{id}/analytics/monthly` (月別), `GET /teams/{id}/analytics/content` (コンテンツ別ランキング)

### 外観設定
`GET/PUT /users/appearance` (ユーザー個人設定), `GET /seasonal-themes/active` (現在有効なシーズナルテーマ取得), `GET/POST/PUT/DELETE /system-admin/seasonal-themes` (SYSTEM_ADMIN管理)

### ダイレクトメール配信
`GET /direct-mails/quota` (週次残回数確認), `POST /direct-mails` (送信・予約送信), `GET /direct-mails` (送信履歴一覧), `GET /direct-mails/{id}` (詳細・開封状況), `DELETE /direct-mails/{id}` (予約送信キャンセル)

### エラーレポート
`POST /error-reports` (送信・認証不要), `GET /system-admin/error-reports` (一覧・SYSTEM_ADMIN), `GET /system-admin/error-reports/{id}` (詳細), `PATCH /system-admin/error-reports/{id}/status` (ステータス更新)

### コルクボード
`GET/POST /corkboards` (ボード一覧・作成), `GET/PUT/DELETE /corkboards/{id}` (詳細・更新・削除), `GET/POST /corkboards/{id}/cards` (カード一覧・追加), `PUT/DELETE /corkboards/{id}/cards/{cardId}` (カード更新・削除), `PATCH /corkboards/{id}/cards/{cardId}/position` (位置のみ更新。頻繁に呼ばれるため軽量エンドポイントを分離), `GET/POST /corkboards/{id}/sections` (セクション一覧・作成), `PUT/DELETE /corkboards/{id}/sections/{sectionId}` (セクション更新・削除), `PATCH /corkboards/{id}/sections/{sectionId}/collapse` (折りたたみ状態のトグル), `GET /chat/messages/{id}/context` (参照カードのコンテキスト取得。前後N件), `GET /timeline-posts/{id}/context` (同上・タイムライン), `GET /bulletin-threads/{id}/context` (同上・掲示板)

### 課金サマリー（ダッシュボード用）
`GET /teams/{id}/billing/current-month` (今月の課金合計・内訳。ADMIN/DEPUTY_ADMIN), `GET /organizations/{id}/billing/current-month` (今月の課金合計・内訳。ADMIN/DEPUTY_ADMIN), `GET /users/me/billing/current-month` (個人向け課金サマリー。将来対応)

### 組織数課金
`GET /organizations/{id}/billing/team-count` (現在のチーム数・課金見込み額確認), `GET /organizations/{id}/billing/invoices` (組織数課金請求書一覧), `GET /organizations/{id}/billing/invoices/{month}` (月次請求書詳細), `POST /organizations/{id}/org-type/change-request` (組織種別変更申請), `GET/PUT /system-admin/org-count-billing-tiers` (無料枠・単価設定), `GET /system-admin/org-count-billing/overview` (全組織のチーム数・課金状況一覧), `GET/PATCH /system-admin/org-type-change-requests` (種別変更申請の一覧・承認/拒否)

### 広告・アフィリエイト
`GET /ads/active` (ページ属性に応じたアクティブ広告取得・認証不要), `POST /ads/{id}/impression` (インプレッション記録・将来), `POST /ads/{id}/click` (クリックログ記録・将来), `GET/POST/PUT/PATCH/DELETE /system-admin/affiliate-configs` (アフィリエイト設定管理), `GET/POST /system-admin/ads` (将来: 広告クリエイティブ管理), `PUT/DELETE /system-admin/ads/{id}` (将来), `GET/POST /system-admin/ad-campaigns` (将来: キャンペーン管理), `PUT/DELETE /system-admin/ad-campaigns/{id}` (将来), `GET /system-admin/ad-campaigns/{id}/stats` (将来: インプレッション・クリック統計)

### プロモーション配信（ターゲット通知・クーポン）
ADMIN/事業者（整骨院・美容院等）が郵便番号・ロール等の属性でセグメントを定義し、メンバー・サポーターにプッシュ通知型のプロモーション（クーポン・販促情報）を配信する。通知基盤（F04.3）の `notification_type = PROMOTION` として既存チャネル（IN_APP / Push / LINE）で配信。
`GET/POST /promotions` (プロモーション一覧/作成), `GET/PUT/DELETE /promotions/{id}` (詳細/更新/削除), `POST /promotions/{id}/publish` (配信実行), `GET /promotions/{id}/stats` (開封率・クーポン利用率等の効果測定), `GET/POST /promotions/{id}/segments` (ターゲットセグメント管理: 郵便番号・ロール・チーム属性), `GET/POST /coupons` (クーポン一覧/作成), `GET/PUT/DELETE /coupons/{id}` (詳細/更新/無効化), `POST /coupons/{id}/redeem` (クーポン利用), `GET /coupons/my` (自分の受け取り済みクーポン一覧), `GET /system-admin/promotions/billing` (プロモーション課金状況一覧)

---

## 実装フェーズ

| Phase | 内容 | 成果物 |
|-------|------|--------|
| **1** | プロジェクト基盤 + 3層アカウント構造 + 認証 + ロール・パーミッション + テンプレート・モジュール基盤 + 全DB設計(Flyway) | Auth API + 組織API + テンプレートAPI + Swagger UI |
| **2** | QR会員証 + マイダッシュボード + ダッシュボードUXカスタマイズ | QR API + ダッシュボードAPI |
| **3** | スケジュール・出欠管理 + 予約管理 + シフト管理 + 緊急安否確認 + Googleカレンダー同期 | カレンダー + 出欠API + 予約API + シフトAPI + 安否確認API + Google連携 |
| **4** | タイムライン + チャット（階層スレッド・絵文字リアクション）+ プッシュ通知 | X風フィード + チャットAPI + WebSocket通知 |
| **5** | 掲示板 + 回覧板 + 電子印鑑 + アンケート・投票 + ファイル共有 | 掲示板API + 回覧板API + 電子印鑑API + アンケートAPI + ファイルAPI |
| **6** | CMS（ブログ・活動記録）+ メンバー紹介 + ギャラリー | CMS API群 |
| **7** | サービス履歴 + パフォーマンス管理 + 備品管理 + カルテ | サービス履歴API + パフォーマンスAPI + 備品API + カルテAPI |
| **8** | マッチング・対外交流 + 会費・決済 + 議決権行使・委任状 | マッチングAPI + 決済API + 議決権行使API |
| **9** | LINE連携・SNS・広告・スポンサー + プロモーション配信（郵便番号ターゲティング・クーポン） + 住民台帳 + 駐車場区画管理 | 外部連携API群 + プロモーションAPI + 住民台帳API + 駐車場API |
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
- **WebSocket 認証**: 接続ハンドシェイク時に `Authorization: Bearer <JWT>` ヘッダーを送信し、Spring Security の `HandshakeInterceptor` で検証する。STOMP `CONNECT` フレームでも同様に JWT を付与する

### エラーレポート収集
エンドユーザーがエラーに遭遇した際に、開発者へ自動的に情報を届ける仕組みを全画面共通で提供する。

**ユーザー側のフロー**
1. 未捕捉エラー発生 → グローバルエラーハンドラーがキャッチ
2. エラーレポートモーダル（子画面）をポップアップ表示
3. モーダル内容:
   - 「予期せぬエラーが発生しました。開発チームへ報告しますか？」
   - 任意コメント入力欄（「このとき何をしていましたか？」）
   - 「送信する」ボタン / 「閉じる」ボタン
4. 送信後: 「ご報告ありがとうございます！開発チームが確認します。」を表示してモーダルを閉じる

**自動収集する情報**（ユーザーには非表示）
- エラーメッセージ・スタックトレース
- 発生ページ（ルートパス）
- ブラウザ・OS情報（User-Agent）
- アプリバージョン
- ユーザーID（ログイン中の場合）・発生日時

**実装方針（フロントエンド）**
- Nuxt の `vueApp.config.errorHandler` と `window.addEventListener('unhandledrejection', ...)` でグローバルに捕捉
- `useErrorReport` composable でモーダル表示・送信ロジックを管理
- 認証不要の公開エンドポイント（`POST /error-reports`）へ送信

**バックエンドのレートリミット**（スパム・DoS 対策）
- 短期制限: 同一 IP につき **1分間に5回まで**
- 日次制限: 同一 IP につき **1日50回まで**
- 超過時は `429 Too Many Requests` + `Retry-After` ヘッダーを返す

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

### フィーチャーフラグ（Phase 制御）

Phase 9 以降で実装される機能（**広告表示 #27・コルクボード #28**）はデフォルト機能として定義されるが、実装完了前に UI/API が露出しないよう環境変数フラグで制御する。

| 項目 | Phase 1〜8 | Phase 9 完了後 |
|------|-----------|---------------|
| **DB スキーマ** | Phase 1 の Flyway マイグレーションでテーブル定義のみ先行作成 | 変更なし |
| **UI 表示** | `FEATURE_V9_ENABLED=false` により関連コンポーネント（メニュー・ボタン・設定画面）を完全非表示 | `FEATURE_V9_ENABLED=true` に変更し、全組織へ常時有効化 |
| **API エンドポイント** | 未実装エンドポイントへのリクエストは `503 Service Unavailable` を返す | 通常のレスポンスを返す |

**Phase 9 完了後のリファクタリング**: フラグを `true` に固定した後、コードベース内のすべての `if (FEATURE_V9_ENABLED)` 分岐を削除し、機能を常時有効のハードコードに切り替える。フラグは一時的な措置であり、長期間の分岐管理は行わない。

---

## Git運用ルール

### ブランチ戦略（GitHub Flow 準拠）
- **メインブランチ (`main`)**:
    - 常にデプロイ可能で、テストがパスした状態を維持する。
    - 開発者（およびAI）による直接のコミットは禁止する。
- **フィーチャーブランチ (`feature/[issue-number]-[description]`)**:
    - すべての作業は `main` から分岐したフィーチャーブランチで行う。
    - コミットメッセージは下記の規約を遵守し、`pre-commit` フックをパスさせること。
- **マージプロセス**:
    - 完了後はプルリクエスト (PR) を作成し、CI（テスト・静的解析）が合格したことを確認して `main` へマージする。
- **リリース**: `main` へのマージ後、必要に応じて Git タグ（`v1.0.0` 等）を付与してリリースを管理する。

### コミットメッセージ
- コミットメッセージには **変更内容の要約を日本語で記載** する
- フォーマット例: `機能追加: ユーザー認証APIの実装`、`修正: 出欠回答のバリデーション追加`

## Instructions
- 開発にあたっては、必ずルートの `.claudecode.md` および `BACKEND_CODING_CONVENTION.md`,`FRONTEND_CODING_CONVENTION.md` に記載された規約を最優先で遵守すること。
- ディレクトリ構成は機能別（Feature-based）パッケージングを採用すること。
- 開発フローの制約（AI/人間の区別）は `.claudecode.md` §17 を参照すること。