# F08.10 / 07: WebSocket ライブ観戦（リアルタイム配信・購読認可）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13
> **関連機能番号**: F08.10（試合記録・分析）／ F00（コンテンツ可視性）／ F08.7（大会可視性 6 レベル）
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — matches/match_events スキーマ・UUIDv7
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — F00 可視性（`MatchVisibilityResolver`）・IDOR・テナント分離・購読認可（§C.8）
> - [04_frontend_and_ux.md](./04_frontend_and_ux.md) — ライブ記録 UX（記録者側）・観戦者ビュー（§G.17）
> - 既存実装基盤: `com.mannschaft.app.config.WebSocketConfig`（SimpleBroker `/topic` `/queue`・`/app` prefix・`/ws` SockJS）／ `WebSocketAuthChannelInterceptor`（STOMP CONNECT 時 JWT 検証）
> - 既存実績: chat / village lobby（`/app/**` 送信＋`/topic` 購読・STOMP）

本書は **J（WebSocket ライブ観戦）** を具体化する。**MVP に含める**（マスター御裁可）。試合の記録者の入力（イベント・スコア更新）を、可視性を持つ観戦者へリアルタイム配信する。

---

## J.1 設計原則 — 正本は HTTP・WebSocket は配信専用

最重要原則: **正本（source of truth）は依然 HTTP**である。WebSocket は「既に永続化された変更を観戦者へ push する配信レイヤ」に徹し、**書き込み経路にはしない**。

| 役割 | 経路 | 説明 |
|------|------|------|
| 記録者（書き込み） | **HTTP POST/PATCH/DELETE**（既存 `MatchRecordEventController` 等・03） | イベント記録・スコア確定はすべて HTTP。BE が永続化＋出場時間再計算（02 §E）＋認可（03 §C）を行う |
| 観戦者（読み取り） | **STOMP 購読（`/topic/matches/{matchId}/live`）** | 記録者の HTTP 書き込みが**コミット後（AFTER_COMMIT）**に broadcast される。観戦者は購読のみ（read-only）。STOMP 経由の書き込みは一切受け付けない |

- 観戦者は STOMP の `/app/**`（SEND）宛先を**持たない**（記録は HTTP のみ）。よって STOMP インバウンドでの書き込み詐称経路が存在しない（設計上の攻撃面を最小化）。
- WebSocket が落ちても**記録・閲覧は HTTP で完全に機能する**（観戦のリアルタイム性のみが劣化＝グレースフルデグレード）。フェイルオープン思想（レートリミッタ Valkey 化と同じ運用哲学）。

---

## J.2 配信フロー（HTTP 正本 → AFTER_COMMIT 配信）

```
[記録者] HTTP POST /matches/{matchId}/events （イベント記録・03 §C 認可）
    │
    ▼
[match ドメイン] MatchEventService が永続化（@Transactional・出場時間フル再計算・02 §E.2）
    │  publish MatchLiveUpdateEvent(matchId, payload)   ← アプリ内イベント
    ▼
[配信リスナー] @TransactionalEventListener(phase = AFTER_COMMIT)
    │  （コミット済みデータのみ配信＝未コミットのイベントを観戦者に見せない）
    ▼
SimpMessagingTemplate.convertAndSend("/topic/matches/{matchId}/live", payload)
    │
    ▼
[観戦者（購読者）] /topic/matches/{matchId}/live を受信 → タイムライン/スコアを差分更新
```

- **AFTER_COMMIT 必須**: 配信は `@TransactionalEventListener(phase = AFTER_COMMIT)` で行う（順位連携リスナー＝05 §H.0.1 と同じ理由）。未コミットのイベントを観戦者へ流すと、ロールバック時に「存在しないイベント」が観戦画面に残る不整合を生むため。
- リスナーは新規 TX を張らない（配信のみ・DB 書き込みなし）。`SimpMessagingTemplate` 呼び出しは冪等性を要しない（push は再送されても観戦者が最新スナップショットで上書きする・J.4）。
- 配信失敗（broker エラー等）は**例外で記録者の HTTP を巻き戻さない**（AFTER_COMMIT のため不可かつ不要）。ログに記録し（症状を隠さない）、観戦者は次の更新 or 再接続時のスナップショット（J.4）で追従する。

### J.2.1 配信ペイロード（型安全・差分）

- ペイロードは**差分（追加/更新/削除されたイベント 1 件＋更新後スコアサマリ）**を送る（全件再送しない＝帯域節約）。
- メッセージ型（例）: `{ type: 'EVENT_ADDED' | 'EVENT_UPDATED' | 'EVENT_DELETED' | 'SCORE_UPDATED' | 'STATUS_CHANGED', matchId, event?: MatchEventResponse, score?: ScoreSummary, status?: MatchStatus, serverSeq: number }`。
- **`serverSeq`（単調増加シーケンス）**: 配信順序の検出に用いる（観戦者は seq の飛びを検知したらスナップショット再取得＝J.4）。順序保証のない broker でも観戦者が整合を回復できる。
- DTO は既存 `MatchEventResponse`（記録者 HTTP と同じ型）を再利用し型を統一（FE の `types/match.ts`・any 禁止）。

---

## J.3 購読認可インターセプタ（F00 可視性検証）【セキュリティ最重要】

> **【最重要・既存基盤のギャップ】** 既存 `WebSocketAuthChannelInterceptor` は **CONNECT フレームの JWT 検証のみ**で、**SUBSCRIBE フレームの宛先別認可を行っていない**（トークン無効でも接続を許可するフェイルオープン設計）。本機能の `/topic/matches/{matchId}/live` は可視性制御が必須のため、**SUBSCRIBE フレームを検査する購読認可インターセプタを新設**する。

### J.3.1 購読時の認可（SUBSCRIBE インターセプタ）

`/topic/matches/{matchId}/live` の購読要求（STOMP SUBSCRIBE フレーム）を `ChannelInterceptor`（inbound channel）で検査し、**F00 可視性が無い者の購読を拒否**する。

```
preSend (SUBSCRIBE フレーム):
  destination := "/topic/matches/{matchId}/live"
  if destination が match live トピックにマッチ:
     userId := セッション属性の userId（CONNECT 時に WebSocketAuthChannelInterceptor がセット）
     matchId(UUID) := destination からパース
     // F00 可視性を正準経由で検証（独自述語禁止・03 §C.3.2）
     if NOT matchAccessService.canView(userId, matchId):   // → MatchVisibilityResolver.canViewUuid へ委譲
         throw MessagingException → SUBSCRIBE を拒否（ERROR フレーム返却・購読不成立）
```

- **認可の正準は `MatchAccessService.canView` → `MatchVisibilityResolver`（F00・03 §C.3.2）に委譲**する（独自 visibility 述語を書かない＝メモリ教訓「可視性は必ず F00 ContentVisibilityResolver 経由」）。
- **テナント検証込み**: `canView` は親 matches をテナント取得（`findByIdAndOrganizationIdAndDeletedAtIsNull`・01 §A.4 二段アクセス）してから可視性判定するため、他テナントの match トピック購読は遮断される（IDOR/越境防止）。
- **未認証（userId=null）の購読**: CONNECT 時に JWT が無効/不在だと session の userId が null。この場合 `canView(null, matchId)` は F00 の未ログイン可視性（`PUBLIC` 等）に従う。**公開可視性の match のみ未ログイン観戦可**、それ以外は購読拒否。大会の可視性（F08.7 の 6 レベル）と整合する（03 §C.8）。
- **tournament 可視性レベルとの整合**: 大会公式戦（`kind=TOURNAMENT/LEAGUE`）の match 可視性は F08.7 の可視性連動（03 §未解決 3）。観戦購読も同じ可視性で判定されるため、「参加チーム関係者のみ」等の F08.7 6 レベルがそのまま観戦範囲になる。

### J.3.2 CONNECT フェイルオープンの是正は本機能に閉じる

- 既存 `WebSocketAuthChannelInterceptor` の CONNECT フェイルオープン（無効トークンでも接続許可）は**他機能（chat/lobby）の既存挙動なので変更しない**（影響範囲を限定＝根治の範囲を絞る）。
- 本機能は **SUBSCRIBE レベルの宛先別認可を新設インターセプタで追加**し、match live トピックに限って厳格に認可する。CONNECT が緩くても、SUBSCRIBE で `canView` を必ず通すため**可視性の穴は生じない**（防御は購読時点で成立）。
- 新設インターセプタは match live 宛先**以外**には介入しない（chat/lobby の既存購読を壊さない）。`destination` のプレフィックス判定で match live のみを対象にする。

### J.3.3 配信側の二重防御（送信時フィルタ）

- 購読認可（J.3.1）が第一防御。万一購読が成立しても、SimpleBroker は宛先購読者全員へ配信するため、**機微情報をペイロードに含めない**設計とする（配信は「公開可能な試合進行情報」のみ＝得点・イベント種別・選手表示名。DB 所有者情報・編集権限・内部 ID 等は含めない・03 §C.2「DB 所有はユーザー不可視」と整合）。
- これにより、購読認可と「ペイロード最小化」の二重防御で漏洩面を抑える。

---

## J.4 再接続・初期スナップショット（HTTP 取得 → topic 差分追従）

- **観戦開始時（購読確立後）**: 観戦者はまず **HTTP で現在の試合状態を取得**する（`GET /matches/{matchId}`＋`GET /matches/{matchId}/events`・02 §F.4）。これが初期スナップショット。以後 `/topic/matches/{matchId}/live` の差分で追従する。
- **再接続時**: WebSocket 切断→再接続後、**最新スナップショットを HTTP で再取得**してから差分購読を再開する（切断中に取りこぼした差分を埋める）。`serverSeq`（J.2.1）の連続性が切れていればスナップショット再取得を強制。
- これにより「差分の取りこぼし」を HTTP スナップショットで必ず回復でき、**WebSocket は信頼性を要求されない（best-effort 配信）**設計になる（正本は HTTP・J.1）。
- スナップショット取得 API も `canView` の IDOR チェーン（02 §F.4・03 §C.4）を通すため、購読認可と一貫した可視性で守られる。

---

## J.5 スケール考慮

- **MVP は SimpleBroker（インメモリ・既存 `WebSocketConfig`）**で配信する。chat/lobby と同じ基盤を流用（新規ブローカー不要）。
- **本番のマルチインスタンス展開**: SimpleBroker はインスタンスローカルのため、複数アプリインスタンスにまたがる購読者へは配信が届かない。本番で WS をスケールさせる場合は **Valkey（Redis Pub/Sub）リレー or 外部 STOMP ブローカー（RabbitMQ 等）**へ切替える（既存 `WebSocketConfig` のコメントにも「本番は Valkey 想定」とある）。**これは F08.10 単独ではなく WS 基盤全体の課題**であり、別途 WS ブローカー軍議（MEMORY: 本番移行 Phase3 WS ブローカー別軍議）で決定する。
  - **本機能の設計はブローカー差し替えに非依存**: 配信は `SimpMessagingTemplate.convertAndSend(destination, payload)` の抽象に閉じており、broker を SimpleBroker→外部ブローカーへ差し替えても本機能のコードは不変（`@TransactionalEventListener`→`convertAndSend` の構造はそのまま）。
- **配信負荷**: 1 試合あたり観戦者数は限定的（チーム関係者中心）。差分配信（J.2.1）＋ペイロード最小化（J.3.3）で帯域を抑える。大規模公開試合（数千観戦者）は MVP 想定外（その場合は外部ブローカー＋ファンアウト最適化を別途）。

---

## J.6 テスト方針

| 種別 | 対象 | 規約 |
|------|------|------|
| **購読認可 UT** | 購読認可インターセプタ（SUBSCRIBE フレーム検査） | `canView=true` で購読成立／`canView=false`（可視性なし・他テナント）で購読拒否（ERROR フレーム）／未認証は PUBLIC のみ許可。`MatchAccessService.canView` をモックして宛先パース＋委譲を検証 |
| **配信リスナー UT** | `MatchLiveUpdateEvent` AFTER_COMMIT リスナー | 純 Mockito でリスナーメソッドを直接呼び、`SimpMessagingTemplate.convertAndSend` が正しい destination・ペイロードで呼ばれることを検証（`@TransactionalEventListener` は結合テストでは発火しないため UT はメソッド直呼び・05 §H.0.1 と同じ方針） |
| **配信ペイロード UT** | ペイロード DTO | 機微情報（owning_team_id 等）が含まれないこと（J.3.3）・serverSeq 単調増加 |
| **E2E（実 WS）** | 記録→配信→観戦の一気通貫 | 記録者が HTTP でイベント記録 → 別セッション（可視性あり観戦者）の STOMP 購読がイベントを受信／可視性なし観戦者は購読拒否。再接続でスナップショット復帰（J.4）。実 BE・STOMP クライアント（chat lobby E2E パターン踏襲・feedback_e2e_real_full_crud） |

- AFTER_COMMIT リスナーの「コミット後配信」の確定検証は実 TX を伴う実機 E2E が担保する（順位連携 05 §H.0.1 と同じ理由＝`@TransactionalEventListener` はロールバック方式の結合テストでは発火しない）。

---

## J.7 実装フェーズ位置づけ

- WebSocket 観戦は **Phase 5（WebSocket 観戦）として段階実装**（06 §I.1）。MVP には含むが、コア記録（Phase 1〜2）・FE ライブ記録（Phase 3）が前提（記録の HTTP 経路が正本・J.1）。
- 依存: Phase 2（`MatchEventService` の永続化＋`MatchAccessService.canView`／`MatchVisibilityResolver`）が確定してから。配信リスナーは Phase 2 のイベント記録経路に `MatchLiveUpdateEvent` publish を追加する形（記録経路への侵襲は publish 1 行＝疎結合）。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **購読認可の正準** — 解決済み（マスター御裁可・本設計）: SUBSCRIBE インターセプタで `MatchAccessService.canView`→`MatchVisibilityResolver`（F00 正準・03 §C.3.2）に委譲。独自述語禁止。大会は F08.7 6 可視性レベルと整合（J.3）。
2. **正本の所在** — 解決済み（マスター御裁可・本設計）: 正本は HTTP、WebSocket は AFTER_COMMIT 配信専用（書き込み経路にしない・J.1）。
3. **マルチインスタンスのブローカー** — **先送り（MVP 外・ブロッカーではない）**: MVP は SimpleBroker（既存基盤）。本番スケール時の Valkey/外部ブローカー切替は **WS 基盤全体の別軍議**（本番移行 Phase3）で判断。**本機能は `SimpMessagingTemplate` 抽象に閉じておりブローカー差し替えに非依存**（J.5）。理由: WS スケールは F08.10 固有でなく全 WS 機能（chat/lobby/通知）共通の基盤課題で、F08.10 単独で先行決定すると基盤と乖離するため。
4. **大規模公開観戦（数千〜）のファンアウト最適化** — **先送り（MVP 外・ブロッカーではない）**: MVP の想定観戦者はチーム関係者中心で限定的。差分配信＋ペイロード最小化で足りる。大規模公開試合のファンアウト最適化（CDN/エッジ配信等）は要件顕在化時に別途（J.5）。
