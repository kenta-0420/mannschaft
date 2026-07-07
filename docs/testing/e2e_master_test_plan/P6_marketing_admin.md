# P6 マーケ・広告・配信・システム管理 E2E テスト法案

> 対象: F09.2 / F09.17 / F09.11 / F09.7 / F09.19 / F09.6 / F09.18 / F09.8(+8.1) / F10.1/3/6/7 / F12.2 / F09.12
> 凡例・テスト層は [README](./README.md) 参照。
> ⚠️ **裏取り品質 △**: FE 実装を「UI確認必須」のまま残した箇所が多い。🟡 は実機 reachability で確定する。

---

## 1. トレーサビリティ監査サマリ

### F09.2 プロモーション配信
| 機能要素 | BE | 判定 |
|---|---|---|
| プロモCRUD/セグメント(郵便番号・ロール)/配信先プレビュー | OrgPromotionController/SegmentEvaluator/AdAudienceResolver | 🟢 FE要確認 |
| クーポン紐付け・QR利用(HMAC-SHA256) | Coupon/CouponRedemptionService | 🟢 |
| 配信予約/開封トラッキング | ScheduledBatch/DeliveryTracker | 🟢 |
| **大規模配信(1000人以上)ADMIN承認** | ApprovalFlow | 🟡 承認UI要確認 |
| 週次送信上限(週3回) | QuotaValidator | 🟡 制限到達シナリオ |

### F09.17 広告主ターゲット配信キャンペーン
| 機能要素 | BE | 判定 |
|---|---|---|
| キャンペーンCRUD/4チャネル/ターゲティング(AGE/GENDER/REGION) | AdMessagingCampaign系(PR#896/906/911) | 🟢 |
| 推定リーチ(100人未満非表示)/審査提出・承認/自動NG検知 | AdReachEstimator/Moderation/NgWordDetector | 🟡 |
| フリークエンシーキャップ(週3・Valkey) | FrequencyCap | 🟡 週境界テスト必須 |
| オプトアウト(チャネル別+広告主ブロック) | UserAdPreferencesService | 🟢 |
| **受信者3件通報→自動SUSPEND** | AdUserReportService | 🔴 **BE未実装確定**（2026-07-07裏取り: 通報POST/一覧のコントローラ不在・FEは実装済みで404。F09.19.9で一式新規実装） |
| GDPR自己データ削除(冪等) | AdDataDeletionService | 🟡 |

### F09.19 広告枠サービング・ハイブリッド購入（設計 2026-07-07・未実装）
| 機能要素 | BE | 判定 |
|---|---|---|
| 運用型キャンペーンCRUD/状態遷移/審査(-operational)/クリエイティブ帰属検証是正(IDOR閉塞) | AdvertiserAdCampaignController(新設予定) | 🔴 設計のみ（F09.19.1） |
| サービング `/spotlight/content`・`/view`・`/visit`（中立命名・BE有料プランゲート・serve証跡・割当4段階・count=2重複回避） | SpotlightServingService(新設予定) | 🔴 設計のみ（F09.19.2） |
| 日次集計バッチ→ad_daily_stats→月次請求（金額一気通貫・手動トリガーAPI） | AdDailyStatsAggregationBatchService(新設予定) | 🔴 設計のみ（F09.19.3/.8） |
| SpotlightSlot 2ウィジェット/IN_FEED差込/広告ラベル/非表示導線/affiliate seed | FE SpotlightSlot.vue(新設予定) | 🔴 設計のみ（F09.19.4） |
| 運用型キャンペーン管理画面（広告主CRUD+SYSTEM_ADMIN審査キュー） | FE operational-campaigns / operational-queue(新設予定) | 🔴 設計のみ（F09.19.4b） |
| F09.11系scope化（チーム請求/与信/レポート/シミュレーター） | scope化コントローラ対(新設予定) | 🔴 設計のみ（F09.19.5/.6） |
| 通報API一式（メッセージ型/運用型・3件自動停止・SYSTEM_ADMIN一覧。**現状BE未実装でFEが404**） | V145.001+通報コントローラ新設 | 🔴 設計のみ（F09.19.9・別実装弾） |

### F09.6/F09.18/F09.8/F10.x
- F09.6 DM配信(即時/予約)/配信対象プレビュー/開封トラッキング/クーポン手入力 = 🟢/🟡
- **F09.18 メール配信基盤(outbox)** = Phase 18-a 設計、保証付き非同期/指数バックオフ/DEAD_LETTER。BE 実装確認要(メモリでは Phase18 完了系の記録あり=要突合)
- F09.8 コルクボード/ピン止め/ダッシュボード統合 = 🟢/🟡
- F10.1 管理者DB/F10.3 監査ログ/F10.6 エラー監視/F10.7 業務アラート = 🟢/🟡
- F12.2 フィーチャーフラグ(`FEATURE_V9_ENABLED`) = 🟡

---

## 2. E2E 実機シナリオ（代表・トレーサ付き）
- **[F09.2-E01]** 郵便番号セグメント配信→プレビュー対象数→配信→promotion_deliveries 45件 SUCCESS。（§3/§4 preview）
- **[F09.2-E02]** クーポン付きプロモ→配布→マイページ表示→QR redeem→2回目で400(上限到達)。（§3/§4）
- **[F09.17-E02]** 年齢セグメント+フリークエンシーキャップ: 月内3配信成功→4件目 402(週3上限)、`user_ad_delivery_counters`=3。（§3 user_ad_delivery_counters）★Valkey週境界
- **[F09.17-E03]** 受信者通報→3件で自動BLOCK→`ad_messaging_campaigns.status=BLOCKED`+監査ログ CAMPAIGN_AUTO_BLOCKED。（§3 ad_user_reports/§1）**※通報BEはF09.19.9実装後に実施可能（現状404）**
- **[F09.11-E01]** 広告主登録(PENDING)→SYSTEM_ADMIN承認(ACTIVE/Stripe Customer)→配信→月次バッチで請求書(DRAFT→ISSUED)→PDF DL。（§4）
- **[F09.19-E01]** 運用型一気通貫: 広告主登録→承認→キャンペーン作成(unit_price_snapshot)→クリエイティブ入稿→双方審査承認→受信者ダッシュボードに SpotlightSlot 表示→view/visit 計上→日次集計(手動実行)→ad_daily_stats→月次請求金額反映。（F09.19 §16 F09.19.8）
- **[F09.19-E02]** 予約バナー意味論: F09.17 BANNER 予約→`ad_banner_deliveries.served_at IS NULL`→serve+view で充足→BillingBridge が未表示予約を課金しない。（F09.19 §7.4）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定
| 機能 | 状態 |
|---|---|
| F09.17 受信者3件通報自動SUSPEND の通報導線 | 🔴 **通報はBE API自体が未実装**（FE導線は実装済みで404）。F09.19.9で一式新規実装 |
| F09.2 大規模配信 DEPUTY_ADMIN→ADMIN 承認 UI | 🟡 |
| F09.18 メール outbox(送信予約/指数バックオフ/DEAD_LETTER) | 🟡 BE 実装状態を Phase18 記録と突合 |
| F09.17 unsubscribe 周期トークン(token_version) | 🟡 設定変更後リンク失効 E2E |
| F10.3 メール outbox 操作監査ログ SYSTEM_ADMIN 画面 | 🟡 |
| admin 孤立ページ: `/admin/ad-rate-cards` `/admin/ad-credit-limit-requests` `/admin/affiliate-settings` がナビ未接続（直URLのみ） | 🔴 F09.19.7 でナビ接続予定 |
| F09.17 ウィザード推定リーチが FE ダミー値（preview API コントローラ未実装） | 🔴 F09.19.7 で実結線予定 |
| BANNER チャネルの掲載面（表示コンポーネント）不在＝出稿→掲載ループ未完 | 🔴 F09.19.2/.4 で充填予定 |

---

## 4. 既存 E2E spec ギャップ
- F09.2 複合セグメント(AND/OR)・バウンス処理、F09.17 Valkey countering・自動SUSPEND、F09.11 請求書PDF/Stripe/増額申請、F09.6 トラッキングピクセル/SESバウンス/GDPR匿名化、F09.18 outbox ワーカー、F10.3 ドメイン別ログフィルタ — いずれも新規テスト追加要。
- **裏取り再確認**: 🟡 は実機 reachability で UI 実在を確定。
