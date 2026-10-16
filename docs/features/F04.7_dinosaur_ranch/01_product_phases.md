# F04.7-01 商品仕様・段階計画・実装根拠

> **ステータス**: 🟡 Phase 1製造中（仕様採択済み、73AC・実機・公開は未完了）
> **正本入口**: [F04.7](../F04.7_gamification.md)
> **調査基準**: `origin/main` `b3efd80c582381ea68b3536660f900e8758f280e`

## 1. 区分

「確定」は今回のユーザー方針、「提案」は実装前に承認する具体設計、「事実」は基準コミットのソース、「既知不足」は実装との差を表す。新DB表/APIの技術契約は02/03へ統合、商品数値はfixtureと本番未登録値を区別する。製造骨格やstubを公開済み機能として案内しない。

### 確定した方針

- 任意利用。本体の予定/出欠/投稿/ブログ/個人学習は恐竜を使わなくても権利・機能が同じ。
- 恐竜は本人の分身。牧場主は操作用キャラクター。部屋から庭、広い牧場へ同じ恐竜個体を継続する。
- 未ログイン/未給餌で死亡・衰弱・ポイント失効・XP減少を起こさない。streak、ログイン報酬、ランキングなし。
- バッジは記念置物へ。ベータ無償エンタイトルメントの付与/条件は維持。
- Phase 1 に出欠/タイムライン/ブログ/無料個人想起の四源。所属数や機会量で個人育成上限に差を付けない。
- 歩行は後続の見た目の演出で、報酬や権利の条件にしない。

### 採択済みの具体化（商品値は開発fixture）

| 項目 | 契約 |
|---|---|
| 個体 | 一人一頭、卵から開始。選定候補の陸/海/空poolは無料server抽選。診断と同じ初期16種×4外見をhabitatで絞り候補組均等抽選。増殖/交換/売買/有料ガチャなし |
| 部屋 | 一人一部屋、恐竜固定位置、記念置物は番号付きスロット |
| 成長 | `EGG → BABY → JUVENILE → ADULT`（卵期間の後に三段階）（幼体→成長期→成体）。累積XPが単調増加。同一個体ID |
| 通貨 | 育成ポイント一種類、永久装飾へ交換。金銭交換・送金・譲渡なし |
| 給餌 | FREE_BASIC、ポイント消費0。週care XP枠内でXP加算。rule snapshotを台帳保存。成功済み成長は不可逆 |
| 愛着 | 非減衰の親密度を仕草・反応で表現する方針は確定。追加通貨なし、初期反応は少数から。公開数値ゲージなし、versioned UTC日＋kind初回unit/閾値は02の開発fixture |
| 参加 | 明示開始後のみ報酬対象。休止中の活動を遡及付与しない。再開で個体/残高を保持 |
| 公開 | 本人限定。チーム管理者にも他人の部屋/残高/想起履歴を見せない |
| 週上限 | 活動points四源合算とcare XPは別の個人週枠。UTC月曜00:00起点、日別/連続特典なし |

非表示は参加継続。「お休みする」は新報酬停止。隠すだけで記録を消さない。参加状態はイベントの発生時刻で判定し、停止期間のbacklogを再開後に与えない。

## 2. 段階計画

| Phase | 範囲 | 除外 |
|---|---|---|
| 1 部屋 | owner/恐竜、habitat初期抽選、三段階、無料給餌、四源報酬、永久装飾交換、記録、表示/動き/音設定、置物スロット、耐障害/運営設定 | 歩行、疲労、畑、訪問、チーム共有、AI、課金による速度差 |
| 2 小さな庭 | 同一owner/恐竜を庭へ、ownerの作業疲労、作物、任意作業、歩行を検討。休んでも作物は枯れず重大損失なし | 恐竜への放置罰、同期協力、ランキング、回復課金/回復通知 |
| 3 広い牧場 | 部屋/庭状態を保持して敷地/展示空間/区画拡張。衣装/家具/景観、親密度による仕草・鳴き声・反応、恐竜のお手伝い、成体後も楽しめる大会イベント記念品 | 個体売買、金銭交換、毎日作業の義務、競争による権利格差 |
| 4 非同期訪問 | 自分のownerと恐竜を連れて明示公開snapshotへ訪問。現地の恐竜との追いかけ/ボール等の演出、勝敗なし、許可/拒否 | 常駐同期、訪問者の残高操作、無許可編集 |
| 5 任意チーム共有 | 個人牧場とは別所有のチームマスコット/共有拠点。個人ポイント/恐竜から独立、OFFでも本体権利は同じ | 個人XPの自動移管、参加義務、チーム間競争 |
| 後続 AI | 本人権限を維持、本人確認なし書込なし。費用/内容利用/同意/安全性を別設計で承認 | 初期の学習内容AI送信、努力/正誤採点、会話回数報酬、疲労との連動 |

Phase 2以降はロードマップ。初期DDL/APIに畑/訪問表を先回り作らない。Phase 2疲労はownerの庭作業能力で恐竜の健康とは別。牧場での実際の操作時間と行動が対象、放置時間/本体操作は対象外。回復しても不在で恐竜は弱らず、回復通知で復帰を促さない。作物が枯れない前提で時計/収穫保証を後続設計する。練習/活動記録の「草」は本体の別機能として、量の可視化と育成報酬を分離し、本人/チーム/選択メンバーの公開範囲を後続設計に保つ。

商品計画の旧概算はPhase 1四源統合4〜8週、全体3〜6か月。24問診断/64外見/二style/確認参照と退会raceが追加された現在の見積として用いず再見積する。一人がCodexを使い一日5〜6時間、アート仕様準備済み、既存基盤の重大不具合なしという前提。実測/約束ではなく、AR新契約・永続配送・全公開経路・アート・レビューで変動する。

## 3. 実装根拠（事実）

ソースの存在/構造を確認したもので、本番データ・権利設定の適用は未確認。以下でfrontend省略パスは `frontend/app/`、backend省略パスは `backend/src/main/java/com/mannschaft/app/` を起点とする。manifest指定の技術前提はbackend/build.gradle.ktsのSpring Boot 3.5.13/Java 21/JPA/Redis/Security/Flyway MySQL/mysqlconnector、frontend/package.jsonのNuxt 3.21.11/Vue ^3.5.41/Pinia ^3.0.4/PrimeVue ^4.5.4（インストール実版未確認）。現状宣言にThree/Lottie/Pixi必須依存はなく、初期は既存Vue UI基盤と96×96ドット/静止fallbackで成立する。

| 資産 | 基準コミットの根拠 |
|---|---|
| 個人ダッシュボード | `frontend/app/pages/dashboard.vue:34` → `components/dashboard/DashboardScopeCarousel.vue:425` → `DashboardPersonalPanel.vue` |
| 個人表示設定 | `DashboardPersonalPanel.vue:20`、`composables/useDashboardWidgets.ts:695,735` のGET/PUT永続化 |
| 遅延/ARIA/動き抑制 | `DashboardPersonalAccordion.vue:72,81,167`、`:119,120,160`、`DashboardScopeCarousel.vue:319` |
| プロフィールavatar | `components/settings/SettingsProfileSection.vue:95` は画像。恐竜で本人写真を上書きしない |
| 旧チームゲーム化 | `composables/useGamificationApi.ts:5,35,48,58` はチーム専用で個人育成と異なる |
| 出欠 | `backend/src/main/java/com/mannschaft/app/schedule/service/ScheduleAttendanceService.java:107,125,145`。更新ごとイベント、proxy履歴は今回contextと区別 |
| 想起 | `reflection/service/RecallService.java:49` はattemptのみ。`frontend/app/pages/reflections/recall.vue:31,100,101,112` は一entry保存/開示/戻る |
| 本人想起 | `reflection/controller/ReflectionEntryController.java:38` は `/api/v1/me/reflections`、`reflection/service/ReflectionAccessGuard.java:50` はowner。`reflection/service/ReflectionThemeService.java:68` / `reflection/service/ReflectionEntryService.java:66` はmembership/entitlement必須ガードなしという調査結果 |
| 無料経路 | V156系module seedの `requires_paid_plan=0` / PERSONAL。`FREE` theme enumは自由テーマで料金区分でない。運用DBは未確認 |
| beta依存 | `billing/beta/BetaGrantService.java:170` →旧gamification内 `BetaTesterBadgeAwardService`。一括削除禁止 |
| イベント | `config/SpringEventPublisher.java:21` はin-memory。AFTER_COMMITだけでは停止時に失う |

Blog公開経路/Timeline origin/代理出欠は [02](02_rewards_data.md) の契約を満たすよう実装時に全経路再照合する。新育成イベント・永続配送が既にあると仮定しない。

## 4. 既知不足・限界

1. 現行想起にsession完了契約なし。attempt保存回数を報酬とせず新しい完了契約が必要。
2. 成長は無料careでactivity0でも保証。無料ARでpoints上限へ到達可能な本人entryを持つ人にも準備負担があり、quota/想起が向かない人は追加装飾の到達手間が違う。全利用者points満額到達保証ではない。
3. 同じ上限/到達機会は努力量・所要時間の等しさではない。文字数/AI採点で努力を判定しない。
4. 報酬は業務保存後に遅延しうる。outbox保存不能でも業務成功、受付前crashの復元不能取りこぼしを許容し隠さない。
5. ログイン報酬廃止は `LOGIN_SUCCESS` 監査とbeta `activeDays` 計測の廃止を意味しない。
6. 調査は静的。本番利用可能性、設定、実画面、実データ移行は実装戦役で確認する。

## 5. 採択済みのPhase 1商品契約

恐竜は本人の分身。牧場主はPhase 2以降の操作・庭作業用プレイヤーキャラクターで、恐竜名・本人氏名とは別に扱う。初期は一人一部屋・一頭、後続Phase、成長、表示style変更でも同一個体ID/species/variant/確定名を保持する。

開始時はEGG。約7日のelapsedと選定確認が揃うと命名導線を表示する。GETはひび/readyを計算するだけでBABYへ更新しない。本人の命名確認POSTだけが名前・namedAt・hatchedAt・BABYを原子的に確定する。未選定/未命名は安全なEGG待機、XP/points0。恐竜名はUnicode trim→NFC、1〜10拡張書記素、UTF-8≤512byte/code point≤160。ZWJ絵文字を許可し、改行/制御文字・不正不可視文字を拒否する。超過を切り捨てない。注意書き→入力→確認→確定、確定後改名なし。

初期三方式はHABITAT_RANDOM、DIAGNOSIS、BIRTH_STYLE。同じ初期16species×4variant＝64外見を使い、将来64speciesへ拡張する。randomはLAND/SEA/AIRの該当する有効なspecies＋variant組を均等抽選する。確認前habitat選択は変更可、確定後の再抽選/ガチャなし。種/外見で成長・報酬・能力差を付けない。

診断は24問（6軸×4問）/5段階、同点軸だけ本人二択を最大6問。回答改版で古いtieを無効化する。server採点のtypeCodeは6bit文字列000000〜111111。軍議の24問内容は開発draftでapproved=false、構成の採択と公開質問内容の承認を分ける。参考サイトの質問・画像・算法を無断複製しない。

出生占いはユーザー採択の独自数秘。本人DOBの西暦YYYYMMDD全8桁を合計し1〜9へ反復還元する。本人カナを版付きヘボン式ローマ字へ正規化し、姓→名の全英字にA=1〜I=9、J=1〜R=9、S=1〜Z=8を繰返し割当、合計を1〜9へ反復還元する。両数とも11/22/33を特別保持しない。漢字の読みを推測せず本人カナ補完へ戻す。nickname/恐竜名/任意名を代用しない。正規化・romanization・計算規則と派生計算説明を版付きsnapshotに保存し、科学的測定/伝統流派の保証として案内しない。具体恐竜対応表はデザイン完成後にユーザーが決める。mod64を本番対応表に仮採用しない。

診断/占いは本人の私的結果として、ranch未参加でも開始/保存/一覧/詳細/再診断を利用できる。設定と「ようす」から独立到達し、非表示/PAUSED/STOPPED/care・報酬停止/残高0でも閲覧可能。再診断は新resultを作り、恐竜ID/名前/species/variant/XP/愛着/pointsを変えない。全結果は当時の説明snapshotで閲覧し、raw姓名/カナ/DOB/回答を結果へ含めない。mappingVersionは未登録NULLで結果保存可、割当利用は不可。

PIXELは96×96論理ドット、PAINT_2Dと同じ個体・段階・状態で切替する。全64外見・孵化後3成長段階・両style・基本反応/静止fallbackの承認coverageは未完成。実デザインと対応表を揃え承認するまで本番公開gateはOFF。開発S01〜S16/V1〜V4や歩行比較試作は完成素材として登録しない。歩行はPhase 2以降。

ログイン付与なし。activity0/points0/チーム未所属でも無料careだけで成体へ到達する。週care枠は同日利用可、UTC月曜起点。全源個人points週capは所属数によらず同じで、永久装飾専用。beta特典は独立保持。無料給餌/ふれあいの非減衰愛着は仕草・言葉で表現し公開数値ゲージにしない。開発加算unitはUTC日＋FEED/TOUCH各初回、別key/二tab/連打追加0。数値・閾値はversioned開発fixtureで本番自動採用しない。


### 隔離開発の報酬検証候補

隔離DEV reward QA は正式承認と別経路。専用 ranch-isolated と明示 fixture flag だけで既存四活動源の実 worker/ledger を検証する。正式質問・素材・mapping の approval は変更しない。 実測 bounds と実 UI/worker の検証は未実行。
