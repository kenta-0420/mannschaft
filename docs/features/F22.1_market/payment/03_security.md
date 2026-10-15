# F22.1 市（Market）統一決済 — 03. セキュリティ・コンプライアンス・未解決事項

> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [02_api_design.md](02_api_design.md)
>
> **統一基盤・正典更新（2026-06-03）**: 謝礼＋会費を共通 `ConnectChargeService` に集約／手数料 5% を支払者2.5%・受取側2.5%で折半／**返金は受取側 scope ADMIN が操作**（運営非関与・支払者負担モデル＝decouple＝明示 TransferReversal＋`reverse_transfer:false` の Refund・`refund_application_fee:false`・Mannschaft±0/受取側±0）／決済手数料は支払者負担で返金されない（利用規約・決済画面で明示・§10 / 02 §6.1）。
>
> **手数料ランク化・正典更新（2026-06-04）**: 手数料を定数5%から **マスタ表 `fee_policies`（率%＋固定額¥）のランク制**へ（折半50/50固定・DEFAULT=率5%＋固定0で後方互換）。**シスアド CRUD は SYSTEM_ADMIN 限定**（§3）。少額決済の「総手数料>額面」破綻を防ぐ**安全ガード必須**（§3'-11e・実コード `PAYMENT_C060` FEE_EXCEEDS_FACE_AMOUNT/422）。レートは `escrow_transactions.fee_policy_key` に焼き付け**遡及防止**（§3'-11f）。

---

## 1. PCI DSS（カード情報の非保持・SAQ-A 維持）

- **カード番号は自社サーバを一切通らない**。支払者のカード入力は **Stripe Checkout / Stripe Elements（Stripe.js）でブラウザから Stripe へ直送**する（02 §5.1）。自社が扱うのは `client_secret`・`pi_xxx`・`acct_xxx` などの**トークン/識別子のみ**。
- これにより **PCI DSS SAQ-A** の適用範囲を維持する（カードデータ環境を自社に持ち込まない）。
- FE は `clientSecret` を受け取り `stripe.confirmPayment()` で confirm。**カード番号・CVC を自社 API に POST しない**ことをコードレビューで機械確認（CI 禁則: リクエストボディに `card_number`/`cvc`/`pan` 等を含めない）。
- 既存 `payment` ドメインが Checkout を用いており（`createCheckoutSession`）、本設計も同方針を踏襲する。

---

## 2. Webhook 署名検証 ＋ 冪等性

- **署名検証必須**: `POST /api/v1/webhooks/stripe/connect` は permitAll だが `Stripe-Signature` を `StripePaymentProvider.constructEvent()` で検証。検証失敗は 400（実コード `PAYMENT_C040` WEBHOOK_SIGNATURE_INVALID）。
- **Connect 用署名シークレットを分離**: `STRIPE_CONNECT_WEBHOOK_SECRET`（platform 用 `STRIPE_WEBHOOK_SECRET` と別）。Stripe 推奨のエンドポイント分離。
- **冪等性キー**: `stripe_webhook_events.event_id`（UNIQUE）。受信直後の INSERT を冪等ゲートにし、重複・並行受信を一意制約で直列化（01 §3.5・02 §4.1）。
- **二重決済防止の三重防御**: ① Stripe idempotency_key（与信/capture/返金）、② `stripe_webhook_events.event_id` UNIQUE、③ 最終認証の札行 `PESSIMISTIC_WRITE` ロック直列化（02 §5.3）。

### 2.1 deny-by-default 許可リスト（既存被覆の確認）
- `docs/security/01_authorization_baseline.md §3.6` は既に `POST /api/v1/webhooks/stripe/*`（**1階層 `*`**）を permitAll 許可している。**`/api/v1/webhooks/stripe/connect` はこの `*` で被覆される**ため、許可リストへの新規追記は不要。
- ただし本設計の PR で baseline の §3.6 に「Connect Webhook（`/stripe/connect`）も `/stripe/*` 許可で被覆」と**明記**する（将来 `/**` 再帰禁止の原則と矛盾しないことの確認記録）。
- POST の与信・払出・返金・onboarding は許可リストに入れず `.authenticated()` がカバー（公開しない）。

---

## 3. 認可マトリクス（deny-by-default 準拠）

| 操作 | 認可主体 | 実装方針 |
|---|---|---|
| Connect onboarding（個人） | **本人のみ** | `scopeKind=USER` は認証ユーザ本人固定（scopeId 無視）。他人の onboarding 不可 |
| Connect onboarding（チーム） | **チーム scope ADMIN** | `AccessControlService.checkPermission(teamId, ...)`。**ORG 用 API を渡さない**（取り違え禁止・F22.1 04 §1.1 と同轍） |
| Connect onboarding（組織） | **組織 scope ADMIN** | `checkAdminOrHasPermission(orgId, ...)`（ORG 専用） |
| Connect 状態照会 | 同上（自 scope のみ） | scope 所有権検証。他 scope は 404 秘匿 |
| 札の謝礼設定 | **札主 scope の ADMIN / `MANAGE_RECRUITMENTS` 保有 DEPUTY** | 既存札 API の認可に委譲（F03.11 §2）。市から直接立てない |
| 個人受領者の指定 | 札主 scope に紐づく者のみ | `payee_kind=USER` の `payee_user_id` が札主チーム所属か検証（実コード `PAYMENT_C013` PAYEE_NOT_IN_SCOPE） |
| **返金（謝礼・会費共通）** | **受取側 scope の ADMIN のみ**（運営非関与・設定A） | `escrow.payee`（受取側 scope）の所有権検証（IDOR・§4）。Mannschaft 運営は返金操作に関与しない。無関係 scope は 404 秘匿。**`feeBearer`（PAYER/PAYEE）の選択も受取側 ADMIN の権限内**（02 §6.1） |
| 会費徴収（即時モード） | **会員本人**（自己支払い） | F08.2 既存の会費支払い認可に委譲。内部で `ConnectChargeService` を呼ぶ（02 §5.1b） |
| エスクロー状態照会 | 受取側 scope ADMIN ＋ **受領者本人** | 受領者は自分宛の払出のみ閲覧可。無関係 scope は 404 |
| Webhook | permitAll ＋ **署名検証** | §2 |
| **手数料パターン CRUD（`fee_policies`/割当）** | **SYSTEM_ADMIN のみ** | `/api/v1/system-admin/fee-policies*`（@PreAuthorize SYSTEM_ADMIN・02 §11）。テナント管理者・一般ユーザーは不可。`DEFAULT` の削除/無効化は拒否（`PAYMENT_C052`）。料率改定は監査ログ |
| KYC 審査落ち（RESTRICTED） | — | payouts 不可なら札の謝礼を実質無効化（HELD・72h 後取消）＋札主へ通知（02 §5.2） |

---

## 4. IDOR / 越権対策

- **escrow_transaction の所有権検証**: `GET /escrow/{id}` は `escrow.source_kind/source_id` から札（または会費項目）を引き、その scope の所有権を `AccessControlService` で照合。**返金は `escrow.payee`（受取側 scope）の ADMIN 所有権を照合**（返金の操作主体＝受取側のため・設定A）。不一致は **404 秘匿**（存在を漏らさない）。
- **connect_account の所有権検証**: `GET /connect/status` は `scope_kind/scope_id` の所有権を照合。他人の `acct_xxx`・`requirements_due` を露出しない。
- **受領者指定の越権防止**: `payee_kind=USER` で札主チームに無関係の `payee_user_id` を指定 → `PAYMENT_C013`。第三者を勝手に受領者にできない。
- **payment_intent / client_secret の漏洩防止**: `client_secret` は与信作成時に**支払者本人のみ**へ返す。公開 DTO・一覧 API には決済トークンを一切含めない（F22.1 本体 04 §1.3 の禁則ワードテストに `client_secret`/`pi_`/`acct_`/`stripe` を追加）。

---

## 5. GDPR・退会時のデータ・資金の扱い

CLAUDE.md の退会 PII 二段モデル（即時消去/30日猶予）に整合させる。

| データ | 退会時の扱い | 区分 |
|---|---|---|
| `connect_accounts`（自分の Stripe アカウント） | **強匿名化の30日猶予側**。係争中・与信中・未払出の資金がある場合は**払出/返金完了まで切離さない**。完了後に Stripe Connect アカウントを deauthorize し `deleted_at` セット | 猶予対象（復旧不可・業務整合性に重大影響） |
| `escrow_transactions` / `ledger_entries` / `refunds` | **匿名化せず保持**（会計・監査証跡）。payer/payee の論理参照 ID は残すが、当該ユーザの PII（氏名等）は user 側の匿名化で消える | 匿名化しない（統計・監査の価値・退会者の user_id 残置は CLAUDE.md 原則4） |
| `requirements_due`（KYC 鏡像） | 即時消去側に寄せたいが、係争・払出中は猶予側。Connect 切離し時に削除 | 状況依存 |

- **係争中（DISPUTED）・与信中（AUTHORIZED/HELD）の資金がある退会**: 30日猶予の `AccountPurgeService` バッチが、資金が `CAPTURED`（払出完了）/`REFUNDED`/`CANCELLED` の終端に達するまで Connect 切離しを**保留**。終端未到達なら運営にアラート（資金を宙吊りにしない・症状を隠さない）。
- **保持期間**: escrow/ledger/refund の会計記録は税務・監査要件に従い保持（具体年数は §3-税務の別建て論点で確定）。Stripe 側 PII（本人確認書類）は Stripe の保持ポリシーに委ねる（自社は鏡像最小化）。

---

## 6. 資金移動業の回避（法規制）

- **案A（Destination Charge + 手動キャプチャ）では資金が終始 Stripe に保有され、Mannschaft の銀行口座を一切経由しない**（README §3.1）。capture と同時に Stripe が受領者 Connect へ transfer する。
- 自社残高に資金をプールしないため、日本の**資金決済法上の「為替取引」（資金移動業）・「前払式支払手段」に該当しない**（Stripe を収納代行/決済代行として用いる整理）。
- これを担保するため、**Separate Charge（自社残高に一旦入金して後日 Transfer する方式）は採用しない**。`transfer_data.destination` を必ず与信時に指定し、capture が transfer を伴う構造を維持する。
- **`application_fee_amount`（総手数料5%＝支払者2.5%+受取側2.5%）のみが Mannschaft の収益**。これは収納代行手数料であり資金移動業の対象外。Stripe 実手数料を引いた純益 ≈ 額面の1.31%（README §3.4）。
- **返金時も自社口座を経由しない（feeBearer 2モード・02 §6.1）**: 返金は受取側 ADMIN が `feeBearer` で負担者を選ぶ。
  - **モードA＝PAYER（既定・decouple）**: 明示 TransferReversal（受取側 Connect 残高から R を巻き戻し）＋ `reverse_transfer:false` の Refund（支払者へ R を返金）。巻き戻し額＝返金額＝R を完全一致させ **Mannschaft±0・受取側±0** を担保。R は transferAmount ベース。`refund_application_fee:false`（1.4% keep）。比例 reverse（`reverse_transfer:true`）は不採用（巻き戻し額と返金額が不一致で持ち出し）。
  - **モードB＝PAYEE（受取側の落ち度/中止）**: `Refund.create(amount=grossRefund, reverse_transfer:true, refund_application_fee:true)`。支払者へ満額 chargeAmount を戻し、Mannschaft は application_fee も返金して中立化（1.4% 放棄）。**いずれのモードも自社口座は経由しない**。
- **⚠️ モードB の Stripe 手数料負担（正直報告・症状を隠さない・02 §6.1）**: マスター意図は「モードB では受取側が Stripe 決済手数料（≈369）を負担し Mannschaft±0」だが、**標準 Stripe API のみでは自動成立不可**（実挙動検証済）。Stripe 決済手数料は返金されず Destination Charge では platform が被る／`TransferReversal` は元送金額が上限で受取側から手数料分を追加で巻き戻せない／受取側残高からの追加徴収（Account Debits）は連結口座の同意・追加コスト・同一リージョンが要件で返金 1 件ごとの自動操作に不適。**よってモードB では Mannschaft が Stripe 手数料を一時負担し、受取側への最終転嫁はリコンシリ（02 §6.3）／次回入金相殺／運用の Account Debits に委ねる**。一時負担額は `ledger_entries`(PLATFORM_FEE) に記録して可視化する。
- **受取側残高不足時のマイナス残高（運用注意）**: 受取側 Connect 残高が巻き戻し額に満たない場合、**Stripe がマイナス残高を後続入金・登録口座からの引落で自動回収**する。**Mannschaft に請求は来ない**（Stripe と受取側の間で完結）。運営は受取側へ残高補填を促す通知のみ行い、立替はしない（症状を隠さない・残高をマイナスのまま放置しない運用）。
- > 法的整理は最終的に**弁護士・税理士確認**を要する（§3-別建て論点に含む）。設計上は「自社口座非経由」を技術的に強制することで規制リスクを最小化する。

---

## 7. レート制限

| 対象 | 制限 | 根拠 |
|---|---|---|
| onboarding-link 発行 | 10 req/hour/user | アカウント作成の濫用防止 |
| connect/status | 30 req/min/user | ポーリング許容上限 |
| 返金 | 5 req/min/user | 誤操作・連打防止 |
| Connect Webhook | 制限なし（署名検証で守る） | Stripe からの正当な再送を阻害しない |

---

## 10. 利用規約・決済画面の注意書き（手数料折半・手数料非返還の明示）

マスター御指示により、手数料折半と「決済手数料は返金されない」点を利用者へ事前周知し、紛争・誤解を未然に防ぐ。

### 10.1 利用規約への明記事項
- **手数料の折半**: 「決済には額面の 5% の手数料がかかり、支払者が 2.5%（額面に上乗せ）、受取側が 2.5%（受取額から差引）を負担します」。
- **手数料の非返還（feeBearer 2モード・02 §6.1）**: 「**ご返金の場合、決済手数料が返金されないことがあります（返金条件は募集主の設定によります）**」。支払者は事前にどちらのモードで返金されるか分からないため、断定（「必ず差し引かれる」）でなく**条件付き**の周知とする。実際の返金額はモードに依存する: モードA（支払者負担）では受取側が受け取った正味（額面 10,000 円 → 9,750 円）が戻り手数料は支払者負担、モードB（受取側負担）では満額（10,250 円）が戻る。
- **返金の操作主体・モード選択**: 「返金はチーム/組織の管理者が行います。**手数料の負担者（返金条件）も管理者が選択します**。Mannschaft 運営は返金操作に関与しません」。
- **受取主体**: 「謝礼・会費は Stripe Connect を通じて受取側（個人/チーム/組織）の口座へ直接入金され、Mannschaft は資金を保持しません」。

### 10.2 決済画面（カード入力直前）の注意書き
- 手数料内訳（額面／支払手数料2.5%／お支払い合計）を明示（04 §3.1）。
- 「※お支払い合計には決済手数料が含まれます」「※ご返金の場合、決済手数料が返金されないことがあります（返金条件は募集主の設定によります）」を**内訳ボックス直下に併記**する。支払者は返金モードを事前に知り得ないため、支払時の文言は**条件付き**（`refundFeeConditionalNote`）に調整する。
- これらの文言は i18n 6言語で管理（直書き禁止・04 §6 `market.payment.breakdown.{includesFeeNote,refundFeeConditionalNote}`）。返金 UI 側（受取側 ADMIN）の負担者選択・各モード説明は `market.payment.refund.*`（04 §6）。

---

## 3'. 未解決事項（解決方針確定 / 税務は別建て）

> 2周精査により保留事項を残さず解決方針を確定。**税務のみ「税理士確認の別建て論点」**として明示し、設計内で無理に確定しない。

- [x] **1. 受領者の札ごと個人/チーム/組織選択** → `escrow_transactions.payee_kind` ＋ `connect_accounts.scope_kind`、札側は `recruitment_listings.payee_kind`/`payee_user_id`（01 §3.2 / §4.1）。個人・チーム双方の onboarding/認可/台帳が成立。
- [x] **2. 資金移動業の回避** → 案A・Stripe 収納代行・自社口座非経由（§6）。Separate Charge 不採用。
- [x] **3. 受領者 onboarding 未完了時の払出** → `HELD`。72h 猶予で完了を待ち、未完了なら与信取消＋応募者/札主へ通知（02 §5.2・症状を隠さず原因明記）。
- [x] **4. KYC 審査落ち・Connect 制限（RESTRICTED）** → payouts 不可なら謝礼を実質無効化（HELD→取消）＋札主へ通知。`account.updated` で復帰したら再開（02 §4.2）。
- [x] **5. 通貨 JPY ゼロデシマル** → amount は**円整数（最小単位）**で保持・Stripe へ渡す。`currency CHAR(3)` で明示（01 §1）。
- [x] **6. 退会時の資金** → 係争/与信中は強匿名化30日猶予側。終端到達まで Connect 切離し保留（§5）。
- [x] **7. 係争（DISPUTED）と hold 失効** → 先 capture 後返金戦略（F13.1 §8.9.3 踏襲・02 §5.4）。
- [x] **8. 二重払出** → 札行 `PESSIMISTIC_WRITE` ＋ Stripe idempotency_key ＋ webhook event_id UNIQUE の三重防御（§2・02 §5.3）。
- [x] **9. IDOR（escrow/connect の他人参照）** → scope 所有権検証・404 秘匿（§4）。
- [x] **10. Webhook 許可リスト** → 既存 `/webhooks/stripe/*` で被覆。baseline §3.6 に明記（§2.1）。
- [x] **11. 手数料率（確定: 案あ＝DEFAULT・2026-06-04 ランク化）** → **手数料はマスタ表 `fee_policies`（率%＋固定額¥）で持ち折半50/50固定**。DEFAULT＝率5%＋固定0＝旧「総5%折半」と完全一致（後方互換）。source_kind＋sub_key で `FeePolicyResolver` が解決（完全一致→既定→DEFAULT）。`escrow_transactions.fee_policy_key` に焼き付け遡及防止。`PaymentFeeCalculator` は定数撤廃→policy 注入の純粋関数。`stripe_fee_rate` 既定 0.036（純益試算・参考）。シスアド CRUD は SYSTEM_ADMIN 限定（README §3.4 / 01 §3.6/§3.7 / 02 §3.5/§11）。
- [x] **11e. 手数料の安全ガード（少額破綻防止）** → 固定額混在で「総手数料 > 額面」になると `application_fee ≤ amount` 違反＋破綻。起票前に **total_fee ≤ face を必須検証**し違反は実コード `PAYMENT_C060`（`FEE_EXCEEDS_FACE_AMOUNT`・422・ERROR_CODE_STATUS_MAP 登録）で拒否（業務上限/下限キャップは無し・症状を隠さない・02 §3.5.2）。`PAYMENT_C050` は実コードでは `STRIPE_API_ERROR`（500）であり安全ガードではない（02 §7）。
- [x] **11f. 料率改定の遡及防止** → charge/与信/サブスク加入時に解決した `policy_key`・算出金額を escrow（`fee_policy_key`）/membership_subscriptions に焼き付け。`fee_policies` 改定は新規徴収のみ反映・既存取引は固定（README §3.4.2 / 01 §3.2）。**F22.1 突合（返金 feeBearer 2モード）は保存済み amount−application_fee の差分計算ゆえ rate 非依存＝ランク可変でも整合**（02 §6.1）。`chk_et_fee` は安全ガードにより構造維持。
- [x] **11b. 返金の操作主体・方式（確定: 設定A・支払者負担モデル 2026-06-03）** → 受取側 scope ADMIN が操作（運営非関与）・**decouple 方式＝明示 TransferReversal(R)＋`reverse_transfer:false` の Refund(R)**・`refund_application_fee:false`・支払者へ戻すのは transferAmount（決済手数料・支払上乗せは非返還で支払者負担・Mannschaft±0/受取側±0）・利用規約/決済画面で明示（§10 / 02 §6.1）。
- [x] **11c. 受取側残高不足のマイナス残高** → Stripe 自動回収・Mannschaft 請求なし（§6・立替しない）。
- [x] **11d. 統一基盤化（謝礼＋会費）** → 共通 `ConnectChargeService`・2モード（即時/エスクロー）・`source_kind=MEMBERSHIP` 追加。会費は P2-e で F08.2 から本基盤へ移行（README §1.0 / §8.1）。
- [x] **12. F13.1 との関係** → テーブル共有せず独立構築。`source_kind=JOBMATCHING` を確保し将来の流用余地を残す（README §5）。
- [x] **13. フリマ転用** → `source_kind=FLEAMARKET` を確保（フロー自体は別軍議）。parking の Connect 資産統合も別軍議（README §8.2）。
- [ ] **14. 税務（謝礼の所得区分・源泉徴収・支払調書・適格請求書/インボイス）** → **税理士確認の別建て論点**。`on_behalf_of` で受領者売上として扱う基盤は用意するが、源泉徴収義務の有無・支払調書の提出・インボイス番号の保持などは設計内で確定せず、**税務専門家の確認後に別途設計**する。技術的フックとして `escrow_transactions` に将来 `tax_withheld_amount`/`invoice_number` を追加できる余地を残す（本Phaseでは追加しない）。

---

## 8. ステータス確定条件（🟢設計確定（実装: P2-a完了/P2-b以降未着手）の根拠）

すべて充足済み。

- [x] マスター御裁可（受領者札ごと選択・案A・**手数料折半=案あ＝DEFAULT・返金=設定A・統一基盤化・手数料ランク化（`fee_policies`）・受取人で二分する統一アーキ原則**）が DDL/API に反映されている（README §1.0/§3.0/§3.3〜§3.5・01 §3.2/§3.6/§3.7/§4.1・02 §0/§3.5/§5/§6/§11）。
- [x] §3' 未解決事項が全件解決方針確定（[x]）。税務のみ別建て論点として明示（[ ] のまま意図的に保留）。
- [x] DB 原則適合（01 §7）：クロスドメインFK禁止・CASCADE 同一ドメイン内・新規UUIDv7・テナント Repository。疎結合（ApplicationEvent）・@Query 内コメント厳禁（実装原則）。
- [x] PCI（SAQ-A）・Webhook 署名＋冪等・IDOR・GDPR/退会・資金移動業回避・**手数料非返還の規約/画面明示**が網羅されている（§10）。
- [x] 既存 payment 資産の再利用点と Connect 増築点が明確（README §4・02 §0/§8）。
- [x] F22.1 README §1 対応表の是正・**F08.2/parking 相互参照**追記が列挙されている（README §8/§8.1/§8.2）。

---

## 9. テスト方針（実装フェーズ向け・test-first 先行）

02 §10 に集約。要点: 認可（onboarding/返金/照会の越権→403/404）・冪等（event_id/capture key）・状態遷移・払出保留（HELD/72h）・JPY ゼロデシマル・二重払出防止（札行ロック）・台帳借方=貸方検算・PCI 禁則ワード（client_secret/pi_/acct_ を公開DTOに含めない）。
