# F18: 個人ポイントカードウォレット

> **ステータス**: 🟢 実装完了（Phase 1 + Phase 2 スタンプ型 + Phase 3 残高型 + QR 自動特定 + Wake Lock テレメトリ）
> **実装フェーズ**: Phase 1 完了 2026-05-15 / Phase 2 (スタンプ型) 完了 2026-05-16 / Phase 3 (残高型 + QR 自動特定 + Wake Lock テレメトリ) 完了 2026-05-16
> **最終更新**: 2026-05-16
> **モジュール種別**: 個人ユーザー向け汎用機能
> **関連ドキュメント**: F01.6 プロフィールメディア / F10.3 監査ログ / F12.3 GDPR・個人情報管理 / F13 ストレージ統合 / F00 ContentVisibilityResolver（Phase 2 で参照可能性あり）

---

## 1. 概要

### 解決する課題

東急ハンズや大型量販店のレジで「東急ポイント / dポイント / 楽天ポイント / PayPay / Vポイント / Pontaポイント …」など複数のポイントカードを順次提示する手間がユーザーの体験を大きく損ねている。各社の公式アプリを 1 つずつ起動して画面遷移し、店員に見せ、次のカードへ切り替える操作はレジ前で焦りやすく、後ろの行列のプレッシャーも相まって ADHD 傾向のあるユーザーや高齢者には極めて困難である。

「複数のアプリ間を跳ね回って 1 枚ずつ提示する」状況を、**1 つの Mannschaft アプリ内で順番にスワイプするだけ**で完結できるようにする。これは「個人ポイントカードを 1 つの場所に集約し、シーン別（コンビニ・ドラッグストア・家電量販店など）にグループ化して一括提示できる」電子ウォレット機能である。

### ユーザー価値

| 対象 | 価値 |
|---|---|
| 一般ユーザー（個人） | 複数のポイントカードを 1 つのアプリにまとめ、レジ前で焦らずスワイプ提示。物理カードを財布から探す・各社アプリを切り替える手間ゼロ |
| ADHD 傾向のユーザー | 入力摩擦ゼロ・並び順自由・グループ別呼び出し対応で、レジ前パニックを回避 |
| 高齢者ユーザー | バーコード下に大きい数字番号表示・スクリーンリーダー対応で、視認性と聴覚アクセシビリティを担保 |
| マスタに無いマイナー店舗を使うユーザー | プロバイダーマスタに依存せず、カード名を自由入力するだけで追加可能。運営による事業者カバレッジ整備を待たずに即利用開始できる |
| organization 管理者（Phase 2） | 美容室・整骨院・カフェ等の自店ポイントカードを Mannschaft 内で発行・配布できる。顧客が「Mannschaft のウォレットに追加」するだけで運用開始 |

### v1 (Phase 1) の境界

- **対象は他社（外部事業者）のポイントカード**のみ。バーコード/QR を再描画して画面提示する**補助ツール**
- **Phase 1 は他社カードの自由入力登録が主体。運営の `point_card_providers` マスタは「人気 10〜15 社の視認性補強用」に限定**し、マスタに無い事業者のカードはユーザーが自由に名前を入力して登録できる（fuzzy match でロゴ・色を自動補強）
- 公式アプリではないため、ポイント残高同期・付与申請・利用履歴取得は**しない**
- Phase 2 で organization が自店ポイントカードを発行する余地を**スキーマ・enum・カラムで先行確保**
- 個人スコープ専用（team / organization スコープでの共有はなし。Phase 2 でも自店発行プロバイダーの管理だけが organization スコープに乗る）
- iOS/Android のネイティブウォレットアプリ（Apple Wallet / Google Wallet）との連携は**v1 では行わない**

### 法的・規約上の前提

本機能はあくまで「ユーザーが自分で入力したバーコード/カード番号を画面再描画するもの」であり、各ポイント事業者の公式機能・契約に基づくものではない。利用に伴う一切の責任はユーザー本人にある旨を規約・ヘルプ・初回利用時モーダルで明示する（§9 セキュリティ・規約4項目参照）。

---

## 2. スコープ

### 対象ロール

| ロール | 操作可能な範囲 |
|---|---|
| SYSTEM_ADMIN | プロバイダーマスタの管理（CRUD）。個別ユーザーのカード内容は閲覧不可（暗号化のため SYSTEM_ADMIN も復号鍵を直接扱わない運用） |
| ADMIN（organization） | Phase 1: なし / Phase 2: 自組織の自店ポイントカードプロバイダーの発行・管理、スタンプ押印 |
| DEPUTY_ADMIN | Phase 2 で自店発行カードのスタンプ押印権限のみ（ADMIN 委任）|
| MEMBER（一般ユーザー） | 自分のウォレットの全操作（カード追加・編集・削除・グループ化・提示）|
| SUPPORTER | 自身のウォレット操作のみ（MEMBER と同等）|
| GUEST | 利用不可（全 API で 403） |

### 対象レベル

- [ ] 組織 (Organization) — Phase 1 では使用しない（Phase 2 で自店プロバイダー発行の文脈でのみ使用）
- [ ] チーム (Team) — 使用しない
- [x] 個人 (Personal) — Phase 1 の主スコープ。すべてのカードは `user_id` 単位で保管・参照される

### テンプレート推奨

| テンプレート | F18 | 備考 |
|---|---|---|
| 全テンプレート共通 | ○（個人設定でオプトイン） | Phase 1 はユーザー個人がトグルで有効化（規約同意必須）|
| 美容室・整骨院・カフェなどサービス業組織（Phase 2）| ○（自店発行を組織側で有効化）| Phase 2 で organization 側にスイッチを追加 |

> ウォレット機能の利用は `point_card_user_settings.is_enabled = TRUE` のオプトイン制。デフォルトは OFF。初回有効化時に規約同意（§9.2）と利用規約バージョンの記録を必須とする。

---

## 3. ユースケース

### UC-1: 初回利用・規約同意

1. ユーザーが「マイページ」→「ポイントカード」を開く
2. 初回はオンボーディング画面が表示される（4項目規約 §9.2 を全文スクロール + 個別チェック）
3. 同意ボタン押下で `PUT /api/v1/point-cards/settings` が `is_enabled=true, terms_accepted_at, terms_version` を保存
4. ウォレットホーム（カード一覧）に遷移

### UC-2: 他社カード追加（自由入力 + バーコード）

**案 B 改修ポイント**: 案 A で想定していた「プロバイダー選択画面」を**廃止**し、「カード追加フォーム」に統合した。プリセットボタンと自由入力を 1 画面で完結させる。

1. ウォレットホームから「+ 追加」ボタン → カード追加フォームへ遷移
2. フォーム画面構成:
   - **上部: プリセットカードボタン横並び**（人気 10〜15 社のロゴ + 名称。`GET /api/v1/point-cards/providers` で取得した運営マスタを描画）
   - **下部: 自由入力フォーム**（カード名 `display_name`（必須）/ バーコード値・形式 / 任意メモ）
3. **プリセットボタンをタップ**した場合:
   - カード名入力欄に当該プロバイダーの `display_name` が事前充填される
   - バーコード形式の初期値もプロバイダーの `default_barcode_format` が事前選択される
   - ロゴ・ブランドカラーがプレビューに表示される
   - 内部状態として `provider_id` をプリセットの UUID で保持（送信時に明示的に紐付け）
4. **プリセットを使わず自由入力する**場合（マスタに無い事業者・スーパーのスタンプカード等）:
   - カード名（`display_name`）にユーザーが任意の文字列を入力（例: 「近所のスーパー」「○○薬局」）
   - `provider_id` は未設定のまま（リクエストでは `null` で送信）
5. **バーコード入力方法**は以下 3 択（既存通り、いずれも自由入力 / プリセット問わず利用可）:
   - カメラで読み取り（`@zxing/browser`）
   - 画像から読み取り（既存実装に合わせ可）
   - 手入力（直接タイプ）
6. 確認画面で `barcode_value` / `barcode_format` / `last4` のプレビューと、`display_name` / `nickname`（任意、同プロバイダー複数枚保有時の補助識別）/ `memo` を最終確認
7. 「保存」で `POST /api/v1/point-cards` を呼び出し、サーバーが:
   - 受け取った `display_name` をサーバー側で正規化（NFKC + カタカナ→ひらがな + 記号削除 + lower）
   - `point_card_providers` の起動時メモリキャッシュ（人気 10〜15 社）に対して fuzzy match
   - マッチすれば `provider_id` を自動設定、マッチしなければ `provider_id = NULL` のまま保存
   - `display_name` / `barcode_value` / `nickname` / `memo` を AES-256-GCM で暗号化保存
8. 保存完了後、ウォレットホームに戻る。マッチしたカードはプロバイダーロゴ + ブランドカラーで描画され、マッチしなかった自由入力カードは `display_name` の頭文字アイコン + 自動カラーで描画される

### UC-3: 通常提示（個別カード）

1. ウォレットホームでお気に入りのカードタイル（`is_favorite=true` 優先表示）をタップ
2. 提示モード（全画面）に遷移
3. バーコードが大きく再描画される（`jsbarcode` 使用）
4. 画面下に大きい文字で `display_name`（ユーザー入力カード名）と、カード番号（`last4` だけ大きく強調）が並んで表示される。プロバイダー未マッチカードでもレジで「何のカードか」が分かるよう、`display_name` を最優先で視認できるレイアウトにする
5. 自動的に Wake Lock API（フォールバック: `nosleep.js`）で画面が暗くならないようにする
6. スワイプで前後のカードに移動可能
7. 「使用済み」ボタンで `POST /api/v1/point-cards/{id}/used`、`last_used_at` を更新

### UC-4: グループ提示（東急ハンズ用 / コンビニ用 など）

1. ユーザーが「グループ」タブで「東急ハンズ用」グループを作成
2. グループに「東急ポイント / dポイント / 楽天ポイント / PayPay」など複数枚を追加（順序ドラッグ可）
3. 店頭で「東急ハンズ用」グループをタップ → 提示モード（連続スワイプ）に入る
4. 1 枚目（東急ポイント）を店員に見せる → スワイプで 2 枚目（dポイント）に切り替え → 順次
5. グループ提示開始時に `POINT_CARD_VIEWED` 監査ログを 1 件記録（カードごとではなくグループ単位で記録）

### UC-5: オフライン利用（圏外でも提示可能）

1. ウォレットを開くたびに IndexedDB へカード詳細（復号後の `barcode_value` / `nickname`）を保存
2. ローカル保存時に Web Crypto API（AES-GCM）で二重暗号化（鍵はメモリ上のみ、ログアウト時破棄）
3. 圏外でも IndexedDB から読み出して提示可能（7 日 TTL）
4. ログアウト時に IndexedDB を完全クリア

### UC-6: カード削除（永久削除）

1. カード詳細画面で「削除」ボタン
2. 確認モーダル「このカードを完全に削除します。元には戻せません」
3. `DELETE /api/v1/point-cards/{id}` を呼ぶ
4. サーバー側で物理削除（論理削除なし。個人機密のため）
5. `POINT_CARD_DELETED` 監査ログを記録（metadata: `provider_code`（マッチしなかったカードでは `null`）, `provider_matched`, `card_id` のみ。`barcode_value` / `display_name` は含めない）

### UC-7: 退会時の自動消去

1. ユーザーが GDPR 退会フローを実行（F12.3）
2. 30 日猶予期間終了後、`AccountPurgeService` が `users` を物理削除
3. `users` ON DELETE CASCADE で `user_point_cards` / `point_card_groups` / `point_card_group_items` / `point_card_user_settings` が自動連鎖削除

### UC-8（Phase 2）: organization が自店ポイントカードを発行

1. 美容室の ADMIN が「自店ポイントカード発行」画面を開く
2. プロバイダー基本情報（display_name, category, brand_color, logo, type=SELF_ISSUED_STAMP）を入力
3. `point_card_providers` に `organization_id = <店ID>, type = 'SELF_ISSUED_STAMP'` でレコード作成
4. 「お客様用 QR コード」を生成（ディープリンク `mannschaft://wallet/add?providerId={uuid}`）
5. 顧客が QR を読み取り → 自分のウォレットにカードが追加される（`user_point_cards.provider_id` にひも付け）
6. 店主が来店時にスタンプ押印 API を叩く → `stamp_count` がインクリメント

### UC-9（Phase 2）: 自店スタンプの押印

1. 顧客が来店時、店主がスマホで顧客のバーコードを読み取る（Phase 2 で店主向け押印画面実装）
2. `POST /api/v1/orgs/{orgId}/point-cards/{cardId}/stamps` を実行
3. `user_point_cards.stamp_count` が +1、`point_card_stamp_events` に履歴を記録（Phase 2 拡張テーブル）

---

## 4. ドメイン定義

### 4.1 カード種別 enum（`point_card_providers.type`）

| 値 | 説明 | Phase |
|---|---|---|
| `EXTERNAL` | 他社（外部事業者）が発行するポイントカード。Mannschaft はバーコードを再描画するだけで、残高・履歴は管理しない | Phase 1 |
| `SELF_ISSUED_STAMP` | organization が自店発行するスタンプカード（10 個押すと 1 杯無料など）| Phase 2 |
| `SELF_ISSUED_BALANCE` | organization が自店発行するチャージ型残高カード（プリペイド・電子マネー風）| Phase 2 |

### 4.2 カテゴリ enum（`point_card_providers.category`）

| 値 | 説明 |
|---|---|
| `RETAIL` | 家電・量販店（ヨドバシ・ビックカメラ・東急ハンズ等） |
| `CONVENIENCE` | コンビニ（セブン-イレブン・ファミマ・ローソン等） |
| `FOOD` | 飲食・カフェ・ファストフード |
| `TRANSPORT` | 交通系（Suica・PASMO 等は外部 IC のため対象外。あくまでポイント面のみ） |
| `OTHER` | その他（マイレージ・通販・サブスク等） |

### 4.3 バーコード形式 enum（`user_point_cards.barcode_format`）

| 値 | 説明 | レンダリングライブラリ |
|---|---|---|
| `CODE128` | 1 次元バーコード。最も汎用 | `jsbarcode` |
| `CODE39` | 1 次元バーコード | `jsbarcode` |
| `EAN13` | 13 桁の商業バーコード | `jsbarcode` |
| `EAN8` | 8 桁バーコード | `jsbarcode` |
| `JAN13` | 日本の JAN コード（EAN13 互換）| `jsbarcode` |
| `QR` | 2 次元 QR コード | `qrcode` |
| `PDF417` | 2 次元バーコード（航空券・運転免許証等） | `jsbarcode` 拡張 / 別途検討 |
| `ITF` | Interleaved 2 of 5 | `jsbarcode` |
| `NONE` | バーコードなし（カード番号のみ手書き提示する想定）| なし |

### 4.4 用語

| 用語 | 定義 |
|---|---|
| **プロバイダー** | **ロゴ・色補強用の運営マスタ**（人気 10〜15 社）。Phase 1 ではユーザー登録カードに対する任意の補強情報であり、必須の紐付け先ではない。Phase 2 では organization も発行者になる |
| **カード** | ユーザーが追加した個別のカードレコード（同じプロバイダーでも複数枚保持可。例: 家族用 / 自分用）。**ユーザーが入力した `display_name` が一次識別**であり、`provider_id` は fuzzy match でマッチした場合のみ紐付くオプション関連 |
| **`display_name`** | ユーザー自身が入力するカード名（必須）。例: 「東急ポイント」「近所のスーパー」「○○薬局」。AES-256-GCM で暗号化保存される。カード一覧・提示モードでの表示はこの値を一次表示する |
| **`nickname`** | 同じプロバイダーで複数枚持つときの補助識別子（任意）。例: 「家族用」「自分用」。`display_name` と責務分離する（`display_name` = カードの種類名、`nickname` = ユーザー個人の運用ラベル） |
| **fuzzy match** | ユーザー入力 `display_name` を正規化（NFKC + カタカナ→ひらがな + 記号削除 + lower）し、運営マスタの `code` / `display_name` と照合してロゴ・色を自動補強する仕組み。詳細は §7.6 |
| **グループ** | 「東急ハンズ用」「コンビニ用」のようなシーン単位のカード束。提示モードで連続スワイプ可能 |
| **提示モード** | フルスクリーン + Wake Lock + バーコード大表示 + スワイプの専用 UI |
| **last4** | カード番号の下 4 桁。識別用 UI 表示にのみ使用し、暗号化対象外（4 桁単独では特定不可） |

---

## 5. DB 設計

### 5.0 マスタ・シングルトン例外の判定章（CLAUDE.md 原則 6 準拠）

CLAUDE.md 原則 6 では、新規テーブルは **UUIDv7 主キー（CHAR(36) または BINARY(16)）** を必須とする。マスタ例外・シングルトン例外に該当するかを以下で判定する。

#### F18 新規 5 テーブルの分類

| テーブル | 行増加パターン | 判定 |
|---|---|---|
| `point_card_providers` | プロバイダーごとに 1 行（マスタ起動投入 + Phase 2 で自店発行が増加）| **通常 → UUIDv7**（運営マスタ + 自店発行で全テナント共通ではない）|
| `user_point_cards` | ユーザー × カードごとに増加 | **通常 → UUIDv7** |
| `point_card_groups` | ユーザー × グループごとに増加 | **通常 → UUIDv7** |
| `point_card_group_items` | ユーザー × グループ × カードごとに増加 | **通常 → UUIDv7** |
| `point_card_user_settings` | ユーザーごとに 1 行（PK = user_id）| **PK 自然キー（user_id）採用 — 例外**（既存 `users.id` BIGINT を 1:1 共有する設定テーブル）|

**結論**:
- `point_card_user_settings` は「ユーザー 1 人につき 1 行で必ず `users.id` と 1:1 対応する設定テーブル」であり、独立した UUID 主キーを持つ意味がない。`user_id` を PK 兼 FK として使う（シャーディング時も user_id と一緒に同じシャードに乗る前提）。
- 残り 4 テーブルは行が単調増加するため UUIDv7（CHAR(36)）+ `UuidV7Entity` 継承を適用する。

### 5.0.1 原則 7（AbstractTenantAwareRepository）適用判定

原則 7 は `organization_id` カラムを持つテーブル向け。F18 は **個人スコープ**（`user_id` 軸）のため原則 7 は不採用。代わりに**プロジェクト内独自の `AbstractUserOwnedRepository<T, ID>` パターン**を導入する（既存に同名インターフェースがなければ本機能で新設）:

```java
public interface AbstractUserOwnedRepository<T, ID> extends JpaRepository<T, ID> {
    List<T> findByUserId(Long userId);
    List<T> findByUserId(Long userId, Sort sort);
    Optional<T> findByIdAndUserId(ID id, Long userId);
    long countByUserId(Long userId);
}
```

> 既存に `AbstractUserOwnedRepository` パターンがプロジェクトに存在しない場合、本機能の S1 で導入する（先行する個人スコープ機能 F02.5 / F03.15 等で類似のメソッドを各 Repository が個別実装しているなら、共通基底に集約する余地）。実装着手時に既存基盤を再確認し、共通インフラとして抽出する判断は実装フェーズで行う。

### 5.0.2 暗号化方針

`user_point_cards` の `display_name` / `barcode_value` / `nickname` / `memo` を **AES-256-GCM** で暗号化する。実装には既存の `EncryptedStringConverter`（F09.15・F14.2 等で使用済み）を再利用する:

```java
@Convert(converter = EncryptedStringConverter.class)
@Column(name = "barcode_value", columnDefinition = "VARBINARY(1024)")
private String barcodeValue;
```

| フィールド | 暗号化アルゴリズム | NULL 許容 | 補足 |
|---|---|---|---|
| `display_name` | AES-256-GCM | NOT NULL | **案 B で新設**。ユーザー入力のカード名。fuzzy match で `provider_id` を自動補強する元データ |
| `barcode_value` | AES-256-GCM | NOT NULL | バーコード値（カード番号）|
| `nickname` | AES-256-GCM | NULL 可 | 補助識別子（「家族用」等）|
| `memo` | AES-256-GCM | NULL 可 | 任意メモ |

- 鍵管理は環境変数 `MANNSCHAFT_ENCRYPTION_KEY_VERSION_1` を用いる既存方式に従う
- DB 上の型は **VARBINARY**（暗号化済み IV + ciphertext + auth tag のバイナリ）
- **Blind Index は作らない**（カード番号で検索する機能を作らない方針。fuzzy match はサーバー側で受信した平文 `display_name` をその場で正規化してマスタとマッチングするため、暗号化済みの保存値を検索する必要はない）
- `last4` は平文 VARCHAR(4) として別カラムで保持（UI 識別用。4 桁では特定不可）

### 5.1 `point_card_providers`（**ロゴ・色補強用マスタ（10〜15 社）** + Phase 2 自店発行）

> **用途（案 B 改修）**: Phase 1 では「ユーザーがカード追加する際のプリセットボタン提示用」「fuzzy match によるロゴ・ブランドカラー自動補強用」のための**運営管理マスタ**である。マスタに無い事業者のカードはユーザーが `display_name` を自由入力して登録するため、本テーブルが空でもウォレットは機能する（プリセットが出ないだけ）。Seed は人気 10〜15 社のみで開始し、運営判断で追加する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | CHAR(36) | NO | UUIDv7 | PK |
| `code` | VARCHAR(50) | NO | — | 一意コード（`tokyu_point`, `dpoint`, `rakuten` 等。Phase 2 では `org_{orgId}_xxx` 命名）。fuzzy match の正規化マッチ対象 |
| `display_name` | VARCHAR(100) | NO | — | 表示名（例: 「東急ポイント」）。fuzzy match の正規化マッチ対象 |
| `category` | VARCHAR(20) | NO | — | カテゴリ enum（`RETAIL` / `CONVENIENCE` / `FOOD` / `TRANSPORT` / `OTHER`）|
| `type` | VARCHAR(30) | NO | 'EXTERNAL' | カード種別 enum（`EXTERNAL` / `SELF_ISSUED_STAMP` / `SELF_ISSUED_BALANCE`）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | Phase 1 では常に NULL。Phase 2 で自店発行プロバイダーの所属組織を設定 |
| `logo_url` | VARCHAR(500) | YES | NULL | R2 オブジェクトキー（Cloudflare R2、F13 連携） |
| `brand_color` | CHAR(7) | YES | NULL | ブランドカラー（`#E60012` 等）。UI のカードタイル背景に使用 |
| `default_barcode_format` | VARCHAR(20) | YES | NULL | 既定のバーコード形式（`CODE128` 等）。プリセットタップ時のフォーム初期値 |
| `card_number_regex` | VARCHAR(200) | YES | NULL | カード番号の正規表現（例: `^[0-9]{13}$`）。**プリセット選択時のみの参考バリデーション**（必須化はしない。自由入力カードでは適用されない）|
| `card_number_length_hint` | VARCHAR(50) | YES | NULL | UI に表示するヒント（例: 「13 桁の数字」） |
| `is_active` | TINYINT(1) | NO | 1 | プロバイダーが利用可能か（運営側で停止可能） |
| `legal_notice` | TEXT | YES | NULL | 各プロバイダー固有の注意書き（多言語キー or 日本語デフォルト）。Phase 1.1 で i18n 化検討 |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |
| `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) ON UPDATE | |

**インデックス**

```sql
UNIQUE KEY uq_pcp_code (code)
INDEX idx_pcp_category_active (category, is_active)
INDEX idx_pcp_type_org (type, organization_id)            -- Phase 2 自店発行検索用
```

**制約**

```sql
CONSTRAINT chk_pcp_type_org_consistency CHECK (
    (type = 'EXTERNAL' AND organization_id IS NULL)
    OR (type IN ('SELF_ISSUED_STAMP', 'SELF_ISSUED_BALANCE') AND organization_id IS NOT NULL)
)
```

> Phase 1 では `type='EXTERNAL'` 行のみが Seed 投入される（V9.141、**人気 10〜15 社程度に縮小**）。`organization_id` カラムを最初から用意することで、Phase 2 で organization が自店発行する際に**スキーマ破壊的変更なし**で受け入れられる。マスタ拡充は運営判断で随時行われ、本機能の必須要件ではない。

**FK 方針**

- `organization_id` は **クロスドメイン弱参照**。FK は張らず INDEX のみ（CLAUDE.md 原則 1 準拠）
- Phase 2 で organization が削除されたときの自店プロバイダーの扱いは「`is_active = 0` に強制 + 親 organization 行に `ON DELETE CASCADE` 相当のアプリ層削除イベント」で対応する（クロスドメイン CASCADE は禁止のため、`OrganizationDeletedEvent` を購読して S6 で対応）

### 5.2 `user_point_cards`（ユーザー保有カード）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | CHAR(36) | NO | UUIDv7 | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | カード所有者（FK → users.id ON DELETE CASCADE）|
| `display_name` | VARBINARY(1024) | NO | — | **【案 B 新設】AES-256-GCM 暗号化**: ユーザー入力のカード名（例: 「東急ポイント」「近所のスーパー」）。カード一覧・提示モードの一次表示はこの値。fuzzy match の元データ |
| `provider_id` | CHAR(36) | **YES** | NULL | **【案 B 変更】**プロバイダー（FK → point_card_providers.id **ON DELETE SET NULL**）。fuzzy match がマッチした場合のみ自動セット。マッチしない自由入力カードは NULL のまま保存される |
| `nickname` | VARBINARY(1024) | YES | NULL | **AES-256-GCM 暗号化**: 同一プロバイダー複数枚保有時の補助識別子（例: 「家族用」「自分用」）。`display_name` とは責務分離 |
| `barcode_value` | VARBINARY(1024) | NO | — | **AES-256-GCM 暗号化**: バーコードの数値文字列（カード番号） |
| `barcode_format` | VARCHAR(20) | NO | 'CODE128' | バーコード形式 enum |
| `last4` | VARCHAR(4) | YES | NULL | カード番号下 4 桁（平文、UI 識別用。4 桁単独では特定不可、INDEX なし）|
| `memo` | VARBINARY(2048) | YES | NULL | **AES-256-GCM 暗号化**: 任意メモ |
| `is_favorite` | TINYINT(1) | NO | 0 | お気に入り（一覧で上位表示） |
| `display_order` | INT UNSIGNED | NO | 0 | 表示順序（ユーザーがドラッグで設定） |
| `balance` | DECIMAL(12,2) | YES | NULL | **Phase 2 用**: チャージ型残高（Phase 1 では常に NULL）|
| `stamp_count` | INT UNSIGNED | YES | NULL | **Phase 2 用**: スタンプ数（Phase 1 では常に NULL）|
| `last_used_at` | DATETIME(6) | YES | NULL | 直近使用日時（`POST /used` で更新） |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |
| `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) ON UPDATE | |

**インデックス**

```sql
INDEX idx_upc_user_favorite (user_id, is_favorite, display_order)   -- お気に入り優先一覧
INDEX idx_upc_user_last_used (user_id, last_used_at DESC)            -- 直近使った順
INDEX idx_upc_provider (provider_id)                                 -- プロバイダー削除前の参照件数チェック用
```

**制約**

- 物理削除のみ（論理削除なし）— カード番号は個人機密のため、`deleted_at` で残しておく価値がない（F12.3 ポリシー準拠）
- `last4` には INDEX を作らない（検索する機能を作らない方針 = 攻撃面減少）

**FK 方針**

- `user_id` → `users.id` **ON DELETE CASCADE**（同一「個人スコープ」ドメイン内とみなす — CLAUDE.md 原則 2 の判定で、退会時にユーザー個人データが連鎖削除されるべきテーブルとして許可される）
- `provider_id` → `point_card_providers.id` **ON DELETE SET NULL**（**案 B 変更**: プロバイダーが消えてもカード自体は「`display_name` を持つ自由入力カード」として残存させる。RESTRICT だと運営マスタの整理が困難になるうえ、ユーザーから見るとロゴ・色だけが消えるだけで操作不可ではないため、SET NULL の方が UX 妥当。マッチ済みカードがマッチ解除されても、ユーザー入力の `display_name` は残るためカード機能自体は維持される）

**Java Entity**

```java
@PersonalData(category = "point_cards")    // F12.3 GDPR 連携
@Entity
@Table(name = "user_point_cards")
public class UserPointCardEntity extends UuidV7Entity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "display_name", nullable = false, columnDefinition = "VARBINARY(1024)")
    private String displayName;

    // 【案 B】NULL 許容。fuzzy match がマッチした場合のみセットされる
    @Column(name = "provider_id", columnDefinition = "CHAR(36)")
    private UUID providerId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "nickname", columnDefinition = "VARBINARY(1024)")
    private String nickname;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "barcode_value", nullable = false, columnDefinition = "VARBINARY(1024)")
    private String barcodeValue;

    @Column(name = "barcode_format", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private BarcodeFormat barcodeFormat;

    @Column(name = "last4", length = 4)
    private String last4;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "memo", columnDefinition = "VARBINARY(2048)")
    private String memo;

    @Column(name = "is_favorite", nullable = false)
    private boolean favorite;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    // Phase 2 用（Phase 1 は常に null）
    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "stamp_count")
    private Integer stampCount;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;
}
```

**Repository**

```java
public interface UserPointCardRepository
    extends AbstractUserOwnedRepository<UserPointCardEntity, UUID> {

    @Query("SELECT c FROM UserPointCardEntity c WHERE c.userId = :userId "
         + "ORDER BY c.favorite DESC, c.displayOrder ASC, c.createdAt DESC")
    List<UserPointCardEntity> findAllOrdered(@Param("userId") Long userId);

    long countByUserId(Long userId);   // 上限 200 件チェック用
}
```

### 5.3 `point_card_groups`（カードグループ）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | CHAR(36) | NO | UUIDv7 | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | グループ所有者（FK → users.id ON DELETE CASCADE）|
| `name` | VARCHAR(64) | NO | — | グループ名（例: 「東急ハンズ用」） |
| `emoji` | VARCHAR(8) | YES | NULL | 絵文字 1 文字（UTF-8 で 4 バイト想定。表示装飾用） |
| `display_order` | INT UNSIGNED | NO | 0 | グループ一覧の表示順 |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |
| `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) ON UPDATE | |

**インデックス**

```sql
INDEX idx_pcg_user (user_id, display_order)
```

> グループ名・絵文字は暗号化対象外（PII ではなくシーン名のため）。`name` 上限 64 文字 / `emoji` 上限 8 バイト。

### 5.4 `point_card_group_items`（グループ ↔ カードの中間テーブル）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | CHAR(36) | NO | UUIDv7 | PK |
| `group_id` | CHAR(36) | NO | — | FK → point_card_groups.id **ON DELETE CASCADE**（同ドメイン内）|
| `card_id` | CHAR(36) | NO | — | FK → user_point_cards.id **ON DELETE CASCADE**（同ドメイン内）|
| `display_order` | INT UNSIGNED | NO | 0 | グループ内の提示順 |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |

**インデックス・制約**

```sql
UNIQUE KEY uq_pcgi_group_card (group_id, card_id)   -- 同じカードは同グループ内に 1 回のみ
INDEX idx_pcgi_group_order (group_id, display_order)
INDEX idx_pcgi_card (card_id)                        -- カード削除時の中間テーブル整理用
```

### 5.5 `point_card_user_settings`（ユーザー設定）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `user_id` | BIGINT UNSIGNED | NO | — | PK 兼 FK（→ users.id ON DELETE CASCADE）|
| `is_enabled` | TINYINT(1) | NO | 0 | 機能の有効化（オプトイン） |
| `terms_accepted_at` | DATETIME(6) | YES | NULL | 規約同意日時 |
| `terms_version` | VARCHAR(20) | YES | NULL | 同意した規約のバージョン（`v1.0.0` 等） |
| `require_biometric_on_show` | TINYINT(1) | NO | 0 | 提示モード起動前に WebAuthn 再認証を要求するか |
| `created_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |
| `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) ON UPDATE | |

> 主キーが `user_id` の 1:1 設定テーブルなので **§5.0 例外区分（PK 自然キー）** を適用。UUIDv7 主キーは持たない。

### 5.6 ER 図

```mermaid
erDiagram
    users ||--o{ user_point_cards : "user_id CASCADE"
    users ||--o{ point_card_groups : "user_id CASCADE"
    users ||--|| point_card_user_settings : "user_id PK+FK CASCADE"

    point_card_providers ||--o{ user_point_cards : "provider_id nullable, SET NULL"
    point_card_groups ||--o{ point_card_group_items : "group_id CASCADE"
    user_point_cards ||--o{ point_card_group_items : "card_id CASCADE"

    point_card_providers {
        CHAR id PK "UUIDv7"
        VARCHAR code UK
        VARCHAR display_name
        VARCHAR category
        VARCHAR type "EXTERNAL | SELF_ISSUED_STAMP | SELF_ISSUED_BALANCE"
        BIGINT organization_id "NULL in Phase 1"
        VARCHAR brand_color
    }

    user_point_cards {
        CHAR id PK "UUIDv7"
        BIGINT user_id
        VARBINARY display_name "AES-256-GCM, NOT NULL"
        CHAR provider_id FK "nullable, ON DELETE SET NULL"
        VARBINARY barcode_value "AES-256-GCM"
        VARBINARY nickname "AES-256-GCM"
        VARCHAR last4 "plaintext, 4 chars"
        VARBINARY memo "AES-256-GCM"
        DECIMAL balance "Phase 2"
        INT stamp_count "Phase 2"
    }

    point_card_groups {
        CHAR id PK "UUIDv7"
        BIGINT user_id
        VARCHAR name
        VARCHAR emoji
    }

    point_card_group_items {
        CHAR id PK "UUIDv7"
        CHAR group_id FK
        CHAR card_id FK
        INT display_order
    }

    point_card_user_settings {
        BIGINT user_id PK "= users.id"
        TINYINT is_enabled
        DATETIME terms_accepted_at
        VARCHAR terms_version
        TINYINT require_biometric_on_show
    }
```

**FK 方針まとめ**

| FK 関係 | 種別 | ON DELETE | 根拠 |
|---|---|---|---|
| `user_point_cards.user_id → users.id` | クロスドメイン弱参照に見えるが、退会時の連鎖削除を物理保証する必要がある | CASCADE | F12.3 GDPR 物理削除ポリシー準拠。ユーザー個人機密として連鎖削除 |
| `user_point_cards.provider_id → point_card_providers.id` | 同一機能内（共にウォレットドメイン）、ただし **NULL 許容** | **SET NULL** | **【案 B 変更】**プロバイダーマスタは「ロゴ・色補強用」の任意紐付け。プロバイダーが削除されてもカード本体（ユーザー入力 `display_name` + バーコード）は機能し続けるべきであり、RESTRICT で運営側を縛るより SET NULL でカードを「自由入力扱い」に戻す方が UX 妥当。ユーザー側は再度プリセットを選び直すか、そのまま使い続けるかを選択できる |
| `point_card_groups.user_id → users.id` | 同上 | CASCADE | 同上 |
| `point_card_group_items.group_id → point_card_groups.id` | 同一機能内 | CASCADE | 同ドメイン内 CASCADE 許可 |
| `point_card_group_items.card_id → user_point_cards.id` | 同一機能内 | CASCADE | 同ドメイン内 CASCADE 許可 |
| `point_card_user_settings.user_id → users.id` | 同上 | CASCADE | 1:1 設定の連鎖削除 |
| `point_card_providers.organization_id → organizations.id` | クロスドメイン | **FK なし**（INDEX のみ）| CLAUDE.md 原則 1。Phase 2 で `OrganizationDeletedEvent` で対応 |

---

## 6. API 設計

### 6.1 エンドポイント一覧（14 本）

| メソッド | パス | 認可 | 用途 |
|---|---|---|---|
| GET | `/api/v1/point-cards/providers` | MEMBER | **プリセット提示用**プロバイダー一覧（人気 10〜15 社、category フィルタ、is_active=true のみ）|
| GET | `/api/v1/point-cards` | MEMBER | 自分のカード一覧（last4 のみ返却、barcode_value は返さない）|
| POST | `/api/v1/point-cards` | MEMBER | カード追加 |
| GET | `/api/v1/point-cards/{id}` | MEMBER | カード詳細（復号値を含む。提示時に呼ばれる）|
| PATCH | `/api/v1/point-cards/{id}` | MEMBER | カード更新（nickname / memo / is_favorite / display_order 等）|
| DELETE | `/api/v1/point-cards/{id}` | MEMBER | カード削除（物理削除）|
| POST | `/api/v1/point-cards/{id}/used` | MEMBER | 「使用済み」記録（last_used_at 更新）|
| GET | `/api/v1/point-cards/groups` | MEMBER | グループ一覧（カード ID リストのみ）|
| POST | `/api/v1/point-cards/groups` | MEMBER | グループ作成 |
| GET | `/api/v1/point-cards/groups/{id}` | MEMBER | グループ詳細（カード復号値を JOIN FETCH で 1 回 SQL で返却）|
| PATCH | `/api/v1/point-cards/groups/{id}` | MEMBER | グループ更新（name / emoji / カード並び替え・追加・削除）|
| DELETE | `/api/v1/point-cards/groups/{id}` | MEMBER | グループ削除（中間テーブルが CASCADE で消える）|
| GET | `/api/v1/point-cards/settings` | MEMBER | ユーザー設定取得（is_enabled / terms_accepted_at 等）|
| PUT | `/api/v1/point-cards/settings` | MEMBER | ユーザー設定更新（オプトイン・規約同意・WebAuthn 要求設定）|

> **Phase 2 で追加予定の API**: `POST /api/v1/orgs/{orgId}/point-cards` (organization が自店プロバイダー発行), `POST /api/v1/orgs/{orgId}/point-cards/{cardId}/stamps` (スタンプ押印), `POST /api/v1/orgs/{orgId}/point-cards/{cardId}/balance-events` (チャージ・引き落とし) など。

### 6.2 主要エンドポイント仕様

#### GET `/api/v1/point-cards/providers`

**用途（案 B 改修）**: カード追加フォーム上部の**プリセットボタン**に表示する人気 10〜15 社の一覧を返す。Phase 1 では「ロゴ・色補強用マスタ」であり、ここに無い事業者はユーザーが自由入力で登録する（プリセットを使わない / 使えない場合でも、サーバー側は受信 `display_name` を正規化して同じマスタに対して fuzzy match を行い、暗黙的にロゴ・色を補強する）。

**認可**: 認証済みユーザー（MEMBER 以上）

**クエリパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| `category` | String | カテゴリで絞り込み（`RETAIL` 等。省略時は全件） |
| `q` | String | display_name の部分一致検索（任意。最大 50 文字） |

**レスポンス（200 OK）**

```json
{
  "data": [
    {
      "id": "01928a3e-4b2f-7a8c-9d12-...",
      "code": "tokyu_point",
      "display_name": "東急ポイント",
      "category": "RETAIL",
      "type": "EXTERNAL",
      "logo_url": "https://r2-cdn.../providers/tokyu_point.png?sig=...",
      "brand_color": "#E60012",
      "default_barcode_format": "CODE128",
      "card_number_length_hint": "16 桁の数字",
      "legal_notice": "本機能は東急株式会社の公式アプリではありません..."
    }
  ]
}
```

> `is_active=false` のレコードは返さない。`type='SELF_ISSUED_*'` のレコードも Phase 1 ではフィルタアウト（Phase 2 で `organization_id` でフィルタするバリアントを追加）。

#### POST `/api/v1/point-cards`

**認可**: MEMBER（本人のみ。`point_card_user_settings.is_enabled = true` 必須）

**リクエスト（案 B: `display_name` 必須・`provider_id` 任意）**

プリセット未使用（自由入力カード）の例:

```json
{
  "display_name": "近所のスーパー",
  "provider_id": null,
  "barcode_value": "1234567890123",
  "barcode_format": "CODE128",
  "nickname": null,
  "memo": "○○通り店",
  "is_favorite": false
}
```

プリセットを使用してフォームに事前充填された場合の例（クライアントが明示的に `provider_id` を送る）:

```json
{
  "display_name": "東急ポイント",
  "provider_id": "01928a3e-4b2f-7a8c-9d12-...",
  "barcode_value": "1234567890123",
  "barcode_format": "CODE128",
  "nickname": "家族用",
  "memo": null,
  "is_favorite": false
}
```

> クライアントが `provider_id` を送らない場合（自由入力）でも、サーバーは `display_name` の fuzzy match を試みる。マッチすればその場で `provider_id` を補完し、マッチしなければ NULL のまま保存する。クライアントが `provider_id` を明示送信した場合、その値が優先される（fuzzy match の上書きは行わない）。

**バリデーション**

- `display_name`: **必須**、1〜100 文字（trim 後）
- `barcode_value`: 必須、1〜100 文字
- `provider_id`: 任意。指定された場合は `point_card_providers` に存在し `is_active=true` であること
- プロバイダーが解決した場合（クライアント送信または fuzzy match マッチ）で、かつ `card_number_regex` が設定されている場合は**参考バリデーション**として `barcode_value` を検証する（不一致でも警告レベル。Phase 1 では `POINT_CARD_002` を返すのはプロバイダー明示指定時のみ。fuzzy match で偶発的にマッチした場合は警告に留め保存を継続する方針 — 実装時に最終決定）
- 同一 user の既存カード数が **200 件未満**（`POINT_CARD_003` 上限超過エラーを返却）
- `nickname`: 任意、1〜100 文字
- `memo`: 任意、1〜500 文字

**副作用**

1. `display_name` をサーバー側で正規化（§7.6）→ `point_card_providers` のメモリキャッシュに対して fuzzy match
2. クライアント送信の `provider_id` が NULL であれば、fuzzy match 結果で補完。クライアントが値を明示送信した場合はその値を尊重
3. `barcode_value` の下 4 桁を抽出して `last4` に格納
4. `display_name` / `nickname` / `barcode_value` / `memo` を `EncryptedStringConverter` 経由で AES-256-GCM 暗号化保存
5. 監査ログ `POINT_CARD_CREATED` 発火（metadata: `{"provider_code": "tokyu_point", "provider_matched": true, "card_id": "<uuid>"}` または `{"provider_code": null, "provider_matched": false, "card_id": "<uuid>"}`）— `display_name` / `barcode_value` / `nickname` / `memo` は含めない

**レスポンス（201 Created）**

マッチした場合:

```json
{
  "data": {
    "id": "01928a3e-...",
    "display_name": "東急ポイント",
    "provider": {
      "id": "01928a3e-4b2f-...",
      "code": "tokyu_point",
      "display_name": "東急ポイント",
      "logo_url": "...",
      "brand_color": "#E60012"
    },
    "last4": "0123",
    "barcode_format": "CODE128",
    "nickname": "家族用",
    "is_favorite": false,
    "created_at": "2026-05-14T09:00:00+09:00"
  }
}
```

マッチしなかった場合（自由入力カード）:

```json
{
  "data": {
    "id": "01928a3e-...",
    "display_name": "近所のスーパー",
    "provider": null,
    "last4": "0123",
    "barcode_format": "CODE128",
    "nickname": null,
    "is_favorite": false,
    "created_at": "2026-05-14T09:00:00+09:00"
  }
}
```

> `provider` フィールドは **Nullable**。フロントは null の場合、`display_name` の頭文字アイコン + `display_name` ハッシュ由来の自動カラー（HSL ベース）でカードタイルを描画する。201 レスポンスには `barcode_value` の平文は返さない（DRY: 詳細 API で再取得させる方針）。

#### GET `/api/v1/point-cards`

**認可**: MEMBER

**クエリパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| `group_id` | UUID | 指定すると当該グループに属するカードのみ返す（任意）|
| `favorite` | Boolean | true で `is_favorite=true` のみ |

**レスポンス（200 OK）**

```json
{
  "data": [
    {
      "id": "01928a3e-...",
      "display_name": "東急ポイント",
      "provider": {
        "id": "01928a3e-4b2f-...",
        "code": "tokyu_point",
        "display_name": "東急ポイント",
        "logo_url": "https://r2-cdn.../...",
        "brand_color": "#E60012"
      },
      "last4": "0123",
      "barcode_format": "CODE128",
      "nickname": "家族用",
      "is_favorite": true,
      "display_order": 0,
      "last_used_at": "2026-05-13T12:30:00+09:00"
    },
    {
      "id": "01928a3f-...",
      "display_name": "近所のスーパー",
      "provider": null,
      "last4": "9876",
      "barcode_format": "CODE128",
      "nickname": null,
      "is_favorite": false,
      "display_order": 1,
      "last_used_at": null
    }
  ]
}
```

> **重要**: `barcode_value` および `memo` は一覧では**返さない**（漏洩リスク最小化）。`display_name` は一覧でも復号して返す（カード識別の一次情報のため）。`provider` フィールドは Nullable で、null の場合は頭文字アイコン + 自動カラーで描画する。詳細 API で初めて `barcode_value` / `memo` を復号して返す。

#### GET `/api/v1/point-cards/{id}`

**認可**: MEMBER（`currentUser.id == card.user_id` 必須）

**レスポンス（200 OK）**

```json
{
  "data": {
    "id": "01928a3e-...",
    "display_name": "東急ポイント",
    "provider": { /* プロバイダー情報フル、マッチしなかったカードは null */ },
    "barcode_value": "1234567890123",
    "barcode_format": "CODE128",
    "last4": "0123",
    "nickname": "家族用",
    "memo": "妻と共用",
    "is_favorite": true,
    "display_order": 0,
    "last_used_at": "2026-05-13T12:30:00+09:00",
    "created_at": "2026-05-01T10:00:00+09:00"
  }
}
```

> **IDOR 防御**: Repository 層では `findByIdAndUserId(id, userId)` のみ使用。Service 層も `currentUser.id == card.user_id` を二重チェック。他人のカードに到達できない。
> `provider` は Nullable（fuzzy match マッチがないか、プロバイダーが SET NULL で外れた場合は null）。

#### GET `/api/v1/point-cards/groups/{id}`

**認可**: MEMBER（自分のグループのみ）

**レスポンス（200 OK）**

```json
{
  "data": {
    "id": "01928a3e-...",
    "name": "東急ハンズ用",
    "emoji": "🛍️",
    "items": [
      {
        "card_id": "01928a3e-...",
        "display_order": 0,
        "display_name": "東急ポイント",
        "provider": {
          "code": "tokyu_point",
          "display_name": "東急ポイント",
          "logo_url": "...",
          "brand_color": "#E60012"
        },
        "barcode_value": "1234567890123",
        "barcode_format": "CODE128",
        "last4": "0123",
        "nickname": "家族用"
      },
      {
        "card_id": "01928a3f-...",
        "display_order": 1,
        "display_name": "近所のスーパー",
        "provider": null,
        "barcode_value": "9876543210987",
        "barcode_format": "CODE128",
        "last4": "0987",
        "nickname": null
      }
    ]
  }
}
```

**N+1 回避**: 実装は以下の 1 SQL で取得する（**案 B 改修: `provider_id` が NULL のカードも返すため LEFT JOIN に変更**）。

```java
@Query("""
    SELECT NEW com.mannschaft.app.pointcard.dto.GroupItemView(
        gi.cardId, gi.displayOrder,
        c.displayName, c.barcodeValue, c.barcodeFormat, c.last4, c.nickname,
        p.code, p.displayName, p.logoUrl, p.brandColor
    )
    FROM PointCardGroupItemEntity gi
    JOIN UserPointCardEntity c ON gi.cardId = c.id
    LEFT JOIN PointCardProviderEntity p ON c.providerId = p.id
    WHERE gi.groupId = :groupId
      AND c.userId = :userId
    ORDER BY gi.displayOrder ASC
    """)
List<GroupItemView> findGroupItemsJoined(@Param("groupId") UUID groupId, @Param("userId") Long userId);
```

> `provider_id` が NULL の自由入力カードでもグループ提示に含めるため `LEFT JOIN` を使用する。`p.code` 等は NULL になり、フロント側で `provider == null` 判定して頭文字アイコン描画に切り替える。
> JOIN FETCH 相当を JPQL コンストラクタ式で実現し、暗号化フィールド（`displayName` 等）は `EncryptedStringConverter` が個別行ロード時に透過的に復号する。グループ単位での監査ログ `POINT_CARD_VIEWED` は **Controller の入口で 1 件のみ記録**（カード件数を metadata に持つ。グループ全カード分の N 件記録は爆発防止のため行わない）。

#### POST `/api/v1/point-cards/{id}/used`

**認可**: MEMBER（自分のカードのみ）

**リクエストボディ**: 空

**副作用**

- `user_point_cards.last_used_at = NOW()` を更新
- 監査ログは**記録しない**（呼び出し頻度が高いため。`POINT_CARD_VIEWED` で代替）
- レートリミット 600 req/h（10 件/分）

**レスポンス（204 No Content）**

#### バリデーション上限・レートリミット一覧

| 操作 | 上限 / レート |
|---|---|
| カード総数 | 200 / user |
| グループ総数 | 50 / user |
| グループ内カード数 | 20 / group |
| カード作成 | 30 / hour |
| グループ作成 | 30 / hour |
| `/used` | 600 / hour（10 / min）|
| `/providers` 取得 | 60 / min |
| `/point-cards/{id}` 詳細取得 | 120 / min |

### 6.3 エラーコード

| コード | HTTP | メッセージ | 発生条件 |
|---|---|---|---|
| `POINT_CARD_001` | 403 | ウォレット機能が有効化されていません | `is_enabled=false` または規約未同意 |
| `POINT_CARD_002` | 400 | カード番号がプロバイダーの形式と一致しません | `card_number_regex` 不一致 |
| `POINT_CARD_003` | 409 | カード保有上限（200 枚）に達しています | 上限超過 |
| `POINT_CARD_004` | 409 | グループ作成上限（50 個）に達しています | 上限超過 |
| `POINT_CARD_005` | 409 | グループ内カード数上限（20 枚）に達しています | 上限超過 |
| `POINT_CARD_006` | 404 | カードが見つかりません | 他人のカード ID または存在しない ID |
| `POINT_CARD_007` | 404 | プロバイダーが見つかりません | `provider_id` 不正または `is_active=false` |
| `POINT_CARD_008` | 429 | レートリミット超過 | Bucket4j |
| `POINT_CARD_009` | 401 | 生体認証が必要です | `require_biometric_on_show=true` で WebAuthn 未通過 |

---

## 7. ビジネスロジック

### 7.1 オプトイン状態機械（規約同意フロー）

```
[初回アクセス] is_enabled=false, terms_accepted_at=null
    ↓ ユーザーが「ウォレットを有効化」をタップ
[規約 4 項目を全文スクロール + 個別チェック]
    ↓ 同意ボタン押下
PUT /settings { is_enabled: true, terms_version: "v1.0.0" }
    ↓
[有効化済み] is_enabled=true, terms_accepted_at=NOW(), terms_version="v1.0.0"
    ↓ ユーザーが「ウォレットを無効化」を選択（任意）
PUT /settings { is_enabled: false }
    ↓
[無効化済み — カード/グループはサーバー上に残るがアクセス不可]
```

> 無効化された状態でも `user_point_cards` レコードは物理削除しない（再度有効化すれば復元される）。完全削除したい場合は退会か個別カード削除 API を使う。

### 7.2 カード追加フロー（プリセット + 自由入力 / バーコード）

```
1. フロント: カード追加フォームを開く
   - 上部: プリセットボタン横並び（GET /providers の結果を描画）
   - 下部: 自由入力フォーム（display_name / barcode / format / memo）
2. ユーザー選択肢:
   a) プリセットタップ → display_name 事前充填 + provider_id 内部保持 + バーコード形式初期値セット
   b) 自由入力 → display_name を直接タイプ、provider_id は NULL のまま
3. バーコード入力（カメラ / 画像 / 手入力のいずれか）→ barcode_value + barcode_format 取得
4. ユーザーが確認 → POST /api/v1/point-cards
   - body: { display_name, provider_id (nullable), barcode_value, barcode_format, nickname, memo, is_favorite }
5. サーバー: display_name を §7.6 のルールで正規化
6. サーバー: provider_id が NULL の場合のみ、正規化済み display_name をメモリキャッシュに対して fuzzy match
   - マッチ → provider_id 補完
   - マッチせず → NULL のまま
7. サーバー: provider_id が解決していて、かつ provider.card_number_regex がある場合は参考バリデーション
8. サーバー: AES-256-GCM で display_name / barcode_value / nickname / memo を暗号化 → INSERT
9. last4 = barcode_value.slice(-4) を平文で別途格納
10. AuditLogService.record(POINT_CARD_CREATED,
       metadata={"provider_code": <code or null>, "provider_matched": <bool>, "card_id": "<uuid>"})
```

### 7.3 グループ提示モード（連続スワイプ）

```
1. ユーザーが「東急ハンズ用」グループをタップ
2. GET /api/v1/point-cards/groups/{id} で全カードを 1 回 SQL で取得
3. Controller 入口で AuditLogService.record(POINT_CARD_VIEWED,
       metadata={"group_id": "...", "card_count": 4})
4. クライアント: ProvideView 全画面遷移、Wake Lock 取得
5. 1 枚目バーコード描画（jsbarcode）
6. ユーザーがスワイプ → 2 枚目に切り替え（クライアント側のみで切り替え。サーバーアクセスなし）
7. 最終カードまで進めた後、「終了」で全画面解除、Wake Lock 解放
```

> グループ単位での監査ログ記録は 1 件で十分（カードごとに記録するとログ爆発する）。`POINT_CARD_VIEWED` の metadata に `card_count` を入れて「○ 枚を一括提示した」事実を記録する。

### 7.4 オフライン対応（IndexedDB 二重暗号化）

```
1. ユーザーが GET /api/v1/point-cards/groups/{id} を呼ぶたびに、
   レスポンスを IndexedDB に保存
2. 保存時に Web Crypto API（AES-GCM）で二重暗号化
   - 鍵はログイン時にメモリ上で生成（PBKDF2(userId + sessionSecret)）
   - ログアウト時に鍵を破棄 + IndexedDB の全レコードを削除
3. 保存期限 7 日（TTL）。期限切れレコードは取得時にスキップ
4. 圏外でも IndexedDB からカード詳細を取り出して提示可能
5. オンライン復帰時に再フェッチして上書き更新
```

> IndexedDB の鍵管理はメモリ上のみで揮発させる。端末紛失時にブラウザストレージが取り出されても、鍵がメモリにないため復号不可能（実用上の改ざん耐性は限定的だが、即時アクセス制限としては有効）。リモートワイプは v1 範囲外。

### 7.5 Phase 2 拡張時のシミュレーション

Phase 2 で organization が自店ポイントカードを発行する際の流れ:

1. ADMIN が `POST /api/v1/orgs/{orgId}/point-cards` を叩く
   ```json
   { "display_name": "サロン○○ ポイント",
     "category": "OTHER",
     "type": "SELF_ISSUED_STAMP",
     "logo_url": "...",
     "brand_color": "#FF6699" }
   ```
2. サーバーは `point_card_providers` に
   `organization_id=<orgId>, type='SELF_ISSUED_STAMP'`
   で INSERT。`code` は `org_<orgId>_<slug>` で自動生成。
3. ADMIN は「お客様用 QR コード」を取得（ディープリンク `mannschaft://wallet/add?providerId=<uuid>`）
4. 顧客がアプリで QR 読み取り → `POST /api/v1/point-cards` を内部的に呼ぶ
   （Phase 1 の API がそのまま動作する。`provider_id` が `SELF_ISSUED_STAMP` でも問題なく動く）
5. 店主がスタンプ押印 API（Phase 2 新規）を叩く
   ```
   POST /api/v1/orgs/{orgId}/point-cards/{cardId}/stamps
   { "delta": 1 }
   ```
   → `user_point_cards.stamp_count += 1`、`point_card_stamp_events` に履歴記録
6. 顧客側 UI: `stamp_count` の値を見て「○/10 個」表示

**Phase 1 設計が Phase 2 を破壊しないことの検証**:

| 項目 | Phase 1 状態 | Phase 2 で追加されるもの | 既存 Phase 1 データへの影響 |
|---|---|---|---|
| `point_card_providers.type` | `EXTERNAL` のみ | `SELF_ISSUED_STAMP` / `SELF_ISSUED_BALANCE` 値追加 | enum 値が増えるのみ。既存行は EXTERNAL のまま |
| `point_card_providers.organization_id` | 常に NULL | NOT NULL 制約は付けない（EXTERNAL 行があるため）。CHECK で type と整合 | Phase 1 行は NULL のまま |
| `user_point_cards.balance` / `stamp_count` | 常に NULL | Phase 2 で値が入る | Phase 1 行は NULL のまま |
| API `POST /point-cards` | EXTERNAL のみ受け入れ。**【案 B】**`display_name` 必須・`provider_id` 任意の現行 API シグネチャをそのまま流用 | SELF_ISSUED_* の `provider_id` をクライアント送信値として受け入れる（QR 経由のディープリンクで自動充填される） | 既存 API シグネチャ無変更 |
| 新規 API（スタンプ押印・残高チャージ）| 存在しない | Phase 2 で追加 | Phase 1 API 無変更 |

結論: **Phase 1 のスキーマ・API は Phase 2 で破壊的変更なし**。案 B の `provider_id` NULL 許容化はむしろ Phase 2 の自店発行カード（QR ディープリンクから `provider_id` 明示送信）にもクリーンに対応する。

### 7.6 fuzzy match 仕様（案 B 新章）

ユーザーが入力した `display_name` をサーバー側で正規化し、`point_card_providers` の運営マスタとマッチング判定する仕組み。プリセット 10〜15 社のロゴ・ブランドカラーを自由入力カードにも自動補強することが目的。

#### 7.6.1 正規化ステップ（順序固定）

1. **NFKC 正規化**: 全角英数記号 → 半角に統一（例: 「Ｄポイント」→「Dポイント」、「（株）」→「(株)」）
2. **カタカナ → ひらがな**: 同義表記の判定幅を広げる（例: 「ポイント」→「ぽいんと」、「ヨドバシ」→「よどばし」）
3. **記号・空白削除**: 半角/全角空白・ハイフン・ドット・スラッシュ・アンダースコア・括弧類などの装飾記号を全削除（例: 「D-Point」→「dpoint」、「D ポイント」→「dぽいんと」）
4. **lower 化**: ASCII 英字を小文字に統一（例: 「DPoint」→「dpoint」）

#### 7.6.2 マッチング対象

`point_card_providers` の以下 2 カラムに同じ正規化を適用し、両方を起動時にキャッシュに格納する:

- `code`（例: `tokyu_point` → 正規化後 `tokyupoint`）
- `display_name`（例: 「東急ポイント」→ 正規化後 `とうきゅうぽいんと`）

入力 `display_name` の正規化結果が、いずれかのキャッシュキーに **完全一致** すればマッチ成立とする。

> Phase 1 は完全一致のみ。編集距離（Levenshtein 等）や部分一致による曖昧マッチは Phase 1 では実装しない（誤マッチで意図しないブランドカラーが付くのは UX 悪化要因のため）。将来拡張は §16 未解決事項を参照。

#### 7.6.3 マッチ例

| ユーザー入力 | 正規化結果 | マッチ先 | 補強結果 |
|---|---|---|---|
| 「Ｄポイント」 | `dぽいんと` | provider.display_name 「dポイント」の正規化 `dぽいんと` | ✅ マッチ |
| 「dポイント」 | `dぽいんと` | 同上 | ✅ マッチ |
| 「D-Point」 | `dpoint` | provider.code 「dpoint」の正規化 `dpoint` | ✅ マッチ |
| 「ｄぽいんと」 | `dぽいんと` | provider.display_name の正規化 | ✅ マッチ |
| 「近所のスーパー」 | `近所のすーぱー` | マスタに無し | ❌ NULL のまま |
| 「とうきゅうハンズ」 | `とうきゅうはんず` | マスタは「東急ポイント」（`とうきゅうぽいんと`）のみ | ❌ NULL のまま（別事業者） |

#### 7.6.4 メモリキャッシュ戦略

```java
@Component
public class ProviderMatchCache {
    private volatile Map<String, ProviderEntity> normalizedIndex = Map.of();

    @PostConstruct
    public void init() { rebuild(); }

    public synchronized void rebuild() {
        List<ProviderEntity> all = providerRepository.findByIsActiveTrue();
        Map<String, ProviderEntity> idx = new HashMap<>();
        for (ProviderEntity p : all) {
            String nCode = normalize(p.getCode());
            String nName = normalize(p.getDisplayName());
            idx.putIfAbsent(nCode, p);
            idx.putIfAbsent(nName, p);
        }
        this.normalizedIndex = Map.copyOf(idx);
    }

    public Optional<ProviderEntity> match(String userInput) {
        return Optional.ofNullable(normalizedIndex.get(normalize(userInput)));
    }

    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        n = katakanaToHiragana(n);
        n = n.replaceAll("[\\s\\-\\.\\/_()\\[\\]{}「」『』【】〔〕（）]", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private static String katakanaToHiragana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x30A1 && c <= 0x30F6) sb.append((char)(c - 0x60));
            else sb.append(c);
        }
        return sb.toString();
    }
}
```

- **起動時 1 回ロード**: `@PostConstruct` で `is_active=true` のプロバイダーを全件読み込み、`Map<String, ProviderEntity>` を構築
- **キャッシュ更新**: SYSTEM_ADMIN がプロバイダーを追加・編集・停止した場合は `ApplicationEventPublisher` で `ProviderCacheRefreshEvent` を発火、`@EventListener` で `rebuild()` を呼ぶ
- **スレッド安全性**: `volatile` フィールドで読み取り側はロックフリー、書き込みは `synchronized` で全置換（読み取り 99.99% / 書き込み 0.01% のワークロードに最適化）
- **マスタ規模**: Phase 1 は 10〜15 社のため `Map` 全体で数十 KB 程度。メモリインパクトはゼロに近い

#### 7.6.5 クライアント送信 `provider_id` との優先順位

| クライアント送信 `provider_id` | サーバー fuzzy match 結果 | 最終 `provider_id` 値 |
|---|---|---|
| 値あり（プリセットボタン経由） | — | クライアント送信値を採用（fuzzy match は実行しない / 実行しても上書きしない）|
| NULL（自由入力） | マッチ | fuzzy match 結果を採用 |
| NULL（自由入力） | マッチなし | NULL のまま保存（自由入力カードとして確定）|

> ユーザーがプリセットボタンをタップした明示的な意思表示は最優先する。fuzzy match はあくまで「クライアントが何も指定してこなかった場合の補強」である。

Phase 2 で organization が自店ポイントカードを発行する際の流れ:

1. ADMIN が `POST /api/v1/orgs/{orgId}/point-cards` を叩く
   ```json
   { "display_name": "サロン○○ ポイント",
     "category": "OTHER",
     "type": "SELF_ISSUED_STAMP",
     "logo_url": "...",
     "brand_color": "#FF6699" }
   ```
2. サーバーは `point_card_providers` に
   `organization_id=<orgId>, type='SELF_ISSUED_STAMP'`
   で INSERT。`code` は `org_<orgId>_<slug>` で自動生成。
3. ADMIN は「お客様用 QR コード」を取得（ディープリンク `mannschaft://wallet/add?providerId=<uuid>`）
4. 顧客がアプリで QR 読み取り → `POST /api/v1/point-cards` を内部的に呼ぶ
   （Phase 1 の API がそのまま動作する。`provider_id` が `SELF_ISSUED_STAMP` でも問題なく動く）
5. 店主がスタンプ押印 API（Phase 2 新規）を叩く
   ```
   POST /api/v1/orgs/{orgId}/point-cards/{cardId}/stamps
   { "delta": 1 }
   ```
   → `user_point_cards.stamp_count += 1`、`point_card_stamp_events` に履歴記録
6. 顧客側 UI: `stamp_count` の値を見て「○/10 個」表示

**Phase 1 設計が Phase 2 を破壊しないことの検証**:

| 項目 | Phase 1 状態 | Phase 2 で追加されるもの | 既存 Phase 1 データへの影響 |
|---|---|---|---|
| `point_card_providers.type` | `EXTERNAL` のみ | `SELF_ISSUED_STAMP` / `SELF_ISSUED_BALANCE` 値追加 | enum 値が増えるのみ。既存行は EXTERNAL のまま |
| `point_card_providers.organization_id` | 常に NULL | NOT NULL 制約は付けない（EXTERNAL 行があるため）。CHECK で type と整合 | Phase 1 行は NULL のまま |
| `user_point_cards.balance` / `stamp_count` | 常に NULL | Phase 2 で値が入る | Phase 1 行は NULL のまま |
| API `POST /point-cards` | EXTERNAL のみ受け入れ | SELF_ISSUED_* の `provider_id` でも動作 | 既存 API シグネチャ無変更 |
| 新規 API（スタンプ押印・残高チャージ）| 存在しない | Phase 2 で追加 | Phase 1 API 無変更 |

結論: **Phase 1 のスキーマ・API は Phase 2 で破壊的変更なし**。

---

## 8. UI / UX 設計

### 8.1 ページ構成

```
app/pages/wallet/
├── index.vue                 # ウォレットホーム（カード一覧・お気に入り・グループ入口）
├── cards/
│   ├── new.vue               # カード追加フォーム（プリセット + 自由入力 + スキャン / 手入力）【案 B】
│   └── [id].vue              # カード詳細（編集・削除・提示）
├── groups/
│   ├── [id].vue              # グループ編集（メンバー追加・並び替え）
│   └── [id]/show.vue         # グループ提示モード（全画面スワイプ）
└── settings.vue              # 有効化トグル・規約同意・WebAuthn 設定
```

> `cards/new.vue` の役割（案 B 改修後）: 「プリセットボタン横並び」+ 「自由入力フォーム」を 1 画面に統合。プロバイダー選択用の中継ページ（旧案の `provider-picker` 相当）は廃止。

### 8.2 コンポーネント構成

```
app/components/wallet/
├── PresetCardButtons.vue       # 【案 B 新設】人気カードのプリセットボタン横スクロール（GET /providers の結果を描画）
├── BarcodeCapture.vue          # カメラスキャン（@zxing/browser）
├── BarcodePreview.vue          # バーコード描画（jsbarcode / qrcode）
├── CardTile.vue                # ウォレットホームの個別カードタイル（provider null 時は頭文字 + 自動カラー）
├── GroupTile.vue               # グループタイル
├── PresentationView.vue        # 全画面提示モード（Wake Lock + スワイプ）
└── TermsAcceptModal.vue        # 規約同意モーダル（4 項目・スクロール検知）
```

> **【案 B 改修】**`ProviderPicker.vue` は廃止。代わりに `PresetCardButtons.vue` をカード追加フォーム上部に常設し、タップで `display_name` 入力欄を事前充填する。プリセットに無い事業者はユーザーがフォーム下部で自由入力する。

`CardTile.vue` のレンダリングロジック（provider Nullable 対応）:

| 条件 | 描画内容 |
|---|---|
| `card.provider != null` | プロバイダーロゴ + ブランドカラー背景 + `display_name`（または `nickname` があればそちら）|
| `card.provider == null` | `display_name` の先頭 1 文字を大きく表示（頭文字アイコン）+ `display_name` ハッシュ由来の HSL 自動カラー背景 + `display_name` フルネーム |

### 8.3 採用ライブラリ

| ライブラリ | バージョン | 用途 |
|---|---|---|
| `jsbarcode` | `^3.x` | 1D バーコード描画（CODE128 / CODE39 / EAN13 / EAN8 / JAN13 / ITF）|
| `qrcode` | `^1.5.4` | QR コード描画（既存導入済み）|
| `@zxing/browser` | `^0.1.5` | カメラからのバーコード/QR 読み取り（既存導入済み）|
| `nosleep.js` | `^0.12.x` | Wake Lock API フォールバック（iOS Safari 等で Wake Lock API 未サポートのため）|

> **【案 B】文字列正規化**: フロント側のプレビュー（プリセット未タップ時の「これは ○○ ポイントとマッチします」表示）でも fuzzy match の正規化を行う場合は、標準 `String.prototype.normalize('NFKC')` + 手書きのカタカナ→ひらがな関数 + 正規表現の記号削除で十分（外部ライブラリ不要）。サーバー側（§7.6.4）と完全同一のロジックを TypeScript で複製するだけ。サーバーとフロントで規則がブレないよう、両者で同じ単体テストケース（§14）を共有する。
> PDF417 は `jsbarcode` 単体では対応しない。Phase 1 では事実上の利用例が少ないため後回し可。S1 で実装着手時に対応ライブラリを選定する。

### 8.4 提示モードの UX 要件

| 要件 | 実装 |
|---|---|
| 画面を暗くしない | Wake Lock API（Chrome / 一部 Safari）+ `nosleep.js` フォールバック |
| カード名（`display_name`）を大きく表示 | **【案 B】**バーコードの直下、`last4` と並んで `display_name` を最低 28 px のフォントサイズで表示。プロバイダーがマッチしないカードでもレジで「何のカードか」が一目で分かるようにする。プロバイダーがマッチしているカードはロゴが上に重畳表示される |
| バーコード下に大きい数字 | `last4` だけ大きく強調 + 全桁を 1 段下に小さく表示 |
| スワイプで前後カード切替 | `vue-touch` 系で左右スワイプ検知 |
| 連続提示の進捗表示 | 「2 / 4」のページインジケータ |
| スクリーンキャプチャ警告 | 提示モード入る前に「スクリーンショット撮影は OS の制約により完全には防げません。撮影しないでください」と注意モーダル |
| 戻る | 左上 × ボタン or システム戻るボタンで終了 |
| 提示終了で `last_used_at` 更新 | 全画面終了時に `POST /point-cards/{id}/used` を背景で送信 |

### 8.5 アクセシビリティ

- バーコード SVG に `aria-label` でカード番号を読み上げ可能化
- 高コントラスト: `brand_color` の WCAG AA コントラスト自動判定（背景色から文字色を黒/白に自動切替）
- **【案 B】プロバイダー未マッチカードの頭文字アイコン + 自動カラー**: `display_name` ハッシュから生成した HSL カラー（彩度 60% / 明度 50% 程度を基準）を背景に使い、その上に乗る文字色を WCAG AA コントラスト基準（4.5:1）に達するよう黒/白を自動選択する。プリセットカードの `brand_color` と同等のアクセシビリティを担保する
- カード番号は最低 32 px のフォントサイズで表示
- `display_name` は最低 28 px のフォントサイズで提示モードに表示
- スクリーンリーダー向けに「東急ポイント、カード番号 1234567890 0123、お気に入り」のような完全読み上げ対応。プロバイダー未マッチカードは「近所のスーパー、カード番号 ...」のように `display_name` のみで読み上げる
- 色覚多様性: プロバイダー識別はロゴ・名前で行い、`brand_color` のみに依存しない。自由入力カードも頭文字 + `display_name` で識別可能

### 8.6 ダークパターン回避

- 「すべて同意」ボタンを設けず、4 項目を個別チェック
- デフォルト未チェック・ボタン非活性は同意確認できるまで維持
- 「機能を無効化する」リンクを設定画面の上部に常設（隠さない）
- 規約変更時は再同意フローを強制（`terms_version` 不一致で `POINT_CARD_001` を返却）

### 8.7 i18n

- ロケールファイル `frontend/app/locales/{ja,en,zh,ko,es,de}/wallet.json` を新設
- 主要キー: `wallet.welcome` / `wallet.add_card` / `wallet.present` / `wallet.groups` / `wallet.terms.*` / `wallet.errors.*`
- 全 6 言語対応。Phase 1.0 は日本語完全 + 英語完全、他言語は日本語フォールバック
- Phase 1.1 で `point_card_providers.legal_notice` の多言語化対応

---

## 9. セキュリティ

### 9.1 脅威モデル

| 脅威 | 対策 |
|---|---|
| DB 侵害でカード番号流出 | AES-256-GCM at-rest 暗号化（`display_name` / `barcode_value` / `nickname` / `memo`）|
| 第三者の肩越し閲覧 | `require_biometric_on_show=true` で WebAuthn 再認証を提示モード起動前に要求 |
| 端末紛失 | リモートワイプは v1 範囲外。ログアウトで IndexedDB の鍵がメモリから消えるため即時アクセス不可になる |
| スクリーンショット流出 | OS 制約で完全抑止不可。利用前に警告表示と規約明記でカバー（ユーザー自己責任）|
| API で他人のカード取得（IDOR）| Service 入口で `currentUser.id == card.user_id` を必ず検証。Repository は `findByIdAndUserId` のみ使用 |
| SQL インジェクション | JPA の名前付きパラメータのみ使用。生 SQL 禁止 |
| MITM | TLS 強制（既存基盤） |
| バーコード偽装による詐欺利用 | ユーザー自己責任を規約 §9.2 に明記 |
| プロバイダー削除によるカード孤立 | **【案 B】**`provider_id` ON DELETE SET NULL でカード本体は維持し、自由入力扱いに戻る。ユーザー入力 `display_name` が残るためカードは引き続き提示可能 |
| Phase 2 で organization 退会時の自店プロバイダー残骸 | `OrganizationDeletedEvent` を購読し `is_active=0` に強制 + ユーザー通知 |
| 監査ログへの暗号化データ混入 | metadata には `provider_code`（マッチしないカードでは null）/ `provider_matched` / `card_id` のみ記録。`display_name` / `barcode_value` / `nickname` / `memo` は絶対に含めない |

### 9.2 規約（4 項目）

ユーザーがウォレット機能を初回有効化する際に、以下 4 項目を**全文スクロール + 個別チェック**で同意させる。同意時に `point_card_user_settings.terms_accepted_at` と `terms_version` を記録する。

1. **本機能は他社カードのバーコードを再描画して提示する補助ツールであり、各事業者の公式アプリではありません。**
2. **各ポイント事業者の利用規約・約款に従ってカードを利用する責任はユーザーにあります。**
3. **店舗側がアプリ表示のバーコードを受け付けない場合、本機能では何ら保証しません。**
4. **不正利用・カード番号漏洩について Mannschaft は責任を負いません。**

> 規約バージョンを更新した場合（`terms_version` 変更）、既存ユーザーには再同意フローを強制する。`terms_version` 不一致時は API 側で `POINT_CARD_001` を返却し、フロントは再同意画面に誘導する。

### 9.3 暗号化フィールドと検索可能性

| フィールド | 暗号化 | 検索可能 | 備考 |
|---|---|---|---|
| `display_name` | AES-256-GCM | **不可（INDEX 作らない）** | **【案 B 新設】**ユーザー入力カード名。fuzzy match はサーバー側でリクエスト受信時の平文に対して実行するため、保存値を検索する用途は無い |
| `barcode_value` | AES-256-GCM | 不可（INDEX 作らない）| カード番号で検索する機能を実装しない方針 |
| `nickname` | AES-256-GCM | 不可 | 「家族用」等の補助識別子。検索 API は提供しない |
| `memo` | AES-256-GCM | 不可 | 任意メモ |
| `last4` | 平文 | INDEX なし | 4 桁単独では特定不可、UI 識別用 |
| `provider_id` | 平文 | INDEX あり | プロバイダー単位カウント・参照用。NULL 許容 |

> Blind Index（HMAC-SHA256 でハッシュ化した検索用カラム）は作らない。本機能では検索ユースケースが薄く、攻撃面を減らすために最小実装に留める。

### 9.4 認可制御

- **入口で必ず `currentUser.id == card.user_id` を検証**: `PointCardService` の全メソッドで先頭に `requireOwner(cardId)` を呼ぶ
- Repository は `findByIdAndUserId(id, userId)` 系のみ使用。素の `findById(id)` を Service 層から呼ぶことを禁止（リンター or レビュアーで検知）
- グループ詳細 API も同様: `findByGroupIdAndUserId` を経由

### 9.5 レート制限（Bucket4j）

| エンドポイント | 制限 |
|---|---|
| `POST /point-cards` | 30 / hour |
| `POST /point-cards/groups` | 30 / hour |
| `POST /point-cards/{id}/used` | 600 / hour（10 / min）|
| `GET /point-cards/providers` | 60 / min |
| `GET /point-cards/{id}` | 120 / min |
| `PUT /settings` | 10 / hour |

### 9.6 提示モードの追加保護

- `require_biometric_on_show=true` の場合、`GET /point-cards/{id}` 呼び出し時に直前の WebAuthn 認証完了を確認（5 分以内）
- WebAuthn 未完了なら `POINT_CARD_009` を返却し、フロントは再認証画面に誘導
- グループ提示モードの場合は、提示モード入口で一度だけ WebAuthn 認証

---

## 10. GDPR / F12.3 連携

### 10.1 個人データカテゴリ追加

`UserPointCardEntity` に `@PersonalData(category = "point_cards")` を付与する。これにより `PersonalDataCoverageValidator` が起動時にカテゴリ網羅性を検証する。

```java
@PersonalData(category = "point_cards")
@Entity
@Table(name = "user_point_cards")
public class UserPointCardEntity extends UuidV7Entity { ... }
```

> `PointCardGroupEntity` / `PointCardGroupItemEntity` / `PointCardUserSettingsEntity` には個人氏名・連絡先のような直接的 PII はないが、ウォレット利用の事実自体が個人情報のため、エクスポート時には同時に出力する。`@PersonalData` は `UserPointCardEntity` のみに付与し、Collector で関連テーブルを join で集める実装とする。

### 10.2 エクスポート対象（F12.3 §3.2 へ追記）

| カテゴリキー | カテゴリ名 | データソース | ファイル名 |
|---|---|---|---|
| `point_cards` | ポイントカード | `user_point_cards` (**display_name**/barcode_value/nickname/memo を復号), `point_card_user_settings` | `point_cards.json` |
| `point_cards` | ポイントカードグループ | `point_card_groups`, `point_card_group_items` | `point_card_groups.json` |

> **【案 B】**`display_name` も AES-256-GCM 暗号化対象のため、エクスポート時には `EncryptedStringConverter` が復号した平文を JSON にそのまま出力する（追加の復号処理は不要）。プロバイダー未マッチカードは `provider_id` が null のまま出力される。

> `point_card_user_settings` は `point_cards.json` 内のオブジェクトとして同梱する（規約同意日時・WebAuthn 設定も含めて 1 ファイル）。

### 10.3 物理削除バッチへの追記（F12.3 §4.2 Phase 2 拡張）

退会後 30 日経過時の `AccountPurgeService.purgeUser()` で以下 4 テーブルが自動連鎖削除される（`users` ON DELETE CASCADE 設定済みのため明示削除は不要だが、設計書には明記する）:

```
Phase 2 追記:
  ├─ user_point_cards (user_id) → CASCADE
  ├─ point_card_groups (user_id) → CASCADE
  │  └─ point_card_group_items (group_id) → CASCADE（更に連鎖）
  └─ point_card_user_settings (user_id) → CASCADE
```

> `point_card_group_items.card_id → user_point_cards.id` も CASCADE のため、`user_point_cards` 削除時に同時に消える。**両親から CASCADE が来ても問題ない**（同じレコードを 2 回 DELETE しようとするわけではなく、最初の CASCADE で消えた後は対象がないため 2 度目の参照は no-op）。

### 10.4 PersonalDataCollector への分岐追加

```java
// PersonalDataCollector.java に追加
private String collectPointCards(Long userId) {
    List<UserPointCardEntity> cards = userPointCardRepository.findByUserId(userId);
    // EncryptedStringConverter が読み込み時に復号するため、そのまま JSON 化すれば平文で出力される
    // 【案 B】PointCardExportDto は displayName / barcodeValue / nickname / memo を全て含む。
    //         providerId は NULL 許容（自由入力カード）でそのまま出力する。
    PointCardUserSettingsEntity settings = pointCardUserSettingsRepository.findById(userId).orElse(null);
    return objectMapper.writeValueAsString(Map.of(
        "settings", settings,
        "cards", cards.stream().map(PointCardExportDto::from).toList()
    ));
}

private String collectPointCardGroups(Long userId) {
    List<PointCardGroupEntity> groups = pointCardGroupRepository.findByUserId(userId);
    return objectMapper.writeValueAsString(groups);
}

// CATEGORY_FILES に追加
Map.entry("point_cards", "point_cards.json"),
// 別カテゴリキーとして groups を持つか、point_cards.json に統合するかは実装時判断。
// 設計上は別ファイル `point_card_groups.json` として分離する（DB テーブル境界に揃える）。
```

---

## 11. 監査ログ / F10.3 連携

### 11.1 新規イベント種別（6 種）

| event_type | トリガー | metadata 例 |
|---|---|---|
| `POINT_CARD_CREATED` | `POST /point-cards` 成功時 | マッチ時: `{"provider_code": "tokyu_point", "provider_matched": true, "card_id": "01928a3e-..."}` ／ マッチなし（自由入力）: `{"provider_code": null, "provider_matched": false, "card_id": "01928a3e-..."}` |
| `POINT_CARD_DELETED` | `DELETE /point-cards/{id}` 成功時 | `{"provider_code": "tokyu_point", "provider_matched": true, "card_id": "01928a3e-..."}` または `{"provider_code": null, "provider_matched": false, "card_id": "01928a3e-..."}` |
| `POINT_CARD_VIEWED` | グループ提示モード開始時のみ（個別カード提示は記録しない）| `{"group_id": "01928a3e-...", "card_count": 4}` |
| `POINT_CARD_GROUP_CREATED` | `POST /point-cards/groups` 成功時 | `{"group_id": "01928a3e-...", "card_count": 0}` |
| `POINT_CARD_GROUP_DELETED` | `DELETE /point-cards/groups/{id}` 成功時 | `{"group_id": "01928a3e-..."}` |
| `POINT_CARD_SETTINGS_UPDATED` | `PUT /point-cards/settings` 成功時 | `{"is_enabled": true, "terms_version": "v1.0.0"}` |

> **【案 B】**`provider_code` は fuzzy match の結果に依存して null になりうる。マッチの有無を `provider_matched` ブール値で明示する。これにより「自由入力カードの登録比率」や「fuzzy match の効果」を集計可能になる（運営マスタ拡充判断の材料）。

### 11.2 イベントカテゴリ

新規カテゴリ `POINT_CARD` を `AuditEventCategory` enum に追加し、上記 6 イベントを所属させる。

### 11.3 metadata 取り扱い注意（重要）

- **絶対に含めない**: `display_name` / `barcode_value` / `nickname` / `memo`（暗号化対象データ）、`last4` も含めない（識別力があるため）
- **含めてよい**: `provider_code`（プロバイダー識別子、PII ではない。**マッチしない自由入力カードでは null**）、`provider_matched`（マッチ有無のブール、運用分析用）、`card_id`（UUIDv7、それ単体では特定不可）、`group_id`、`card_count`、`is_enabled`、`terms_version`
- 個別カード提示（`/point-cards/{id}` 詳細取得）は `POINT_CARD_VIEWED` を記録**しない**。理由は呼び出し頻度が高く、監査ログ爆発を招くため。グループ提示モードという「意図的な提示行為」だけを記録する

### 11.4 保持期間

既存の監査ログ基盤（F10.3）の標準保持期間（2 年）に従う。F18 専用の長期保持は不要（個人ウォレットの調査ニーズは 2 年で十分カバーされる）。

### 11.5 参照方針

- SYSTEM_ADMIN は `GET /admin/audit-logs?event_category=POINT_CARD` で全件参照可能
- 一般ユーザーは `GET /users/me/audit-logs?event_category=POINT_CARD` で自分の操作履歴を参照可能（Phase 3 以降）
- ADMIN（organization）スコープのログ参照: Phase 1 では POINT_CARD イベントは organization_id を持たない（個人スコープのため）。Phase 2 で `POINT_CARD_STAMP_ISSUED` 等を追加する際に organization_id を埋める

---

## 12. Phase 2 拡張シミュレーション（自店発行カード）

§7.5 と重複するが、Phase 2 の全体像を明示する。

> **【案 B との関係】**Phase 1 のマスタ縮小（10〜15 社）と `provider_id` NULL 許容化は、Phase 2 の organization 自店発行カード（QR ディープリンクで `provider_id` を明示送信）に対しても**完全無影響**である。自店発行カードは「クライアントが `provider_id` を明示送信する」流れであり、Phase 1 の自由入力カードフロー（`provider_id` NULL のまま保存）とは別経路で動作する。Phase 1 改修は Phase 2 設計に何ら制約を加えない。

### 12.1 Phase 2 で追加されるテーブル概要

```sql
-- スタンプ履歴
CREATE TABLE point_card_stamp_events (
    id CHAR(36) NOT NULL,
    card_id CHAR(36) NOT NULL,
    delta INT NOT NULL,              -- 通常 +1、訂正時 -1 等
    pressed_by_user_id BIGINT UNSIGNED NOT NULL,  -- 店主・ADMIN
    pressed_at DATETIME(6) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    note VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_pcse_card (card_id, pressed_at DESC),
    INDEX idx_pcse_org (organization_id, pressed_at DESC),
    INDEX idx_pcse_presser (pressed_by_user_id)
    -- FK は user_point_cards.id にのみ張る（CASCADE）
);

-- チャージ・引き落とし履歴
CREATE TABLE point_card_balance_events (
    id CHAR(36) NOT NULL,
    card_id CHAR(36) NOT NULL,
    delta DECIMAL(12,2) NOT NULL,    -- 正なら入金、負なら出金
    operation_type VARCHAR(30) NOT NULL,  -- CHARGE / PURCHASE / REFUND
    operated_by_user_id BIGINT UNSIGNED NOT NULL,
    operated_at DATETIME(6) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    note VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_pcbe_card (card_id, operated_at DESC),
    INDEX idx_pcbe_org (organization_id, operated_at DESC)
);
```

### 12.2 Phase 2 追加 API

| メソッド | パス | 認可 | 用途 |
|---|---|---|---|
| GET | `/api/v1/orgs/{orgId}/point-cards/providers` | ADMIN | 自組織の自店プロバイダー一覧 |
| POST | `/api/v1/orgs/{orgId}/point-cards/providers` | ADMIN | 自店プロバイダー新規発行 |
| PATCH | `/api/v1/orgs/{orgId}/point-cards/providers/{id}` | ADMIN | プロバイダー編集 |
| DELETE | `/api/v1/orgs/{orgId}/point-cards/providers/{id}` | ADMIN | プロバイダー停止（is_active=false） |
| GET | `/api/v1/orgs/{orgId}/point-cards/providers/{id}/qr` | ADMIN | 顧客追加用 QR コード生成 |
| POST | `/api/v1/orgs/{orgId}/point-cards/{cardId}/stamps` | ADMIN/DEPUTY_ADMIN | スタンプ押印（+1） |
| POST | `/api/v1/orgs/{orgId}/point-cards/{cardId}/balance-events` | ADMIN/DEPUTY_ADMIN | チャージ・引き落とし |

### 12.3 organization 退会時の自店プロバイダー処理

Phase 2 では organization が退会する際の自店プロバイダーの扱いを以下とする:

1. `OrganizationDeletedEvent`（既存基盤）を購読
2. 該当 `organization_id` の `point_card_providers` を `is_active=0` に強制更新
3. 当該プロバイダーを `provider_id` として持つすべての `user_point_cards` のユーザーに通知（プッシュ通知 + メール）「○○ 店のポイントカードは店舗閉店により利用停止になりました。残ポイントは紙の控えに記録するなどして店舗にお問い合わせください」
4. カードレコード自体は削除しない（ユーザーのウォレットには「閉店」ラベル付きで残す）
5. クロスドメイン CASCADE は禁止のため、`organization_id` FK は引き続き張らない

### 12.4 Phase 1 → Phase 2 スキーマ移行の差分

| 変更箇所 | Phase 1 → Phase 2 移行内容 |
|---|---|
| `point_card_providers.type` | enum 値追加（`SELF_ISSUED_STAMP` / `SELF_ISSUED_BALANCE`）。既存行に影響なし |
| `point_card_providers.organization_id` | Phase 1 から NULL 許容で存在。Phase 2 で初めて値が入る |
| `user_point_cards.balance` / `stamp_count` | Phase 1 から NULL 許容で存在。Phase 2 で初めて値が入る |
| `point_card_stamp_events` / `point_card_balance_events` | Phase 2 で新設 |
| API | Phase 1 API は無変更、Phase 2 で `/orgs/{orgId}/point-cards/*` を追加 |
| Flyway | Phase 2 用に V9.143 以降を予約 |

**検算**: Phase 1 設計のテーブル定義のままで Phase 2 の自店発行が実現できる。Phase 1 リリース後に「スキーマを直さないと Phase 2 に進めない」事態は発生しない。

---

## 13. Flyway マイグレーション

### 13.1 マイグレーションファイル一覧（Phase 1）

```
V9.136__create_point_card_providers.sql
V9.137__create_user_point_cards.sql
V9.138__create_point_card_groups.sql
V9.139__create_point_card_group_items.sql
V9.140__create_point_card_user_settings.sql
V9.141__seed_point_card_providers_phase1.sql
V9.142__add_point_card_audit_event_types.sql
```

> 連番 V9.136〜V9.142 は**仮予約**。実装着手時に Phase 9 系の最新番号を確認し、衝突する場合は +1 ずつシフトする。

> **【案 B】**`V9.137__create_user_point_cards.sql` には以下のカラムが含まれる:
> - `display_name VARBINARY(1024) NOT NULL`（**案 B 新設**、AES-256-GCM 暗号化対象）
> - `provider_id CHAR(36) NULL`（**案 B 変更**、FK は `ON DELETE SET NULL`）
> - 他カラムは §5.2 の通り

### 13.2 V9.141 シード（Phase 1 リリース対象プロバイダー例）

**【案 B】**Seed 件数を「**人気 10 社程度**」に縮小する（最終リストは Phase 1 リリース時に運営が確定）。残り約 40 社規模のマスタ拡充は本機能の必須要件ではなく、運営判断で随時 INSERT する運用とする。プリセットに無い事業者はユーザーが自由入力で登録するため、マスタ縮小によるユーザー機能への影響はない。設計上の代表サンプル:

```sql
INSERT INTO point_card_providers
  (id, code, display_name, category, type, brand_color, default_barcode_format, is_active, created_at, updated_at)
VALUES
  (UUID_TO_BIN(UUID(), 1), 'tokyu_point',  '東急ポイント',                 'RETAIL',      'EXTERNAL', '#E60012', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'dpoint',       'dポイント',                    'OTHER',       'EXTERNAL', '#CC0000', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'rakuten',      '楽天ポイント',                 'OTHER',       'EXTERNAL', '#BF0000', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'paypay',       'PayPayポイント',               'OTHER',       'EXTERNAL', '#FF0033', 'QR',      1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'tpoint',       'Vポイント',                    'OTHER',       'EXTERNAL', '#1E88E5', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'ponta',        'Pontaポイント',                'OTHER',       'EXTERNAL', '#F39800', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'yodobashi',    'ヨドバシゴールドポイント',     'RETAIL',      'EXTERNAL', '#000000', 'EAN13',   1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'biccamera',    'ビックカメラポイント',         'RETAIL',      'EXTERNAL', '#D31C24', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'tsutaya',      'TSUTAYA',                      'RETAIL',      'EXTERNAL', '#003D78', 'CODE128', 1, NOW(6), NOW(6)),
  (UUID_TO_BIN(UUID(), 1), 'matsukiyo',    'マツモトキヨシ',               'RETAIL',      'EXTERNAL', '#FAB237', 'CODE128', 1, NOW(6), NOW(6));
  -- 【案 B】Phase 1 はこの 10 社規模でリリース。追加は運営マスタ管理（is_active トグル + 必要に応じた INSERT）。
  -- ユーザーがプリセット外の事業者を使う場合は自由入力で登録する設計のため、Seed 件数を増やす必要はない。
```

> 上記の `UUID_TO_BIN(UUID(), 1)` は MySQL 8 の UUIDv1→BIN(16) 変換。実装時は **UUIDv7** を CHAR(36) で `gen_random_uuid()` 相当（または Java 側で UuidV7Util を呼んで PREPARED で INSERT）に置き換える。シード用 SQL は決定論性のため固定 UUID 文字列をハードコードする方針でも可。

### 13.3 V9.142 — AuditEventType enum 値追加

監査ログ基盤側で `event_type` は VARCHAR(100) のため DDL 変更は不要。アプリ層の `AuditEventType` enum 値追加と、`AuditEventCategory.POINT_CARD` の追加を反映するためのマイグレーションだが、実体は Seed/コード変更のみのため**空の Flyway（または管理上のマーカー）**として扱ってもよい。実装時の整理に委ねる。

### 13.4 マイグレーション上の注意

- `users` テーブルが先に存在していること（FK 依存）
- `EncryptedStringConverter` のキーバージョンは Phase 1 時点で **v1** を使用。鍵ローテーション時の再暗号化バッチは別タスク（既存基盤に従う）
- Phase 2 用カラム（`organization_id` / `balance` / `stamp_count`）は Phase 1 から最初に存在させる（後付け ALTER は技術的負債）

---

## 14. テスト戦略

| テスト | 対象 | 検証内容 |
|---|---|---|
| `PointCardProviderRepositoryTest` | リポジトリ | カテゴリ別検索、`is_active=true` フィルタ、`type='EXTERNAL'` フィルタ |
| `UserPointCardRepositoryTest` | リポジトリ | `findByIdAndUserId` の IDOR 防御、暗号化フィールドの保存/復号、ソート順、上限カウント |
| `PointCardServiceTest` | サービス | 認可（他人カードへのアクセスで NotFound）、上限超過、`card_number_regex` 検証、規約未同意で 403 |
| `PointCardControllerTest` | コントローラ | 全 14 エンドポイントの認可・バリデーション・レスポンス形式 |
| `PointCardGroupServiceTest` | グループ操作 | 1 SQL での JOIN FETCH 取得（N+1 防止）、グループ内カード数上限、所有者検証 |
| `PointCardEncryptionTest` | 暗号化 | `EncryptedStringConverter` が **`display_name`** / `barcode_value` / `nickname` / `memo` を透過的に暗号化・復号、`last4` は平文 |
| `PointCardAuditLogTest` | 監査ログ | 6 イベント種別が正しく発火、metadata に暗号化対象が含まれない、`POINT_CARD_CREATED` の metadata に `provider_matched` ブールと `provider_code` の Nullable 動作（マッチ時/未マッチ時両方）が正しく入る、グループ提示で `card_count` が正しく入る |
| `ProviderMatchCacheTest` | **【案 B 新設】**fuzzy match | 正規化規則（NFKC + カタカナ→ひらがな + 記号削除 + lower）が個別ステップで正しく動作、「Ｄポイント」「dポイント」「D-Point」「ｄぽいんと」が同じ provider にマッチ、マッチしない文字列で `Optional.empty()` が返る、`rebuild()` 後にキャッシュが更新される（イベントリスナー経由）|
| `PointCardCreateProviderResolutionTest` | **【案 B 新設】**カード作成時の provider 解決 | (1) クライアントが `provider_id` 明示送信 → その値が採用される、(2) 自由入力で fuzzy match マッチ → `provider_id` 自動補完される、(3) 自由入力でマッチなし → `provider_id` は NULL のまま保存、(4) プリセットボタン経由でフォーム事前充填されたケース（`display_name` がマスタ名と完全一致）の動作確認 |
| `PointCardProviderDeletionTest` | **【案 B 新設】**プロバイダー削除時の挙動 | プロバイダー削除（または `is_active=0`）後、関連する既存 `user_point_cards.provider_id` が SET NULL で外れること（FK 動作確認）、カード自体は残ること、`display_name` が暗号化保存されたまま読み出せること |
| `PresetCardButtonsTest` (Vitest) | **【案 B 新設】**フロント | プリセットタップで `display_name` 入力欄が事前充填される、`provider_id` がコンポーネント内部状態に保持される、バーコード形式の初期値がプロバイダーの `default_barcode_format` に切り替わる |
| `CardTileFallbackTest` (Vitest) | **【案 B 新設】**フロント | `provider == null` のカードで頭文字アイコン + `display_name` ハッシュ由来の HSL 自動カラーが描画される、WCAG AA コントラスト基準（4.5:1）を満たす文字色が選択される |
| `PointCardSettingsServiceTest` | 設定 | 規約同意フロー、`terms_version` 不一致で 403、`require_biometric_on_show` 動作 |
| `PointCardGdprIntegrationTest` | GDPR 連携 | `PersonalDataCollector` が `point_cards.json` / `point_card_groups.json` を生成、退会時の CASCADE 削除 |
| `PointCardE2eTest` (Playwright) | UI | オンボーディング規約同意、カード追加（手入力）、グループ提示モード（スワイプ + Wake Lock）、オフライン IndexedDB 取得 |

---

## 15. 実装フェーズ

### S1: DB 基盤 + Repository 共通基底

- 5 テーブル DDL（V9.136〜V9.140）。**【案 B】`user_point_cards.display_name VARBINARY(1024) NOT NULL` カラム追加、`provider_id` を NULL 許容 + `ON DELETE SET NULL` で定義**
- Seed プロバイダー **【案 B】人気 10 社程度に縮小**（V9.141、リリース時に最終確定）
- `UuidV7Entity` 継承 Entity 4 種 + `point_card_user_settings` Entity（user_id PK）
- `AbstractUserOwnedRepository` 基底インターフェース新設（既存に同等品がなければ）
- `EncryptedStringConverter` 適用確認（`display_name` 含む 4 フィールド）

### S2: コア CRUD API

- `PointCardProviderService` + Controller（`GET /providers`、**プリセット提示用**として動作）
- `PointCardService` + Controller（カード CRUD 全 7 本）
- **【案 B】`PointCardService.matchProvider(displayName)` 実装**（§7.6 fuzzy match ロジック）
- **【案 B】`ProviderMatchCache` 実装**（`@PostConstruct` + `ProviderCacheRefreshEvent` 購読）
- `PointCardSettingsService` + Controller（settings 2 本）
- Bucket4j レート制限設定

### S3: グループ機能 + JOIN FETCH 最適化

- `PointCardGroupService` + Controller（グループ CRUD 4 本）
- グループ詳細の 1-SQL 取得（JPQL コンストラクタ式、**【案 B】`LEFT JOIN` で provider Nullable 対応**）
- 提示モード `POINT_CARD_VIEWED` 監査ログ発火

### S4: フロントエンド（カード CRUD・グループ）

- ページ `wallet/index.vue` / `cards/new.vue`（**【案 B】プリセット + 自由入力フォーム統合**）/ `cards/[id].vue` / `groups/[id].vue` / `settings.vue`
- コンポーネント **【案 B】`PresetCardButtons.vue` 新設**（`ProviderPicker.vue` は廃止）/ `BarcodeCapture` / `BarcodePreview` / `CardTile`（provider Nullable 対応の頭文字アイコンフォールバック）/ `GroupTile` / `TermsAcceptModal`
- i18n 6 言語の `wallet.json`

### S5: 提示モード（Wake Lock・スワイプ・WebAuthn）

- `PresentationView.vue` 全画面 UI
- Wake Lock API + `nosleep.js` フォールバック
- スクリーンキャプチャ警告モーダル
- WebAuthn 再認証（`require_biometric_on_show=true` 時）
- iOS Safari 実機での Wake Lock 挙動確認

### S6: オフライン対応（IndexedDB 二重暗号化）

- Web Crypto API での AES-GCM 二重暗号化
- 7 日 TTL
- ログアウト時の鍵破棄 + IndexedDB クリア
- 圏外時のフォールバック描画

### S7: GDPR + 監査ログ統合

- `@PersonalData("point_cards")` 付与
- `PersonalDataCollector` に分岐追加（`point_cards.json` / `point_card_groups.json`）
- F12.3 設計書追記（§3.2 カテゴリ追加、§4.2 物理削除バッチ追記）
- `AuditEventType` enum + `AuditEventCategory.POINT_CARD` 追加
- F10.3 設計書追記（イベント 6 種・metadata 注意事項）

### S8: テスト + 仕上げ

- ユニットテスト全 10 種
- E2E テスト（Playwright）
- アクセシビリティ監査（WCAG AA）
- 🟢 ステータス更新

**クリティカルパス**: S1 → S2 → S3 → S4 → S5 → S7 → S8。S6（オフライン）と S7 は並行可能。

### Phase 1 実装結果（2026-05-15 完了）

実装は当初計画 S1〜S8 を分割・並行化して下記 6 陣で完遂した。S2 を 3 分割（Provider/Card/Audit）、S4 を 3 分割（Shell/Pages/Offline）、S7（GDPR + 監査ログ）は S2-C・S3 に統合する形で消化した。

| 陣 | 内容 | 完了 PR | コミット |
|---|---|---|---|
| S1 基盤 | DDL 5 本（V9.136〜V9.140）+ Seed 10 社（V9.141）+ enum 3 種 + UserOwnedRepository | #630 | 2cf561bfb |
| S2-A Provider/Settings + ProviderMatch | Provider/Settings ドメイン + fuzzy match（NFKC + ひらがな化 + 記号削除 + lower）+ ProviderMatchCache | #632 | 77a46b55b |
| S2-B Card CRUD | UserPointCard CRUD 7 本 + AES-256-GCM 暗号化 + 認可（UserOwnedRepository）+ 監査ログ統合 | #642 | 3afa90aca |
| S2-C Audit + GDPR enum | AuditEventCategory.POINT_CARD + 6 イベント種別 + PersonalDataCollector スケルトン | #633 | 749da25d4 |
| S3 Groups + ErrorCode 整合 + GDPR 本実装 | PointCardGroup CRUD 4 本 + JPQL コンストラクタ式 + LEFT JOIN（provider Nullable）+ PersonalDataCollector 本実装（`point_cards.json` / `point_card_groups.json`）+ ErrorCode §6.3 整合化 | #643 | 50222c1c7 |
| S4-A Wallet Shell | 型定義 + API クライアント + `wallet/index.vue` + `settings.vue` + i18n 6 言語 `wallet.json` | #646 | c2e629241 |
| S4-B Card/Group ページ + Barcode | `cards/new.vue` / `cards/[id].vue` / `groups/[id].vue` + `PresetCardButtons.vue` + `BarcodeCapture` + `BarcodePreview` + `CardTile`（頭文字アイコンフォールバック）+ `TermsAcceptModal` | #649 | f22fa1c32 |
| S4-C オフライン対応 | IndexedDB + Web Crypto API AES-GCM 二重暗号化 + 7 日 TTL + ログアウト時鍵破棄 + 圏外時フォールバック描画 | #650 | 795f4e51c |
| S5 提示モード | `PresentationView.vue` + Wake Lock API + nosleep.js 動的 import フォールバック + フルスクリーン + 横スワイプ + WebAuthn 再認証（クライアントジェスチャ）+ スクリーンキャプチャ警告モーダル | #653 | dc34be095 |

設計書追加 PR は #627（コミット 7d2df5feb）。Phase 1 全 10 PR が main マージ済み。

### Phase 2 実装結果（2026-05-16 完了 — スタンプ型）

Phase 2 は SELF_ISSUED_STAMP に絞って先行実装、計 6 陣で完遂。Phase 1 残課題 🔴 WebAuthn サーバー側 5 分 TTL 検証も P2-S2A で完全実装した。残高型 (SELF_ISSUED_BALANCE) は Phase 3 で対応。

| 陣 | 内容 | 完了 PR | コミット |
|---|---|---|---|
| P2-S1 基盤 | V9.142 `point_card_stamp_events` DDL + Entity + Repository + AuditEventType 4 値 + OrganizationDeletedEvent listener（@TransactionalEventListener AFTER_COMMIT で is_active=0 + ProviderCacheRefreshEvent 発火）| #660 | 3cdad7bc5 |
| P2-S2A WebAuthn 再認証 | POINT_CARD_009 完全実装。`reauthenticate-begin` / `-complete` API + Valkey 5 分 TTL フラグ + `PointCardGroupService.startPresentation` ゲート + フロント 3 段フロー（begin → credentials.get → complete） | #669 | d067810da |
| P2-S2B Provider CRUD | 自店プロバイダー CRUD（POST/PATCH/DELETE + 顧客 QR）/ type=SELF_ISSUED_STAMP 固定 / ADMIN+DEPUTY_ADMIN で発行・編集、ADMIN only で停止 / 20 個上限 / ProviderCacheRefreshEvent 連動 | #665 | 715e5dd4f |
| P2-S2C Stamp API | スタンプ押印 + 履歴 API（7 段検証: 認可/カード存在/provider 紐付け/org 所有/type=STAMP/active/delta 妥当） / @Transactional で stamp_count 更新 + stamp_event 挿入を不可分 / 下限 0 ガード / 監査ログ + organization_id 軸の証拠ログ / ErrorCode 010〜014 | #666 | 745e5785e |
| P2-S3A Org Dashboard | 店主ダッシュボード Frontend（プロバイダー管理 + 押印画面 + 履歴一覧 + 顧客 QR モーダル）/ qrcode SVG 描画 / 押印画面はカード ID UUID 直接入力 MVP（Phase 3 で QR 自動特定） | #676 | 15eeada04 |
| P2-S3B Customer QR | 顧客 QR 追加フロー（`/wallet/add-from-qr`）+ クエリ事前充填 + カード ID コピー UX | #677 | af079d8fa |

Phase 2 全 6 PR + 第四陣（E2E + ドキュメント）が main マージ済み。

### Phase 3 実装結果（2026-05-16 完了 — 残高型 + QR 自動特定 + Wake Lock テレメトリ）

Phase 3 は残高型 (SELF_ISSUED_BALANCE)・押印画面の QR 自動特定（顧客一時トークン方式）・Wake Lock 実機テレメトリ強化の 3 系統を並行で消化した。計 7 陣（本陣 = 第四陣 = 仕上げ）で完遂。Phase 1 から先行投入していた `user_point_cards.balance` カラムと Phase 2 で導入された `SELF_ISSUED_BALANCE` ENUM 値が破壊なく活用された。

| 陣 | 内容 | 完了 PR | コミット |
|---|---|---|---|
| P3-S1 基盤 | V9.148 `point_card_balance_events` DDL + Entity + Repository + AuditEvent 3 値（CHARGED/SPENT/REFUNDED）+ ErrorCode 4 値（015-018） | #687 | 08e7f318b |
| P3-S2A QR 自動特定 | 顧客一時トークン 5 分 TTL（Valkey GETDEL）+ `POST /resolve-by-token` + `POST /share-token` + ErrorCode 019（TOKEN_NOT_FOUND） | #691 | 1b032989e |
| P3-S2B 残高 API | CHARGE/SPENT/REFUND `POST /balance-events` + 元 event 引用 REFUND（refundOfEventId）+ 累計返金額超過ガード ErrorCode 020 + @Transactional 不可分（balance 更新 + event 挿入）| #692 | bf690ba47 |
| P3-S2C Wake Lock テレメトリ | `useWakeLock` に `useErrorReport.captureQuiet` 統合（context 分類: `permission_denied` / `release_failed` / `nosleep_fallback_failed`）+ iOS 実機検証 checksheet（`docs/operations/F18_ios_wake_lock_checksheet.md`） | #693 | 1f453e8b5 |
| P3-S3A 顧客側 FE | 残高/スタンプ表示 UI + 提示 QR 生成モーダル（`ShareTokenQrModal.vue`）+ TTL 残時間カウントダウン + 5 分超過時の再発行ボタン | #701 | f37284e29 |
| P3-S3B 店主側 FE | 押印画面 QR 自動特定 BarcodeCapture 統合 + 残高型 4 タブ（押印 / チャージ / 利用 / 返金）+ 履歴 2 タブ化（スタンプ / 残高） + 元 SPENT 引用返金 UI | #702 | cb68c349e |
| P3-S4 第四陣（仕上げ） | Backend DTO 拡張（`UserPointCardListItemResponse` / `DetailResponse` に balance / stampCount / providerType / providerOrganizationId 追加 + Service の provider フェッチ修正）+ E2E `wallet-org-balance.spec.ts` 新規（チャージ → 利用 → 返金）+ 設計書 §1 / §15 / §16 / §17 最終化 + memory 更新 | (本 PR) | - |

---

## 16. 未解決事項

### 解決済み（Phase 1 + Phase 2 + Phase 3 実装で確定）

| 項目 | 解決内容 |
|---|---|
| ✅ Flyway 連番 V9.136〜V9.142 | V9.136〜V9.141 + V9.142 stamp_events で確定 |
| ✅ Phase 1 リリース対象プロバイダーの最終リスト | 人気 10 社で確定（V9.141 Seed） |
| ✅ nosleep.js vs Wake Lock API のフォールバック実装 | S5 で動的 import によるフォールバック完了 |
| ✅ ErrorCode 番号体系 | §6.3 通り 001〜014 で整合化完了 |
| ✅ WebAuthn サーバー側 5 分 TTL 検証（POINT_CARD_009 完全実装） | P2-S2A で `reauthenticate-begin/-complete` + Valkey フラグ + startPresentation ゲートを完全実装。AT/RT は再発行せず、consumeReauthentication で 1 回限り使用（再生攻撃防止） |
| ✅ 自店発行プロバイダー CRUD | P2-S2B で完了（20 個上限 / ADMIN+DEPUTY_ADMIN 委任 / ProviderCacheRefreshEvent 連動） |
| ✅ スタンプ押印 + 証拠ログ | P2-S2C で完了（@Transactional 不可分 / 監査ログ + 履歴テーブル二段の証拠保全） |
| ✅ 店主ダッシュボード | P2-S3A で完了（プロバイダー管理 + 押印 + 履歴 + 顧客 QR） |
| ✅ 顧客 QR 追加フロー | P2-S3B で完了 |
| ✅ OrganizationDeletedEvent 購読 | P2-S1 listener で自店プロバイダー自動停止 + ProviderCacheRefreshEvent 発火 |
| ✅ 残高型カード (SELF_ISSUED_BALANCE) | Phase 3 P3-S1 + P3-S2B 完了。CHARGE/SPENT/REFUND API + 元 event 引用返金 + 累計返金額超過ガード（POINT_CARD_020）+ Phase 1 から先行投入していた `user_point_cards.balance` カラムを破壊なく活用 |
| ✅ 押印画面の QR 自動特定 | Phase 3 P3-S2A + P3-S3B 完了。顧客側で 5 分 TTL の一時トークン UUID を発行 → Valkey GETDEL で atomic 消費（再生防止）→ 店主側 BarcodeCapture で読取して `resolveByToken` で cardId を特定。Blind Index 案より暗号化されたバーコード値の検索不能性を維持できる方式に落ち着いた |
| ✅ 顧客向け provider 公開 API | Phase 3 で別解採用。QR URL（`/wallet/share?token=`）にトークンを埋め込む形式で代替し、公開 API 自体は作らない方針で完結。providerId・displayName は QR resolve 結果に含めて返す |
| ✅ Wake Lock テレメトリ | Phase 3 P3-S2C 完了。`useWakeLock` から `useErrorReport.captureQuiet` でテレメトリ送信（context 分類で iOS Safari 制約の集計が可能）+ iOS 17/18 実機検証 checksheet 整備 |

### 未解決のまま（Phase 4 / 別軍議で対応）

| 項目 | 状態 | 解決方針 |
|---|---|---|
| 🟡 iOS Safari Wake Lock 実機検証実施 | テレメトリ整備済 / QA 実機テスト未実施 | Low Power Mode + nosleep.js autoplay 制約。`docs/operations/F18_ios_wake_lock_checksheet.md` 沿って QA 実施待ち |
| 🟡 PDF417 バーコード描画 | 現状エラー表示 | bwip-js 等のライブラリ選定 |
| 🟡 プロバイダーロゴ画像の権利確認 SOP | 未整備 | 運営側 SOP として整備 |
| 🟡 Phase 1 Seed プロバイダーの拡充 | 10 社 | 10 社追加予定（20 社規模へ） |
| 🟡 fuzzy match の正規化レベルの将来拡張 | 完全一致のみ | 同義語辞書 / Levenshtein 距離の検討 |
| 🟡 既存マッチ済みカードの再マッチバッチ | 未実装 | プロバイダー更新時の定期バッチ |
| 🟡 DEPUTY_ADMIN の権限細分化 | 「DEPUTY_ADMIN なら誰でも押印できる」シンプル実装 | 「スタンプ押印権限のみ」を permission group で分ける場合は別軍議 |

---

## 17. 変更履歴

| 日付 | 変更内容 |
|---|---|
| 2026-05-14 | 初版作成。Phase 1 MVP（他社カード保管・グループ提示・規約・オフライン対応）+ Phase 2 拡張余地（organization 自店発行カード・スタンプ・残高）。CLAUDE.md 原則 6（UUIDv7）採用、原則 7（AbstractTenantAwareRepository）不採用＝個人スコープのため `AbstractUserOwnedRepository` パターン代替。論理削除不採用（個人機密のため物理削除、F12.3 ポリシー準拠）。`barcode_value` / `nickname` / `memo` を AES-256-GCM 暗号化、`last4` のみ平文（4 桁では特定不可）。Phase 2 用カラム（`organization_id` / `balance` / `stamp_count`）を最初から NULL 許容で投入し破壊的変更を回避。F12.3 / F10.3 連携を §10 / §11 で明記 |
| 2026-05-14 | 案 B 改修。プロバイダーマスタを人気 10〜15 社のロゴ補強用に縮小。`user_point_cards.display_name`（AES-256-GCM）を新設し自由入力主体に移行。`provider_id` を NULL 許容（ON DELETE SET NULL）に変更し fuzzy match（NFKC + ひらがな化 + 記号削除）で自動紐付け。UI は `ProviderPicker.vue` 廃止 → `PresetCardButtons.vue` 新設で「プリセット + 自由入力」を 1 画面に統合。`POINT_CARD_CREATED` 監査ログ metadata に `provider_matched` 追加。F10.3 metadata 例を null 許容版に追従。Phase 2 自店発行カード設計には無影響 |
| 2026-05-15 | Phase 1 MVP 実装完了。第一陣〜第六陣で DDL/Entity/Service/Controller/Frontend/提示モード/E2E まで完遂（PR #630/#632/#633/#642/#643/#646/#649/#650/#653 全 main マージ済）。残課題: WebAuthn サーバー側検証、iOS 実機検証、PDF417 対応、プロバイダーマスタ拡充。Phase 2 拡張余地（organization 自店発行カード）は無傷で待機 |
| 2026-05-16 | Phase 2 スタンプ型完了。WebAuthn 再認証 (POINT_CARD_009 完全実装) / 自店プロバイダー CRUD / スタンプ押印+履歴 / 店主ダッシュボード / 顧客 QR 追加フロー 全 main マージ済（PR #660/#669/#665/#666/#676/#677 + 第四陣）。Phase 1 残課題🔴 WebAuthn も解消。残高型 (SELF_ISSUED_BALANCE) は Phase 3 で対応。Phase 1 から先行投入していた `type` ENUM 3 値 / `organization_id` / `balance` / `stamp_count` カラムが破壊なく Phase 2 で活用され、設計判断 #6（先行投入）の正しさが実証された |
| 2026-05-16 | Phase 3 完了（残高型 + QR 自動特定 + Wake Lock テレメトリ）。PR #687（基盤）/ #691（QR 自動特定）/ #692（残高 API）/ #693（Wake Lock テレメトリ）/ #701（顧客側 FE）/ #702（店主側 FE）+ 第四陣（DTO 拡張 + E2E + 設計書最終化）全 main マージ済。残高型は Phase 1 から先行投入していた `user_point_cards.balance` カラム + Phase 2 ENUM SELF_ISSUED_BALANCE が破壊なく活用された。QR 自動特定は Blind Index ではなく Valkey 5 分 TTL の一時トークン方式を採用し、暗号化されたバーコード値の検索不能性を維持しつつ実現。Wake Lock 失敗時の `captureQuiet` テレメトリで iOS Safari 制約の実機実態を集計可能に。Backend DTO の `UserPointCardListItemResponse` / `UserPointCardDetailResponse` に `balance` / `stampCount` / `providerType` / `providerOrganizationId` を追加してフロント側で残高型・スタンプ型カードを一覧で即時表示可能にした。Phase 1 設計判断 #6（先行投入）が 3 Phase にわたって有効であった |
