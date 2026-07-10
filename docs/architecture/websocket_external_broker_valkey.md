# WebSocket 外部ブローカー化（Valkey Pub/Sub 中継）設計書

> **ステータス**: 🟡 設計中
> 作成日: 2026-07-10
> 対象ブランチ: `docs/websocket-external-broker-design` → `main`
> 担当: kenta（殿の軍議 + 家老偵察 + 足軽起草）
> 関連: [`docs/features/F08.10_match_record_analytics/07_realtime_spectator.md`](../features/F08.10_match_record_analytics/07_realtime_spectator.md) §J.5（「WS 基盤全体の別軍議」の正本は本書）／[`docs/architecture/db_scalability.md`](db_scalability.md)（1000 万ユーザー方針）

---

## 概要

Mannschaft は現在、WebSocket（STOMP over SockJS）のリアルタイム配信を **各アプリノードのインメモリ SimpleBroker** で行っている。SimpleBroker はノードローカルであり、**複数ノードにまたがる購読者には配信が届かない**。この制約のため本番 ECS サービスは `desired_count = 1`（単一タスク）に固定され、水平スケール・無停止デプロイ（ローリングアップデート）ができない状態にある。

本設計は、SimpleBroker を維持したまま **Valkey（Redis 互換）の Pub/Sub でノード間にメッセージをファンアウトする中継層（relay）** を新設し、マルチノード配信を実現する。あわせて、本作業のスコープで **STOMP Principal 未配線という前提課題**（`convertAndSendToUser` のユーザー宛配信が成立していない疑い）を根治する。

本ドキュメントは本テーマの Single Source of Truth である。

### 目標

| 目標 | 内容 |
|---|---|
| マルチノード配信 | ノード A 接続の購読者が、ノード B 発の配信を受信できる（`/topic`・`/queue` 全配信先）|
| 無停止デプロイ解禁 | `deployment_minimum_healthy_percent 0→100` / `maximum 100→200` を可能にし、ローリングアップデート中の断をなくす |
| ユーザー宛配信の根治 | `convertAndSendToUser`（`/user/{userId}/queue/...`）を、接続ノードに関わらず正しく到達させる |
| 非回帰 | feature flag OFF 時は現行と完全同一挙動 |
| フェイルオープン | Valkey 断でもアプリは落ちず、ローカル配信を継続。復旧後に中継自動再開 |
| コスト据え置き | 既存 ElastiCache Valkey（単一ノード）を流用し、追加インフラ費ゼロ |

---

## 1. 方式決定（マスター御裁可済み）

### 1.1 採用: A 案 — SimpleBroker + Valkey Pub/Sub ファンアウト中継

各ノードの SimpleBroker は**維持**する。ノードで発生した配信メッセージ（brokerChannel に流れるメッセージ）を Valkey チャネルへ publish し、**全ノードがそのチャネルを購読**、受信ノードは自ノードの SimpleBroker にメッセージを**再注入**する。これにより、どのノードに接続していても全ノード発の配信が届く。

- クライアントの購読 destination（`/topic/...`・`/user/.../queue/...`）は**一切変更しない**（FE 改修ゼロ）。
- STOMP エンドポイント（`/ws`・`/ws/signage`）・SockJS も不変。
- ロードバランサのセッションアフィニティ（sticky session）に**非依存**。どのノードに振られても配信が揃う（後述 Cloudflare Tunnel 移行と相性良）。

### 1.2 却下案と理由

| 案 | 概要 | 却下理由 |
|---|---|---|
| **B 案: セッションレジストリ方式** | 全ノードの STOMP セッション（userId→nodeId→sessionId）を Valkey に集中登録し、送信側が宛先ノードを引いて直接転送する | **複雑度過剰**。セッションのライフサイクル（接続/切断/障害）を分散状態として厳密管理する必要があり、整合性バグの温床。SimpleBroker の宛先解決を自前再実装することになり保守コストが高い |
| **C 案: RabbitMQ StompBrokerRelay** | `enableStompBrokerRelay()` で外部 STOMP ブローカー（RabbitMQ 等）に委譲する Spring 標準構成 | **新規インフラ費がコスト削減方針と矛盾**。RabbitMQ クラスタの新規構築・運用（HA・監視・パッチ）が発生する。既存 Valkey で足りる規模で新ミドルウェアを増やさない |

> A 案は「既存資産（SimpleBroker + Valkey）の最小拡張で最大効果」を狙う中庸解であり、B/C の将来移行余地も残す（後述 §8 の移行パス）。

### 1.3 段階移行（安全側既定）

feature flag `mannschaft.websocket.relay.enabled`（既定 `false`）で relay の ON/OFF を切り替える。

1. **段階 1**: `desired_count = 1` のまま relay を ON にして検証（単一ノードでも publish→自ノード購読→再注入のループが健全に回る＝ループ防止が効くことを本番相当で確認）。
2. **段階 2**: `desired_count` を 2 以上に増やし、`deployment_*` を無停止設定へ変更（§6）。

flag OFF なら relay 部品は publish も subscribe もせず、現行 SimpleBroker 挙動と完全同一（非回帰）。

---

## 2. 現状分析（origin/main 実コードで裏取り済み）

### 2.1 WebSocket 設定は「2 つの `@EnableWebSocketMessageBroker`」だが実体は単一ブローカー

同一 Spring コンテキストに `@EnableWebSocketMessageBroker` が **2 箇所**存在する。

| Config クラス | エンドポイント | ブローカー宣言 | インバウンドインターセプタ |
|---|---|---|---|
| `config/WebSocketConfig` | `/ws`（SockJS）| `enableSimpleBroker("/topic","/queue")`・`setApplicationDestinationPrefixes("/app")`・`setUserDestinationPrefix("/user")` | `WebSocketAuthChannelInterceptor` → `MatchLiveSubscriptionInterceptor` → `EmergencyClosureSubscriptionInterceptor` |
| `signage/config/SignageWebSocketConfig` | `/ws/signage`（SockJS）| `enableSimpleBroker("/topic")`・`setApplicationDestinationPrefixes("/app")` | なし |

**確定した実挙動（Spring 機構）:**
`@EnableWebSocketMessageBroker` は `DelegatingWebSocketMessageBrokerConfiguration` を `@Import` するメタアノテーションであり、同一構成クラスの多重 import は **1 回に重複排除**される。この `DelegatingWebSocketMessageBrokerConfiguration` は、コンテキスト内の **全ての `WebSocketMessageBrokerConfigurer` 実装 Bean を注入し、各コールバックを全 Configurer に委譲**する。したがって:

- **ブローカーは 2 系統の独立ブローカーではなく、単一のブローカー（単一 brokerChannel・単一 SimpleBroker・単一 clientOutboundChannel）に両 Configurer がマージ**される。
- `registerStompEndpoints` は加算的に呼ばれ、`/ws` と `/ws/signage` の両エンドポイントが単一ブローカーに載る。
- `configureClientInboundChannel` は `WebSocketConfig` のみが実装するため、認証・購読認可インターセプタは単一のインバウンドチャネルに登録され、**サイネージ含む全エンドポイントに適用**される。

**→ 中継（relay）の適用範囲**: relay は「単一の brokerChannel／SimpleBroker」を対象にするため、**サイネージ（`/topic/signage/...`）を含む全配信が自動的に中継対象**になる。サイネージ専用の追加中継実装は不要。

> **潜在的脆弱性（実装スコープで是正）**: `configureMessageBroker` は両 Configurer で呼ばれ、**同一の `MessageBrokerRegistry` を二重に変更**する。`enableSimpleBroker(...)` は registry 内の登録を上書きするため、`WebSocketConfig`（`/topic`+`/queue`）と `SignageWebSocketConfig`（`/topic` のみ）の**呼び出し順序で最終的なブローカー設定が変わる**（順序依存・`@Order` 未指定で非決定的）。仮にサイネージ側が後勝ちすると `/queue` がブローカーに登録されず、`convertAndSendToUser` の `/queue/...` 配信が壊れうる。実装隊は**起動時に実 Configurer 順序を列挙して現挙動を確定**し（家訓「憶測修正禁止」）、**設定を単一 Config に集約するか、サイネージ側の `configureMessageBroker` 二重宣言を排除**して順序依存を根絶すること。Javadoc「本番は Valkey 想定」（`WebSocketConfig`）は実態と乖離しているため、実装時に是正する。

### 2.2 配信箇所の全数（relay の対象）

全配信は `SimpMessagingTemplate`（Spring 自動登録の単一 Bean）経由。`convertAndSend`（ブロードキャスト）・`convertAndSendToUser`（ユーザー個別）を全文検索して列挙。

| # | 発信元クラス | destination | ペイロードの性質 | at-most-once 許容根拠 |
|---|---|---|---|---|
| 1 | `chat/service/ChatMessagePublisher`（81 行）| `/topic/channels/{channelId}` | DB 永続済み（chat_messages）| 再取得可能（メッセージ履歴 API）|
| 2 | `chat/service/ChatChannelEventPublisher`（66 行）| `/topic/channels/{channelId}/events` | 状態変化イベント（kicked/deleted/archived 等）= DB 状態反映 | 再取得可能 |
| 3 | `chat/controller/ChatTypingController`（69 行）| `/topic/channels/{channelId}`（タイピング）| 揮発（`TypingPayload`・非永続）| 揮発許容（表示の一時状態）|
| 4 | `match/live/MatchLiveBroadcastListener`（76 行）| `/topic/matches/{matchId}/live` | DB 永続済みの差分（HTTP 正本・07 §J.1）。`serverSeq` はインメモリ揮発 | 再取得可能（スナップショット再取得・07 §J.4）|
| 5 | `village/service/VillageLobbyPresenceService`（123 行）| `/topic/villages/{villageId}/lobby/presence` | 揮発（在席 = Valkey TTL キー由来・DB 非永続）| 揮発許容・REST `getPresence` で再取得可 |
| 6 | `corkboard/event/CorkboardEventListener`（62 行）| `/topic/corkboard/{boardId}` | DB 永続済み（AFTER_COMMIT）| 再取得可能 |
| 7 | `reservation/EmergencyClosureBroadcastListener`（78 行）| `/topic/teams/{teamId}/emergency-closures/{closureId}/confirmations` | DB 永続済みの確認サマリ（AFTER_COMMIT）| 再取得可能 |
| 8 | `notification/service/NotificationDispatchService`（141 行）| `/user/{userId}/queue/notifications`（`convertAndSendToUser`・唯一のユーザー宛）| DB 永続済み（notifications 行）| 再取得可能（通知一覧 API）|
| 9a | `signage/websocket/SignageWebSocketPublisher`（33 行）| `/topic/signage/{screenId}/emergency` | 緊急メッセージ（`SignageEmergencyMessageEntity` は DB 永続・timestamp は揮発）| 再描画可能 |
| 9b | 同上（48 行）| `/topic/signage/{screenId}/update` | 揮発シグナル（`type=SLIDE_UPDATE`＝クライアント再取得トリガ・データ本体なし）| 揮発許容・再取得可能 |

> **配信保証**: 中継は **at-most-once（fire-and-forget）**。全配信先が「DB 永続済み・再取得可能」または「揮発許容」のいずれかであることを上表で確認済み。WebSocket は best-effort 配信であり、正本は HTTP（07 §J.1）という既存原則と整合する。

### 2.3 前提課題: STOMP Principal 未配線（重大・本スコープで根治）

**欠陥仮説（実コードで確認済み）:**
`WebSocketAuthChannelInterceptor` は STOMP CONNECT 時に `sessionAttributes.put("userId", userId)` で**セッション属性に userId を入れるだけ**で、`accessor.setUser(...)`（STOMP Principal 確立）を**していない**。origin/main 全体を検索しても `accessor.setUser(` / `StompPrincipal` / `SimpUserRegistry` の使用は**皆無**（0 件）。

その結果、`NotificationDispatchService.sendViaWebSocket` の

```java
messagingTemplate.convertAndSendToUser(
        notification.getUserId().toString(),  // user 名 = userId 文字列
        "/queue/notifications",
        response);
```

が依拠する **ユーザー宛先解決（`DefaultUserDestinationResolver` → `SimpUserRegistry` によるユーザー→セッション対応）が成立していない**。Principal が一度も設定されないため `SimpUserRegistry` にユーザー→セッションの登録がなく、`/user/{userId}/queue/notifications` は解決先セッション 0 件となり、**単一ノードでもユーザー宛通知が誰にも届かない**疑いが濃厚（これはマルチノード以前の既存バグ）。

> **家訓遵守（憶測修正禁止 / `feedback_empirical_bug_detection_over_speculation`）**: 上記は**仮説**であり、**先に失敗するテスト（red）で実証してから直す**。実証方法は §7.2 の「2 セッションテスト」。

**是正設計:**
1. CONNECT 時に `WebSocketAuthChannelInterceptor` で **Principal を確立**する。JWT から得た `userId` を名前に持つ軽量 Principal を生成し `accessor.setUser(new StompPrincipal(String.valueOf(userId)))` を呼ぶ（`StompPrincipal implements java.security.Principal`・`getName()` が userId 文字列を返す新規クラス）。
2. これにより **`SimpUserRegistry` に接続ユーザーが登録**され、`convertAndSendToUser` が `/user/{userId}/queue/...` を当該ユーザーの全セッションへ解決できるようになる。
3. 既存の `sessionAttributes` の userId は購読認可インターセプタ（`MatchLiveSubscriptionInterceptor` 等・07 §J.3）が参照しているため**併存を維持**（後方互換）。Principal 追加は副作用なく上乗せできる。
4. マルチノード時のユーザー宛配信は、relay が `/user/...` 解決後の実 destination（セッション固有）ではなく **ユーザー宛の論理 destination を中継**することで、受信ノード側の `SimpUserRegistry`（そのノードに接続中のセッション）で解決する（§4.3 参照）。

### 2.4 Valkey 現状（pub/sub 未使用 → 新設が必要）

`config/RedisConfig` は **`RedisCacheManager`（キャッシュ）のみ**。pub/sub（`RedisMessageListenerContainer` / チャネル publish）は**皆無**。したがって購読専用の `RedisMessageListenerContainer`（Lettuce・購読専用接続）の**新設が必要**。

キャッシュのキー規約は `mannschaft:cache:{name}:`。本設計の Pub/Sub チャネルは同規約に合わせ **`mannschaft:ws:relay:*`** 系を用いる（§4.2）。

ElastiCache は `aws_elasticache_replication_group.valkey`（`engine = "valkey"`・`num_cache_clusters = 1`・`automatic_failover_enabled = false`・port 6379・at-rest 暗号化 ON / transit 暗号化 OFF）。**単一ノードを維持**（本設計で構成変更なし）。接続は Spring `spring.data.redis`（Lettuce）・`SPRING_REDIS_HOST/PORT` 環境変数（terraform 供給）。

---

## 3. 決定事項サマリ（マスター御裁可済み）

1. **A 案採用**（SimpleBroker 維持 + Valkey Pub/Sub ファンアウト）。B 案（複雑度過剰）・C 案（新規インフラ費が方針矛盾）は却下。
2. **Principal 未配線の根治を本設計スコープに含める**（§2.3）。
3. **ElastiCache Valkey は単一ノード維持**（追加費用ゼロ）。Valkey 断は**フェイルオープン**（リアルタイム中継のみ一時停止・アプリ継続・自動復旧）。将来レプリカ追加可能（§8）。
4. **段階移行**（feature flag `mannschaft.websocket.relay.enabled`）。①`desired_count=1` のまま relay ON→検証 → ②2 タスク化。安全側既定（既定 OFF）。

---

## 4. 中継設計（relay）

### 4.1 コンポーネント構成（クラス名は英語）

| コンポーネント | 責務 |
|---|---|
| `WebSocketRelayProperties` | `mannschaft.websocket.relay.*`（`enabled`・チャネル名・`nodeId` 等）の束ね |
| `WebSocketRelayPublisher` | 自ノードの brokerChannel（アウトバウンド配信）に流れたメッセージを捕捉し、`RelayEnvelope` にラップして Valkey チャネルへ publish |
| `WebSocketRelaySubscriber` | `RedisMessageListenerContainer` で Valkey チャネルを購読し、受信 `RelayEnvelope` を自ノードの SimpleBroker に再注入 |
| `RelayEnvelope` | 中継メッセージの封筒（後述フィールド）。ループ防止用の発信ノード ID を含む |
| `StompPrincipal` | `java.security.Principal` 実装（`getName()` = userId 文字列）。§2.3 の Principal 配線用 |
| `RedisMessageListenerContainer`（Bean 新設）| Lettuce の購読専用接続。`WebSocketRelaySubscriber` を購読者として登録 |

### 4.2 メッセージフロー

```
[ノード A]
  発信元（例: ChatMessagePublisher）
    → SimpMessagingTemplate.convertAndSend("/topic/channels/42", payload)
    → brokerChannel（アウトバウンド）
       ├─(既存) SimpleBroker → ノード A 接続の購読者へ配信
       └─(新設) WebSocketRelayPublisher が捕捉
             → RelayEnvelope{originNodeId=A, destination, headers, body} を
               Valkey PUBLISH "mannschaft:ws:relay:broadcast" <envelope-json>

[Valkey] fan-out（全ノードが購読）

[ノード B / C ...]
  WebSocketRelaySubscriber が SUBSCRIBE "mannschaft:ws:relay:broadcast" で受信
    → originNodeId == 自ノード? → YES: 破棄（ループ防止）
                                → NO : SimpleBroker へ再注入（relay マーカー付与）
    → ノード B 接続の "/topic/channels/42" 購読者へ配信
```

チャネル設計（キー規約 `mannschaft:ws:relay:*`）:

| チャネル | 用途 |
|---|---|
| `mannschaft:ws:relay:broadcast` | `/topic/...` ブロードキャスト中継（単一チャネルに集約し、destination は封筒内で判別）|
| `mannschaft:ws:relay:user` | `/user/.../queue/...` ユーザー宛中継（§4.3）|

> 単一チャネル集約とするか destination プレフィックス別チャネルに分けるかは、実装隊が **配信量メトリクスを見て決定**（初期は 2 チャネルで開始）。全ノード購読のため、チャネル分割は不要フィルタリングの削減が目的（機能差はない）。

### 4.3 ユーザー宛（`convertAndSendToUser`）の中継

ユーザー宛は destination 解決の性質が異なる。`convertAndSendToUser(userId, "/queue/notifications", payload)` は、発信ノードで `SimpUserRegistry`（そのノードに接続中のセッションのみ）を引いてセッション固有 destination に解決する。**ユーザーが別ノードに接続している場合、発信ノードの registry には存在せず解決先 0 件**になる。

中継設計:
- `WebSocketRelayPublisher` は、ユーザー宛の**論理 destination（`/user/{userId}/queue/...` 相当の userId + サブ destination）** を `RelayEnvelope` に載せて `mannschaft:ws:relay:user` へ publish。
- 各ノードの `WebSocketRelaySubscriber` は受信後、**自ノードの `SimpMessagingTemplate.convertAndSendToUser(userId, subDestination, body)` を再実行**する。自ノードにそのユーザーのセッションがあれば解決・配信され、なければ 0 件（無害）。
- 発信ノードは originNodeId 一致で二重配信を防ぐ（発信ノードでは既に §4.2 の SimpleBroker 経路で配信済みのため）。
- **前提**: §2.3 の Principal 配線が全ノードで有効であること（各ノードの `SimpUserRegistry` が自ノード接続ユーザーを持つ）。→ Principal 根治は relay のユーザー宛配信の**必須前提**。

### 4.4 ループ防止（必須）

- `RelayEnvelope.originNodeId` に**発信ノードの一意 ID**（起動時生成 UUID または ECS タスク ID）を入れる。
- 受信側は `originNodeId == 自ノード ID` なら**破棄**（自分が publish したものを再注入しない）。
- 再注入したメッセージには**リレーマーカー**（メッセージヘッダ `X-Relay-Injected=true` 相当）を付与し、`WebSocketRelayPublisher` は**リレーマーカー付きメッセージを再 publish しない**（二重の安全弁：発信ノード ID 判定＋マーカー判定）。単一ノードで relay ON の段階 1 検証でも、この二重防止でループしないことを確認する。

### 4.5 メッセージ封筒（`RelayEnvelope`）

| フィールド | 内容 |
|---|---|
| `originNodeId` | 発信ノード ID（ループ防止）|
| `destination` | 配信先 destination（`/topic/...` またはユーザー宛の userId+subDestination）|
| `messageType` | `BROADCAST` / `USER` |
| `headers` | 再注入に必要な STOMP/simp ヘッダの最小セット（content-type 等）|
| `body` | ペイロード（JSON バイト列）|

シリアライズは既存キャッシュと同様 Jackson（`GenericJackson2JsonRedisSerializer` 系）を流用。ただし relay 封筒は型を固定（`RelayEnvelope`）できるため、キャッシュ用の `activateDefaultTyping` は不要（専用 ObjectMapper を用いる）。

---

## 5. 受け入れ条件（テスト可能・正常/異常/境界）

- **AC-1（マルチノード broadcast）**: 2 ノード相当環境で、ノード A に接続した購読者が、**ノード B 発の `convertAndSend`**（全 `/topic` 配信先・チャット/マッチ/コルクボード/緊急休業/サイネージ）を受信できる。
- **AC-2（マルチノード user・認可）**: `convertAndSendToUser` が、対象ユーザーの**接続ノードに関わらず**到達する。かつ**他ユーザーには漏洩しない**（ユーザー A 宛がユーザー B のセッションに届かない）。
- **AC-3（非回帰）**: relay flag OFF 時は、現行 SimpleBroker 挙動と**完全同一**（publish/subscribe を一切行わず、単一ノード配信のみ）。既存配信 UT が全て green のまま。
- **AC-4（フェイルオープン）**: Valkey 断（接続喪失）でアプリは**落ちず**、当該ノード内のローカル配信は**継続**する。Valkey 復旧後に中継が**自動再開**する（購読コンテナの再接続）。
- **AC-5（Principal 配線）**: CONNECT 後、`SimpUserRegistry` に接続ユーザーが**登録**される（§2.3 の欠陥実証テストが red→green 化）。`convertAndSendToUser` が単一ノードで対象ユーザーに到達する。
- **AC-6（ループ防止）**: 単一ノードで relay ON でも、自ノード publish の再注入で**無限ループ・二重配信が発生しない**（originNodeId 判定＋リレーマーカーで破棄）。
- **AC-7（境界: サイネージ）**: `/topic/signage/...` 配信もマルチノードで届く（単一ブローカーマージにより relay 対象に含まれることの確認）。

---

## 6. API 契約 / DDL / エラーコード / i18n

- **API 契約**: **REST 変更なし**。クライアントの購読 destination（`/topic/...`・`/user/.../queue/...`）は**全て不変**。STOMP エンドポイント（`/ws`・`/ws/signage`）・SockJS も不変。**FE 改修ゼロ**。OpenAPI（`docs/openapi.json`）への影響なし。
- **DDL / 状態遷移**: **不要**（Valkey Pub/Sub のみ・永続化なし）。**Flyway: 不要**（マイグレーション追加なし）。
- **エラーコード**: **新規業務エラーコード不要**。内部障害（Valkey 断・publish 失敗・再注入失敗）は**ログ＋メトリクス**で観測し、**フェイルオープン**（例外を握りつぶさず warn ログに記録するが HTTP/配信経路は巻き戻さない。既存 `NotificationDispatchService` の配信失敗ハンドリングと同じ best-effort 方針）。
- **i18n**: **不要**（ユーザー可視文言なし。中継は完全にバックエンド内部処理）。

> 障害対応の原則（CLAUDE.md）との整合: フェイルオープンは「症状を隠す握りつぶし」ではなく、**best-effort 配信という設計上の正しい振る舞い**（正本は HTTP・再取得可能）。ただし失敗は必ずログ＋メトリクスで可視化し、沈黙させない。

---

## 7. テスト戦略

### 7.1 結合テスト（既存パターン踏襲）

既存の Valkey/Redis 結合テスト（`common/ratelimit/ValkeyRateLimiterIntegrationTest`）の**手組みパターンを踏襲**する。この既存テストは意図的に `@SpringBootTest` を**使わず**（`AbstractMySqlIntegrationTest` との TestContext キャッシュ分裂回避のため）、以下を手動で組む:

- `private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)...`（`@Container` アノテーションは使わず static フィールド保持）。
- `@BeforeAll` で `REDIS.start()`、Docker 不在時は `@EnabledIf` + `Assumptions.abort(...)` でスキップ、`@AfterAll` で `stop()`、`@BeforeEach` で `flushAll()`。
- 接続は `new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort())` → `new LettuceConnectionFactory(...)` → `afterPropertiesSet()` を手動配線。

本 relay の結合テストは、この接続先に対して **2 つの `RedisMessageListenerContainer` / relay 部品（Publisher/Subscriber）を別 `nodeId` で 2 セット手組み**し、「ノード A 発 → Valkey → ノード B の SimpleBroker 再注入まで」を結合検証する（実 SimpleBroker への再注入まで含める）。

> この手組みは CLAUDE.md の一般的な試練規約（`@SpringBootTest(RANDOM_PORT)` + Testcontainers 自動採番）から**意図的に外れる**が、既存 Valkey 結合テストと同じ理由（TestContext キャッシュ分裂回避）による確立パターンであり、`ValkeyRateLimiterIntegrationTest` / `AdFrequencyCapIntegrationTest` と同流儀。ポートは `getFirstMappedPort()` で動的採番するため並行実行衝突はしない。

- ユーザー宛（AC-2）は、ノード B にのみ対象ユーザーのセッション/Principal がある状況を作り、到達と非漏洩を確認。

### 7.2 Principal 欠陥の実証（red 先行）

§2.3 の欠陥は**先に red テストで実証**する（憶測修正禁止）:
- **2 セッションテスト**: 実 STOMP クライアント 2 本で CONNECT し、一方の userId 宛に `convertAndSendToUser` を送る。**修正前は届かない（red）**ことを確認 → Principal 配線後に**届く（green）**。
- `SimpUserRegistry` に接続ユーザーが登録されることを直接アサート（`SimpUserRegistry.getUser(userId)` 非 null）。

### 7.3 非回帰（既存 UT はそのまま活きる）

配信元の既存 UT（`NotificationDispatchServiceTest`・`NotificationDispatchServiceVisibilityGuardTest` 等、`SimpMessagingTemplate` をモックして destination/ペイロードを検証するもの）は、**relay 導入後も `SimpMessagingTemplate` 抽象が不変のため非回帰でそのまま green**。relay は brokerChannel 下流に挿入され、発信元コードには一切触れない。

---

## 8. 非機能

### 8.1 フェイルオープン設計

- `RedisMessageListenerContainer` は接続喪失時に例外を投げず（Lettuce の再接続 + Spring の container 再起動）、購読を自動復旧。
- `WebSocketRelayPublisher` の publish 失敗は warn ログ + メトリクスのみ（配信経路を巻き戻さない）。
- **結果**: Valkey 断中は「マルチノード中継のみ停止」し、各ノードのローカル SimpleBroker 配信は継続。復旧後、購読再開で中継が自動的に戻る。アプリの可用性は Valkey に依存しない（キャッシュ層と同じ耐性設計思想）。

### 8.2 ループ防止

§4.4 の originNodeId 判定 + リレーマーカーの二重防止。単一ノード relay ON（段階 1）でも安全。

### 8.3 ファンアウト帯域見積り（N 倍配信の許容規模）

- ある配信 1 件は Valkey で N ノードに fan-out され、各ノードが自ノード購読者へ配る。Valkey pub/sub トラフィックは概ね **（配信件数 × N ノード × 平均ペイロードサイズ）**。
- 現状 destination の大半はチーム/チャネル/試合単位の限定購読で、1 配信あたりの購読者・配信頻度は限定的（07 §J.5「観戦者はチーム関係者中心で限定的」）。単一 Valkey ノードの pub/sub スループット（数十万 msg/s オーダー）に対し、当面のノード数 N（2〜数台）・配信頻度では**十分に余裕**。
- 揮発配信（タイピング・presence）は件数が出やすいため、**必要ならタイピング/presence を中継対象から除外**する設定余地を残す（揮発かつローカル体感で十分な場合、ノード跨ぎ中継を省く選択。段階 2 で計測して判断）。

### 8.4 監視メトリクス

- `relay.publish.count` / `relay.publish.failure`（発信側）
- `relay.receive.count` / `relay.receive.dropped`（自ノード発の破棄数）
- `relay.reinject.count` / `relay.reinject.failure`
- `relay.latency`（publish→他ノード再注入までの遅延。相関 ID で計測）
- Valkey 接続状態（container 稼働・再接続回数）
- 既存の Micrometer/監視基盤に載せる（新規ダッシュボードは運用整備時）。

### 8.5 Cloudflare Tunnel 経由の WebSocket

- ALB → Cloudflare Tunnel 化は軍議前提として並行進行中とされる。**ただし現時点の IaC（`infra/terraform/**`）・docs には `cloudflared` / Tunnel の記述は皆無**（偵察で検索網羅・ヒットゼロ）。現状の Cloudflare は「ALB を公開オリジンとするプロキシ構成」（ACM 検証・Always Use HTTPS 等）に留まり、Tunnel（オリジン非公開化）への移行は未文書化。本設計はこの移行の有無に**依存しない**。
- A 案は**セッションアフィニティに非依存**（どのノードに振られても全配信が揃う）ため、ALB プロキシでも Tunnel でも**相性が良い**。sticky session を前提にしない分、将来 Tunnel 移行のルーティング自由度を損なわない。
- WebSocket アップグレード（`Upgrade: websocket`）は経路（ALB / Tunnel いずれ）が透過する前提。SockJS フォールバック（XHR-streaming 等）も維持されるため、WS がブロックされてもフォールバックで継続。

### 8.6 IaC 連動（実装は別隊 = §9 隊 3）

`infra/terraform/modules/app/main.tf` の ECS サービス設定を、無停止ローリング対応へ変更する（現状 → 変更後）:

| 項目 | 現状 | 変更後 | 備考 |
|---|---|---|---|
| `deployment_minimum_healthy_percent` | `0` | `100` | ローリング中も稼働タスクを維持（断ゼロ）|
| `deployment_maximum_percent` | `100` | `200` | 新旧タスク並走を許可（relay で配信が揃うため安全）|
| deployment circuit breaker | なし | **追加**（`deployment_circuit_breaker { enable = true, rollback = true }`）| デプロイ失敗時の自動ロールバック |
| コメント（460-463 行の「WebSocket インメモリブローカーのため 2 タスク不可」）| — | **全面書き換え** | relay 導入で 2 タスク並走が可能になった旨に更新 |

- `desired_count` は段階移行に従い、段階 1 では `1` のまま（relay ON 検証）、段階 2 で `2` 以上へ。
- ElastiCache（`modules/data/main.tf` の `aws_elasticache_replication_group.valkey`）は**変更なし**（単一ノード維持）。
- 変更は Cloudflare Tunnel 隊と協調（同じ ECS サービス定義に触れるため、隊間で main への取り込み順序を調整）。

### 8.7 1000 万ユーザー / シャーディング方針との整合

- 単一 Valkey ノードの pub/sub は、ノード数・配信量が桁で増える段階（例: 数十ノード・大規模公開試合の数千同時観戦）で**スループット/ホットチャネルの限界**に達しうる。
- 将来の移行パス（本設計はこれらへの布石を壊さない）:
  1. **Valkey レプリカ追加**（`num_cache_clusters` 増 + `automatic_failover_enabled = true`）。pub/sub 自体は primary 依存だが、可用性向上。
  2. **Redis Cluster 化**（チャネルをスロット分散）。relay のチャネル命名 `mannschaft:ws:relay:*` は destination ハッシュでのチャネル分割に拡張可能。
  3. **専用ブローカー（C 案 = RabbitMQ StompBrokerRelay 等）へ移行**。A 案の relay は `SimpMessagingTemplate` 抽象の下流に閉じており、発信元コードは不変のまま差し替え可能（07 §J.5 の「ブローカー差し替え非依存」と同じ性質）。
- `db_scalability.md` のテナント/シャーディング方針（`organization_id` シャードキー）とは独立レイヤ。将来、配信も organization 単位でチャネル分割すればシャード境界と揃えられる（記録のみ・現スコープ外）。

---

## 9. 実装隊の分割案

| 隊 | 担当 | 主な成果物 |
|---|---|---|
| **隊 1: BE 中核** | relay 部品 + Principal 配線 + WebSocketConfig 是正 | `WebSocketRelayPublisher` / `WebSocketRelaySubscriber` / `RelayEnvelope` / `WebSocketRelayProperties` / `RedisMessageListenerContainer` Bean / `StompPrincipal` / `WebSocketAuthChannelInterceptor` への `setUser` 追加 / feature flag / §2.1 の順序依存是正（Config 集約）|
| **隊 2: 試練（テスト先行）** | 受け入れ条件 → red テスト | §7.2 の Principal 2 セッション red / §7.1 の 2 ノード結合テスト（`redis:7-alpine` Testcontainer 手組み）/ 非回帰確認 |
| **隊 3: IaC** | ECS deployment 設定 | §8.6 の `deployment_*` 変更 + circuit breaker + コメント書き換え（Cloudflare Tunnel 隊と協調）|
| **隊 4: サイネージ系統の確定対応** | §2.1 の実挙動確定 | 起動時 Configurer 順序の実測確定 → 単一ブローカーマージ前提の妥当性確認。設定集約が隊 1 に吸収できるなら隊 1 に統合（調査結果次第）|

> 順序: **隊 2（試練 red）→ 隊 1（green）→ 隊 3（IaC）**。Principal 根治は隊 1 の中核であり、隊 2 の red 実証が前提（BE/API テスト先行・`feedback_test_first_be_api`）。

---

## 付録: 未解決点 / 実装時に確定させること

- **A.1** `configureMessageBroker` 二重呼び出しの順序依存（§2.1）— 実測で現挙動を確定し、単一 Config へ集約して根絶。
- **A.2**（確定済み）`SignageWebSocketPublisher` は 2 destination（`/emergency`・`/update`）。`/emergency` は `SignageEmergencyMessageEntity` として DB 永続（timestamp のみ揮発）、`/update` は再取得トリガの揮発シグナル。いずれも**再描画可能**で at-most-once 許容（§2.2 表 #9a/#9b で確定）。
- **A.3** relay チャネルを単一集約 / destination 別分割のどちらにするか — 配信量メトリクスで決定（初期 2 チャネル）。
- **A.4** 揮発配信（タイピング・presence）を中継対象に含めるか除外可能にするか — 段階 2 で計測判断（§8.3）。
- **A.5** relay 再注入で購読認可（07 §J.3 の SUBSCRIBE インターセプタ）を**二重に通さない**こと — 再注入はブローカー配信（アウトバウンド）であり SUBSCRIBE フレームではないため認可は購読時に既に済んでいる。ただし送信時フィルタ（07 §J.3.3 の機微情報非包含）は relay 経路でも維持されることを確認（ペイロードは発信元で既にフィルタ済みのまま中継されるため追加対応不要の見込み・実装時確認）。
