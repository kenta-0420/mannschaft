# WebSocket 外部ブローカー化（Valkey Pub/Sub 中継）設計書

> **ステータス**: 🟢 設計完了（2026-07-10 精査第 1 パス＋第 2 パス＋実機 E2E 耐性クリティック反映済み）
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
| リアルタイム通知の実配信 | FE に存在しない `/user` 通知の受け口（購読 composable）を新設し、**実ブラウザ到達まで**担保（§2.5・隊 5）|
| コスト据え置き | 既存 ElastiCache Valkey（単一ノード）を流用し、追加インフラ費ゼロ |

---

## 1. 方式決定（マスター御裁可済み）

### 1.1 採用: A 案 — SimpleBroker + Valkey Pub/Sub ファンアウト中継

各ノードの SimpleBroker は**維持**する。ノードで発生した配信メッセージ（brokerChannel に流れるメッセージ）を Valkey チャネルへ publish し、**全ノードがそのチャネルを購読**、受信ノードは自ノードの SimpleBroker にメッセージを**再注入**する。これにより、どのノードに接続していても全ノード発の配信が届く。

- クライアントの購読 destination（`/topic/...`・`/user/.../queue/...`）は**一切変更しない**。既存の `/topic` 系 4 購読（chat / match live / corkboard / emergency-closure）は **FE 改修ゼロ**。ただし `/user` 通知は **FE に購読実装が存在しない**（§2.5）ため、**FE 購読実装（最小結線）が本設計の必須スコープ**（隊 5）。
- STOMP エンドポイント（`/ws`・`/ws/signage`）は不変（`.withSockJS()` サーバー設定は現状維持だが、FE は SockJS 未使用・§8.5）。
- ロードバランサのセッションアフィニティ（sticky session）に**非依存**。どのノードに振られても配信が揃う（後述 Cloudflare Tunnel 移行と相性良）。

### 1.2 却下案と理由

| 案 | 概要 | 却下理由 |
|---|---|---|
| **B 案: セッションレジストリ方式** | 全ノードの STOMP セッション（userId→nodeId→sessionId）を Valkey に集中登録し、送信側が宛先ノードを引いて直接転送する | **複雑度過剰**。セッションのライフサイクル（接続/切断/障害）を分散状態として厳密管理する必要があり、整合性バグの温床。SimpleBroker の宛先解決を自前再実装することになり保守コストが高い |
| **C 案: RabbitMQ StompBrokerRelay** | `enableStompBrokerRelay()` で外部 STOMP ブローカー（RabbitMQ 等）に委譲する Spring 標準構成 | **新規インフラ費がコスト削減方針と矛盾**。RabbitMQ クラスタの新規構築・運用（HA・監視・パッチ）が発生する。既存 Valkey で足りる規模で新ミドルウェアを増やさない |

> A 案は「既存資産（SimpleBroker + Valkey）の最小拡張で最大効果」を狙う中庸解であり、B/C の将来移行余地も残す（後述 §8 の移行パス）。

### 1.3 段階移行（安全側既定）

feature flag `mannschaft.websocket.relay.enabled`（既定 `false`）で relay の ON/OFF を切り替える。本番での供給は**環境変数 `MANNSCHAFT_WEBSOCKET_RELAY_ENABLED`**（Spring relaxed binding）を **terraform の task definition `environment` ブロック**（`infra/terraform/modules/app/main.tf` — `SPRING_REDIS_HOST` 等と同じ箇所）に追加して行う。

1. **段階 1**: `desired_count = 1` のまま relay を ON にして検証（単一ノードでも publish→自ノード購読→再注入のループが健全に回る＝ループ防止が効くことを本番相当で確認）。
2. **段階 2**: `desired_count` を 2 以上に増やし、`deployment_*` を無停止設定へ変更（§8.6）。

flag OFF 時は `@ConditionalOnProperty` で relay 部品（`WebSocketRelayPublisher`・`WebSocketRelaySubscriber`・relay 用 `RedisMessageListenerContainer`）を **Bean ごと生成しない**（「生成して購読だけしない」方式は不可 — 購読接続・スレッドが残り、現行との完全同一挙動＝非回帰にならない）。OFF 時の挙動は現行 SimpleBroker と完全同一。

> **flag OFF→ON 切替の注意（段階 1 の検証条件）**: Principal 配線（§2.3）は relay flag に**依存しない常時有効の修正**（既存バグの根治）だが、CONNECT 時にのみ行われるため、配線を含むリリース適用前から接続し続けているセッションには**遡及できない**。ユーザー宛配信が有効になるのは**再接続後**。段階 1 の AC-5 検証は「リリース/切替後に再接続したセッション」で行うこと。

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

> **潜在的脆弱性（実装スコープで是正）**: `configureMessageBroker` は両 Configurer で呼ばれ、**同一の `MessageBrokerRegistry` を二重に変更**する。`enableSimpleBroker(...)` は registry 内の登録を上書きするため、`WebSocketConfig`（`/topic`+`/queue`）と `SignageWebSocketConfig`（`/topic` のみ）の**呼び出し順序で最終的なブローカー設定が変わる**（順序依存・`@Order` 未指定で非決定的）。仮にサイネージ側が後勝ちすると `/queue` がブローカーに登録されず、`convertAndSendToUser` の `/queue/...` 配信が壊れうる。実装隊は**起動時に実 Configurer 順序を列挙して現挙動を確定**し（家訓「憶測修正禁止」）、以下の**具体形で集約**して順序依存を根絶すること: **`SignageWebSocketConfig` から `configureMessageBroker` を削除**し、ブローカー設定（`enableSimpleBroker("/topic","/queue")`・`/app`・`/user`）は `WebSocketConfig` の**単一 Registry 宣言に一本化**する（Signage 側には `registerStompEndpoints`（`/ws/signage`）のみ残す）。あわせて Signage の `setAllowedOriginPatterns("*")` と `WebSocketConfig` のオリジン制限（`mannschaft.allowed-origins`）の**食い違いも同集約タスクで統一**する（`"*"` は CSRF 対策方針と不整合のため許可オリジン方式へ寄せる）。Javadoc「本番は Valkey 想定」（`WebSocketConfig`）は実態と乖離しているため、実装時に是正する。

### 2.2 配信箇所の全数（relay の対象）

全配信は `SimpMessagingTemplate`（Spring 自動登録の単一 Bean）経由。`convertAndSend`（ブロードキャスト）・`convertAndSendToUser`（ユーザー個別）を全文検索して列挙。

| # | 発信元クラス | destination | ペイロードの性質 | at-most-once 許容根拠 |
|---|---|---|---|---|
| 1 | `chat/service/ChatMessagePublisher`（81 行）| `/topic/channels/{channelId}` | DB 永続済み（chat_messages）| 再取得可能（メッセージ履歴 API）|
| 2 | `chat/service/ChatChannelEventPublisher`（66 行）| `/topic/channels/{channelId}/events` | 状態変化イベント（kicked/deleted/archived 等）= DB 状態反映 | 再取得可能 |
| 3 | `chat/controller/ChatTypingController`（69 行）| `/topic/channels/{channelId}`（タイピング）| 揮発（`TypingPayload`・非永続）| 揮発許容（表示の一時状態）|
| 4 | `match/live/MatchLiveBroadcastListener`（76 行）| `/topic/matches/{matchId}/live` | DB 永続済みの差分（HTTP 正本・07 §J.1）。`serverSeq` は**ノードローカル `AtomicLong`（52 行）— マルチノードで単調性破綻・§4.6 で根治** | 再取得可能（スナップショット再取得・07 §J.4）|
| 5 | `village/service/VillageLobbyPresenceService`（123 行）| `/topic/villages/{villageId}/lobby/presence` | 揮発（在席 = Valkey TTL キー由来・DB 非永続）| 揮発許容・REST `getPresence` で再取得可 |
| 6 | `corkboard/event/CorkboardEventListener`（62 行）| `/topic/corkboard/{boardId}` | DB 永続済み（AFTER_COMMIT）| 再取得可能 |
| 7 | `reservation/EmergencyClosureBroadcastListener`（78 行）| `/topic/teams/{teamId}/emergency-closures/{closureId}/confirmations` | DB 永続済みの確認サマリ（AFTER_COMMIT）| 再取得可能 |
| 8 | `notification/service/NotificationDispatchService`（141 行）| `/user/{userId}/queue/notifications`（`convertAndSendToUser`・唯一のユーザー宛）| DB 永続済み（notifications 行）| 再取得可能（通知一覧 API）|
| 9a | `signage/websocket/SignageWebSocketPublisher`（33 行）| `/topic/signage/{screenId}/emergency` | 緊急メッセージ（`SignageEmergencyMessageEntity` は DB 永続・timestamp は揮発）| 再描画可能 |
| 9b | 同上（48 行）| `/topic/signage/{screenId}/update` | 揮発シグナル（`type=SLIDE_UPDATE`＝クライアント再取得トリガ・データ本体なし）| 揮発許容・再取得可能 |

> **配信保証**: 中継は **at-most-once（fire-and-forget）**。全配信先が「DB 永続済み・再取得可能」または「揮発許容」のいずれかであることを上表で確認済み。WebSocket は best-effort 配信であり、正本は HTTP（07 §J.1）という既存原則と整合する。
>
> **presence（#5）は relay 必須**: 入退室イベント（特に切断による退室）は**接続していたノードでしか発生しない**ため、relay しないと他ノード接続の購読者の在席表示が恒久的にズレる（次の TTL 失効/再入室まで腐る）。§8.3 の中継除外候補は presence を含まず**タイピング（#3）のみ**。

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
1. CONNECT 時に `WebSocketAuthChannelInterceptor` で **Principal を確立**する。JWT から得た `userId` を名前に持つ軽量 Principal（`StompPrincipal implements java.security.Principal`・`getName()` = userId 文字列の新規クラス）を `accessor.setUser(...)` で設定する。**実装上の地雷（必読・Y-1）**: 現行コードは `StompHeaderAccessor.wrap(message)` で**コピー**を作って操作し、元の message をそのまま return している。ここに素朴に `setUser` を足しても**元メッセージに反映されず Principal は確立しない**。`MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)` で**実アクセサ（可変）**を取得して `setUser` するか、それが不可（アクセサが不変）の場合は `MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders())` で**組み直したメッセージを return** する。
2. これにより **`SimpUserRegistry` に接続ユーザーが登録**され、`convertAndSendToUser` が `/user/{userId}/queue/...` を当該ユーザーの全セッションへ解決できるようになる。
3. 既存の `sessionAttributes` の userId は購読認可インターセプタ（`MatchLiveSubscriptionInterceptor` 等・07 §J.3）が参照しているため**併存を維持**（後方互換）。Principal 追加は副作用なく上乗せできる。
4. マルチノード時のユーザー宛配信は、relay が `/user/...` 解決後の実 destination（セッション固有）ではなく **ユーザー宛の論理 destination を中継**することで、受信ノード側の `SimpUserRegistry`（そのノードに接続中のセッション）で解決する（§4.3 参照）。
5. **同一ソース原則（AC-5 でアサート）**: `sessionAttributes` の userId と Principal 名（`getName()`）は、**同一の JWT 検証結果から同時に設定**する（別経路から取らない）。`/user/{userId}/queue/notifications` には SUBSCRIBE 認可インターセプタが存在せず、**Principal の正当性がユーザー宛配信の唯一の防壁**であるため、両値の一致を不変条件とする。
6. **匿名接続（CONNECT フェイルオープン）**: Authorization ヘッダなし/無効の接続には Principal を設定しない（現行の匿名許容方針は不変）。Principal 不在セッションは `SimpUserRegistry` に登録されず、`/user` 宛解決 0 件で**無害**（匿名者にユーザー宛通知が漏れることはない）。

### 2.4 Valkey 現状（pub/sub 未使用 → 新設が必要）

`config/RedisConfig` は **`RedisCacheManager`（キャッシュ）のみ**。pub/sub（`RedisMessageListenerContainer` / チャネル publish）は**皆無**。したがって購読専用の `RedisMessageListenerContainer`（Lettuce・購読専用接続）の**新設が必要**。

キャッシュのキー規約は `mannschaft:cache:{name}:`。本設計の Pub/Sub チャネルは同規約に合わせ **`mannschaft:ws:relay:*`** 系を用いる（§4.2）。

ElastiCache は `aws_elasticache_replication_group.valkey`（`engine = "valkey"`・`num_cache_clusters = 1`・`automatic_failover_enabled = false`・port 6379・at-rest 暗号化 ON / **transit 暗号化 OFF**）。**単一ノードを維持**（ノード数の構成変更なし。ただし transit 暗号化は **relay 有効化の前提として ON 化**する — PII 平文経路の遮断・§8.6）。接続は Spring `spring.data.redis`（Lettuce）・`SPRING_REDIS_HOST/PORT` 環境変数（terraform 供給）。

### 2.5 FE 側の購読実装の現状 — `/user` 通知は受け口が存在しない（🔴・実コード裏取り済み）

origin/main の FE 実コードを裏取りした結果:

- WS 購読の composable は **4 つ**で、いずれも `/topic` 系のみ: `useChatWebSocket` / `useMatchLiveSpectator` / `useCorkboardEventListener` / `useEmergencyClosureLive`（すべて生 `new WebSocket()` で `/ws` に直結・sockjs-client 不使用）。
- **`/user/queue/notifications` を購読するコードは FE 全体に存在しない**。`useNotificationStore.setLatestNotification` は**定義のみで呼び出し元ゼロ**（doc コメントに destination 名が書かれているだけ）。通知の唯一の読者 `WidgetAdminBusinessAlert.vue` は **60 秒ポーリング**で動いている。

→ BE の Principal 根治（§2.3）で配信が成立しても、**FE に受け口が無ければ実ユーザーには何も届かない**。本設計に **FE 通知購読隊（隊 5）** を含める: ログイン後にグローバル WS 接続を確立して `/user/queue/notifications` を SUBSCRIBE し、受信ペイロードを `useNotificationStore.setLatestNotification` へ流す composable を新設する。**既存 store / widget が受け皿になるため新 UI は不要の最小結線**である（AC-9）。

### 2.6 既存 IDOR: チャット SUBSCRIBE 認可の欠落（マスター御裁可済みスコープ追加・S7）

SUBSCRIBE 認可インターセプタが存在するのは現状 **2 destination のみ**（`MatchLiveSubscriptionInterceptor`・`EmergencyClosureSubscriptionInterceptor`）。チャットチャネル `/topic/channels/{channelId}` には **SUBSCRIBE 認可が無く**、認証済みユーザーが任意の channelId を購読すると**他チームのチャット本文を受信できる既存 IDOR** がある（マルチノード化以前からの欠陥）。

マスター裁可により**本戦役に隊を追加して塞ぐ**（隊 6）: 既存 2 インターセプタと同パターンの `ChatChannelSubscriptionInterceptor`（チャネルメンバーシップ検査・非メンバーは購読拒否）を新設する（AC-11・red 先行）。認可 E2E は使い捨てチャネルの新規作成が頑健（`feedback_authz_e2e_seed_membership_pollution`）。

---

## 3. 決定事項サマリ（マスター御裁可済み）

1. **A 案採用**（SimpleBroker 維持 + Valkey Pub/Sub ファンアウト）。B 案（複雑度過剰）・C 案（新規インフラ費が方針矛盾）は却下。
2. **Principal 未配線の根治を本設計スコープに含める**（§2.3）。
3. **ElastiCache Valkey は単一ノード維持**（追加費用ゼロ）。Valkey 断は**フェイルオープン**（リアルタイム中継のみ一時停止・アプリ継続・自動復旧）。将来レプリカ追加可能（§8）。
4. **段階移行**（feature flag `mannschaft.websocket.relay.enabled`）。①`desired_count=1` のまま relay ON→検証 → ②2 タスク化。安全側既定（既定 OFF）。

5. **チャット SUBSCRIBE 認可隊の追加**（精査第 2 パス S7 で発見・マスター御裁可済み）: `/topic/channels/{channelId}` の既存 IDOR を本戦役で閉塞する（§2.6・隊 6）。
6. **serverSeq のノード横断採番**（精査第 2 パス R-1）: `MatchLiveBroadcastListener` のノードローカル `AtomicLong` を **Valkey INCR** に昇格する（§4.6・隊 1）。

> **追加前提（精査第 1 パスで確定）**: relay 有効化より前に **Valkey の転送時暗号化（TLS）を有効化**する（§8.6）。現状 `transit_encryption_enabled = false` のため、relay を先に ON にするとチャット本文・通知本文（PII）が**平文で Valkey を流れる新経路**が生まれる。TLS 化は隊 3（IaC）のスコープであり、**その適用可否確定（ダウンタイム有無）は段階 1 着手前のゲート**（§8.6・A.7）。

---

## 4. 中継設計（relay）

### 4.1 コンポーネント構成（クラス名は英語）

| コンポーネント | 責務 |
|---|---|
| `WebSocketRelayProperties` | `mannschaft.websocket.relay.enabled` の束ね（**プロパティは `enabled` のみ**。チャネル名はコード内定数・nodeId は起動時生成のためプロパティ化しない・§4.2/§4.4）|
| `WebSocketRelayPublisher` | 自ノードの brokerChannel（アウトバウンド配信）に流れたメッセージを捕捉し、`RelayEnvelope` にラップして Valkey チャネルへ publish（捕捉規則は §4.2.1 — (iii) 解決済みユーザー宛は対象外）|
| `WebSocketRelaySubscriber` | `RedisMessageListenerContainer` で Valkey チャネルを購読し、受信 `RelayEnvelope` を自ノードの SimpleBroker に再注入 |
| `RelayEnvelope` | 中継メッセージの封筒（後述フィールド）。ループ防止用の発信ノード ID を含む |
| `StompPrincipal` | `java.security.Principal` 実装（`getName()` = userId 文字列）。§2.3 の Principal 配線用 |
| `RedisMessageListenerContainer`（Bean 新設）| Lettuce の購読専用接続。`WebSocketRelaySubscriber` を購読者として登録 |

配置は **`com.mannschaft.app.websocket.relay` 新規パッケージ**とする（`config` パッケージの肥大回避）。`StompPrincipal` は Principal 配線の一部として `com.mannschaft.app.websocket` に置き、`WebSocketAuthChannelInterceptor` から参照する。relay 部品（Publisher / Subscriber / ListenerContainer）は `@ConditionalOnProperty(prefix = "mannschaft.websocket.relay", name = "enabled", havingValue = "true")` で **flag OFF 時は Bean 不生成**（§1.3）。

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

> チャネルは上記 **2 チャネルに確定**し、チャネル名は**コード内定数（`static final String`）で固定**する（プロパティ化しない — 全ノードが同一値であることを設定ミスの余地なく担保する）。destination 別のさらなるチャネル分割は将来最適化（§8.3 の実測後）であり、**本設計スコープでは行わない**。

### 4.2.1 捕捉点の厳密化 — ユーザー宛先解決との衝突回避【必須】

brokerChannel には **3 種のメッセージ**が流れる。素朴に「brokerChannel を全部捕捉」すると (ii)(iii) を二重に拾い、**二重配信＋他ノードへのセッション固有 destination のばら撒き**になる:

| 種別 | destination 例 | 発生元 | relay 対象 |
|---|---|---|---|
| (i) broadcast | `/topic/channels/42` | `convertAndSend` | **中継する**（BROADCAST）|
| (ii) ユーザー宛・未解決 | `/user/123/queue/notifications` | `convertAndSendToUser` 直後 | **中継する**（USER）|
| (iii) ユーザー宛・解決済み | `/queue/notifications-user{sessionId}` | `UserDestinationMessageHandler` が `SimpUserRegistry` で解決し brokerChannel へ**再送**したもの | **中継しない（無視）** |

- (iii) の sessionId は**発信ノードローカル**であり、他ノードでは無意味。中継すると (ii) と (iii) の両方が飛んで二重配信になる。
- **判定規則（固定）**: destination が `/topic/` 始まり → BROADCAST 中継。`/user/` 始まり（未解決）→ USER 中継。**それ以外（解決済み `/queue/...-user*` を含む）→ 中継しない**。
- **実装点と登録順（Y-2）**: 捕捉は brokerChannel の `ChannelInterceptor`（`preSend`・メッセージ非改変）で行う。**注意: brokerChannel への interceptor 登録に `@Order` は効かない**（`WebSocketMessageBrokerConfigurer` に brokerChannel 用の configure コールバックは存在しない）。**brokerChannel Bean を `@Qualifier("brokerChannel")` 付きで `AbstractMessageChannel` として注入し、初期化フック（`SmartInitializingSingleton` / `@PostConstruct`）で `addInterceptor(...)` を明示呼び出し**して登録する。順序は**挿入順**（既存登録の後尾に追加＝最終段）であり、アノテーション順序に依存しない。

### 4.3 ユーザー宛（`convertAndSendToUser`）の中継

ユーザー宛は destination 解決の性質が異なる。`convertAndSendToUser(userId, "/queue/notifications", payload)` は、発信ノードで `SimpUserRegistry`（そのノードに接続中のセッションのみ）を引いてセッション固有 destination に解決する。**ユーザーが別ノードに接続している場合、発信ノードの registry には存在せず解決先 0 件**になる。

中継設計:
- `WebSocketRelayPublisher` は、ユーザー宛の**論理 destination（`/user/{userId}/queue/...` 相当の userId + サブ destination）** を `RelayEnvelope` に載せて `mannschaft:ws:relay:user` へ publish。
- 各ノードの `WebSocketRelaySubscriber` は受信後、自ノードで `convertAndSendToUser` を**再実行**する。ただし**素の再実行は禁止** — 新規メッセージが生成されて relay マーカーが載らず、そのノードの Publisher が (ii) 未解決メッセージとして再捕捉→再 publish する**ループ**になる。**必ず `convertAndSendToUser(userId, subDestination, body, headersWithRelayMarker)` のヘッダ付きオーバーロードで relay マーカーを明示的に載せて再実行**する（§4.4）。自ノードにそのユーザーのセッションがあれば解決・配信され、なければ 0 件（無害）。
- 発信ノードは originNodeId 一致で二重配信を防ぐ（発信ノードでは既に §4.2 の SimpleBroker 経路で配信済みのため）。
- **前提**: §2.3 の Principal 配線が全ノードで有効であること（各ノードの `SimpUserRegistry` が自ノード接続ユーザーを持つ）。→ Principal 根治は relay のユーザー宛配信の**必須前提**。

### 4.4 ループ防止（必須）

- `RelayEnvelope.originNodeId` に**発信ノードの一意 ID = 起動時生成 UUID（String）に一本化**（ECS タスク ID 案は廃止 — 取得経路の環境依存とローカル再現の複雑化を避ける）を入れ、受信側は `originNodeId == 自ノード ID` なら**破棄**（自分が publish したものを再注入しない）。
- **リレーマーカー**（メッセージヘッダ `X-Relay-Injected=true` 相当）を再注入メッセージに必ず付与し、`WebSocketRelayPublisher` は**マーカー付きメッセージを publish しない**。付与は**二経路それぞれで仕様化**する:
  - **broadcast 経路**: SimpleBroker への直接再注入メッセージにマーカーヘッダを付与して送出する。
  - **user 経路**: 再実行は `convertAndSendToUser(..., headersWithRelayMarker)` の**ヘッダ付きオーバーロード**で行う（素の再実行では新規生成メッセージにマーカーが載らず再捕捉ループになる・§4.3）。マーカーは (ii) 未解決メッセージの段階で Publisher に検知され publish が抑止される。`UserDestinationMessageHandler` の解決ホップ後もヘッダが保持されるかは実装時にテストで確認する（仮に保持されなくても、(iii) 解決済みメッセージは §4.2.1 の destination 判定で無条件に中継対象外のため防御は破れない）。
- 防御は**三重**（originNodeId 判定・リレーマーカー判定・§4.2.1 の destination 判定）。単一ノードで relay ON の段階 1 検証でも、この防止でループ・二重配信しないことを確認する（AC-6）。

### 4.5 メッセージ封筒（`RelayEnvelope`）— 確定 JSON スキーマ

#### 4.5.1 スキーマ（S5・実装者の裁量余地ゼロ）

キー名は **camelCase 固定**。シリアライズは relay 専用 `ObjectMapper`（命名戦略は Jackson デフォルト = camelCase・`FAIL_ON_UNKNOWN_PROPERTIES=false` で**未知フィールドは無視**＝封筒の前方互換）。キャッシュ用の `activateDefaultTyping` は**使わない**（デシリアライズ型は `RelayEnvelope` に固定）。

| キー | 型 | 内容 |
|---|---|---|
| `originNodeId` | String（UUID）| 発信ノード ID（起動時生成 UUID・ループ防止）|
| `messageType` | String（`"BROADCAST"` / `"USER"`）| 中継種別 |
| `destination` | String | BROADCAST: `/topic/...` 全体。USER: サブ destination（`/queue/notifications` 等）|
| `userId` | String / null | USER のみ。宛先ユーザー ID（`convertAndSendToUser` の第 1 引数）。BROADCAST では null |
| `contentType` | String | ペイロードの MIME（実質 `application/json` 固定）|
| `body` | String（Base64）| ペイロードのバイト列（Base64 エンコード）|

実例（BROADCAST）:

```json
{
  "originNodeId": "3f9b2c9e-6a1d-4b7f-9c1e-2d8a5b4c7e10",
  "messageType": "BROADCAST",
  "destination": "/topic/channels/42",
  "userId": null,
  "contentType": "application/json",
  "body": "eyJpZCI6MTIzLCJ0ZXh0Ijoi44GT44KT44Gr44Gh44GvIn0="
}
```

#### 4.5.2 再注入時に必須のヘッダ（完全列挙・W-1）

受信ノードの再注入で設定するヘッダは以下で**全部**である（sessionId / subscriptionId は SimpleBroker が購読者ごとに付与するため封筒に含めない）:

| ヘッダ | 値 | 設定方法 |
|---|---|---|
| `simpMessageType` | `SimpMessageType.MESSAGE`（固定）| `SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE)` で生成 |
| destination（`simpDestination`）| 封筒の `destination` | `accessor.setDestination(...)` |
| `contentType` | 封筒の `contentType` | `accessor.setContentType(MimeType.valueOf(...))` |
| `X-Relay-Injected` | `true` | リレーマーカー（§4.4）。`accessor.setHeader(...)` |

### 4.6 MatchLive `serverSeq` のノード横断採番（根治・R-1）

`MatchLiveBroadcastListener` の `serverSeq` は**ノードローカルの `AtomicLong`**（52 行・配信ごとに `incrementAndGet()`）である。マルチノード化すると同一試合の配信が**ノードごとの独立した 2 系列の seq** を持ち、観戦者は混在受信で**単調性が崩れる**。07 §J.4 の「seq の飛び検知→スナップショット再取得」が誤発火し続け（スラッシング）、リアルタイム観戦が実質不能になる。

**是正（設計決定）**: seq 採番を **Valkey `INCR`（試合単位キー）によるノード横断の単調採番**へ昇格する（既存 Valkey 利用の延長・根治）。

- キー: `mannschaft:ws:matchseq:{matchId}`。`INCR` の戻り値をそのまま `serverSeq` に使用（初回 INCR = 1）。
- TTL: 配信のたびに `EXPIRE` を更新し、**最終配信から 24 時間で失効**（試合終了後の自然クリーンアップ。専用の削除バッチ不要）。
- **Valkey 断時の挙動（フェイルオープン方針と整合）**: 断中は採番不能のため、**MatchLive の WS 配信のみスキップ**する（warn ログ＋メトリクス `matchlive.serverseq.skipped`（Counter）。他 destination の配信・HTTP 経路は無影響）。観戦者には差分が届かなくなるが、正本は HTTP（07 §J.1）であり再接続/スナップショット再取得（07 §J.4）で回復する。断中は relay 自体も停止しておりマルチノード配信は元々成立しないため、「ローカル独自採番で配り続ける」より**配信を止めて HTTP に委ねる方が seq 汚染がなく一貫**する。
- **Valkey 復旧後**: キーが残存していれば継続採番。キー消失（Valkey 再起動等）時は seq が巻き戻るが、クライアントは 07 §J.4 の非単調検知でスナップショット再取得を 1 回行い**自己回復**する（許容・追加実装不要）。

担当は**隊 1（BE 中核）に含める**（§9）。受け入れ条件は AC-10。

---

## 5. 受け入れ条件（テスト可能・正常/異常/境界）

- **AC-1（マルチノード broadcast）**: 2 ノード相当環境で、ノード A に接続した購読者が、**ノード B 発の `convertAndSend`**（全 `/topic` 配信先・チャット/マッチ/コルクボード/緊急休業/**村ロビー presence**/サイネージ）を受信できる。presence は中継除外不可（§2.2 注記）。
- **AC-2（マルチノード user・認可）**: `convertAndSendToUser` が、対象ユーザーの**接続ノードに関わらず**到達する。かつ**他ユーザーには漏洩しない**。検証手順を固定する: ユーザー A を**ノード B のみ**に接続し、**ノード A から** A 宛に送信 → ノード A 上の別ユーザー（受信者 C）には届かず、ノード B の A に届くこと。実機では判定前に**両セッションが別ノードに接続していることを node-id 観測（§7.4.2）で必ず確認**する（同一ノードに割れると relay を通らず**偽陰性の緑**になるため）。
- **AC-3（非回帰）**: relay flag OFF 時は、現行 SimpleBroker 挙動と**完全同一**（relay 部品は `@ConditionalOnProperty` で Bean 不生成・publish/subscribe を一切行わない）。既存配信 UT が全て green のまま。
- **AC-4（フェイルオープン）**: Valkey 断（接続喪失）でアプリは**落ちず**、当該ノード内のローカル配信は**継続**する。Valkey 復旧後に中継が**自動再開**する（購読コンテナの再接続）。
- **AC-5（Principal 配線）**: CONNECT 後、`SimpUserRegistry` に接続ユーザーが**登録**される（§2.3 の欠陥実証テストが red→green 化）。`convertAndSendToUser` が単一ノードで対象ユーザーに到達する。あわせて `sessionAttributes` の userId と Principal 名（`getName()`）が**同一の JWT 検証結果由来で必ず一致**することをアサートする（§2.3 是正設計 5 — `/user` 宛には SUBSCRIBE 認可が無く Principal 正当性が唯一の防壁のため）。
- **AC-6（ループ防止）**: 単一ノードで relay ON でも、自ノード publish の再注入で**無限ループ・二重配信が発生しない**（originNodeId 判定＋リレーマーカー＋destination 判定の三重防御・§4.4）。user 経路の再実行（ヘッダ付きオーバーロード）も再捕捉されないこと。
- **AC-7（境界: サイネージ）**: `/topic/signage/...` 配信もマルチノードで届く（単一ブローカーマージにより relay 対象に含まれることの確認）。
- **AC-8（無停止デプロイの観測）**: 段階 2 の設定変更後、ローリングデプロイ中に維持していた WS 接続で「切断→クライアント自動再接続」以外の断が発生しない。デプロイ中の HTTP 5xx ゼロ・deployment circuit breaker 非発火。**本 AC は red テストに落とせない「段階 2 の運用検証項目」であり、試練（自動テスト）対象外として区分する**。
- **AC-9（FE 実ブラウザ到達）**: **実ブラウザ**（実アプリの購読コード＝隊 5 の composable 経由。テスト用 STOMP クライアント不使用）で `/user/queue/notifications` の通知が到達し、`useNotificationStore` に反映される（実機 E2E・§7.4。手順: **フルリロードで新規 CONNECT させてから通知を発火** — Principal 配線は CONNECT 時のみ・S8）。
- **AC-10（serverSeq 単調性）**: マルチノード（2 ノード）で観戦クライアントが受信する `serverSeq` が**単調増加**である（**red: 現行 `AtomicLong` のままでは 2 系列混在により減少が観測される**・§4.6）。
- **AC-11（チャット SUBSCRIBE 認可）**: 非メンバーの `/topic/channels/{channelId}` SUBSCRIBE が**拒否**される（**red 先行**・§2.6）。メンバーは購読成立（非回帰）。

---

## 6. API 契約 / DDL / エラーコード / i18n

- **API 契約**: **REST 変更なし**。クライアントの購読 destination（`/topic/...`・`/user/.../queue/...`）は**全て不変**。STOMP エンドポイント（`/ws`・`/ws/signage`）も不変。**既存の `/topic` 系 4 購読（chat / match live / corkboard / emergency-closure）は FE 改修ゼロ**。OpenAPI（`docs/openapi.json`）への影響なし。
  - **ただし `/user` 通知は FE に購読実装が存在しない**（§2.5 — `setLatestNotification` は呼び出し元ゼロ・唯一の読者は 60 秒ポーリング）。**FE 購読 composable の新設（最小結線・新 UI 不要）が本設計の必須スコープ**（隊 5・AC-9）。BE の Principal 根治（§2.3）と両方が揃って初めて「リアルタイム通知」が実ユーザーに届く。通知トースト・バッジ等の表示頻度が実際に変わる挙動変化として FE 隊へ周知し、実機 E2E の確認項目に含める。
- **DDL / 状態遷移**: **不要**（Valkey Pub/Sub のみ・永続化なし）。**Flyway: 不要**（マイグレーション追加なし）。
- **エラーコード**: **新規業務エラーコード不要**。内部障害（Valkey 断・publish 失敗・再注入失敗）は**ログ＋メトリクス**で観測し、**フェイルオープン**（例外を握りつぶさず warn ログに記録するが HTTP/配信経路は巻き戻さない。既存 `NotificationDispatchService` の配信失敗ハンドリングと同じ best-effort 方針）。
- **i18n**: **不要**（ユーザー可視文言なし。中継は完全にバックエンド内部処理）。

> 障害対応の原則（CLAUDE.md）との整合: フェイルオープンは「症状を隠す握りつぶし」ではなく、**best-effort 配信という設計上の正しい振る舞い**（正本は HTTP・再取得可能）。ただし失敗は必ずログ＋メトリクスで可視化し、沈黙させない。

---

## 7. テスト戦略

### 7.1 結合テスト — 二方式の役割分担

手組み Lettuce 流儀（rate limiter パターン）では **SimpleBroker + SimpUserRegistry + UserDestinationMessageHandler を含む実 STOMP スタックの 2 ノード構成は組めない**（これらは `@EnableWebSocketMessageBroker` の構成一式として起動する必要がある）。方式を AC ごとに分担する。

#### 7.1.1 AC-1 / AC-6（broadcast relay 経路の中核部品）— 手組み検証

既存 `common/ratelimit/ValkeyRateLimiterIntegrationTest` の手組みパターンを踏襲（`@SpringBootTest` 不使用の理由 = `AbstractMySqlIntegrationTest` との TestContext キャッシュ分裂回避）:

- `GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)`（static フィールド保持・`@Container` 不使用）。`@BeforeAll` で start、Docker 不在時は `@EnabledIf` + `Assumptions.abort(...)` でスキップ、`@AfterAll` で stop、`@BeforeEach` で `flushAll()`。
- `new RedisStandaloneConfiguration(host, getFirstMappedPort())` → `new LettuceConnectionFactory(...)` → `afterPropertiesSet()` を手動配線（ポート動的採番のため並行実行衝突なし）。
- この接続先に relay 部品（Publisher / Subscriber + `RedisMessageListenerContainer`）を**別 nodeId で 2 セット手組み**し、「ノード A 発 publish → Valkey → ノード B 受信 → 再注入呼び出し」を検証する。SimpleBroker 本体はモックとし、**再注入の呼び出し内容（destination・ヘッダ・リレーマーカー）まで**を検証範囲とする。originNodeId 破棄・マーカー付きメッセージの publish 抑止（AC-6）・`RelayEnvelope` シリアライズもここで検証。

#### 7.1.2 AC-1 実到達 / AC-2 / AC-5 / AC-7 / AC-10 / AC-11（実 STOMP スタック）— 2 コンテキスト @SpringBootTest

- 単一 JVM 内で `@SpringBootTest(webEnvironment = RANDOM_PORT)` 相当のフルコンテキストを **2 つ**起動し、**1 個の Valkey Testcontainer を共有**する。nodeId は起動時 UUID 生成（§4.4）のため**両コンテキストで自然に異なり**、プロパティ指定は不要。ポートは RANDOM_PORT 自動採番。
- **接続先の注入経路（W-2）**: 1 つ目のコンテキストは `@DynamicPropertySource` で Valkey Testcontainer の `spring.data.redis.host` / `spring.data.redis.port` を注入。2 つ目は `SpringApplicationBuilder.properties("spring.data.redis.host=...", "spring.data.redis.port=...", "server.port=0")` で**同値を明示指定**して起動する。
- 実 STOMP クライアント（`WebSocketStompClient`）で各ノードに CONNECT し、`UserDestinationMessageHandler` / `SimpUserRegistry` / SimpleBroker を含む**実スタック**で検証する:
  - **AC-1 の実到達**: §7.1.1（再注入呼び出しまで）だけでは AC-1 は閉じないため、**最低 1 destination（村ロビー presence または match live）をここでフルスタック到達アサート**する（ノード B 発 → ノード A 接続クライアントの実受信まで）。
  - **AC-2**（到達＋非漏洩・手順は §5 AC-2 の固定手順）・**AC-5**（registry 登録＋同一ソース一致）・**AC-7**（サイネージ topic のマルチノード到達）。
  - **AC-10**: 2 コンテキストから同一試合の配信を交互に発生させ、観戦クライアントの受信 `serverSeq` が単調増加であることをアサート（**red: `AtomicLong` のままでは減少が観測される**）。
  - **AC-11**: 非メンバー認証ユーザーで `/topic/channels/{channelId}` を SUBSCRIBE し拒否（ERROR フレーム）を確認（**red 先行**）。メンバーの購読成立も対で確認。
- **TestContext キャッシュへの配慮**: 1 つ目はフレームワーク管理のコンテキストとしてよいが、**2 つ目は `SpringApplicationBuilder` で明示起動・テスト終了時に明示 close** し、フレームワークの TestContext キャッシュに残さない。テストクラス全体も `@DirtiesContext(classMode = AFTER_CLASS)` で後始末し、他テスト（`AbstractMySqlIntegrationTest` 系）へのキャッシュ分裂波及を防ぐ。

#### 7.1.3 AC-4（Valkey 断フェイルオープン）— 手組み検証

§7.1.1 と同じ手組み構成で検証する（**明示割当** — 対応テストの欠落を許さない）:

1. **断の再現は container の pause/unpause**（`docker pause` 相当・`REDIS.getDockerClient().pauseContainerCmd(...)`）を用いる。`stop()`→`start()` は mapped port が変わり「Lettuce の再接続先が消える」という別問題を検証してしまうため使わない。
2. pause 中: publish が**例外を上げず** warn＋`relay.publish.failure` に計上されること・relay を経由しない配信呼び出し（ローカル SimpleBroker 経路）が**成功し続ける**こと。
3. unpause 後: 購読が自動再開し、publish→受信の中継が**復帰**すること（受信再開をアサート）。

### 7.2 Principal 欠陥の実証（red 先行）

§2.3 の欠陥は**先に red テストで実証**する（憶測修正禁止）:
- **原因切り分けの独立アサート**: 同テスト内で、まず「`/queue` プレフィックスがブローカーに登録済みであること」を独立に確認する（§2.1 の `configureMessageBroker` 順序依存が原因でないことの切り分け）。その上で配信をアサートし、red の原因を **Principal 未配線単独**に固定する。
- **2 セッションテスト**: 実 STOMP クライアント 2 本で CONNECT し、一方の userId 宛に `convertAndSendToUser` を送る。**修正前は届かない（red）**ことを確認 → Principal 配線後に**届く（green）**。
- `SimpUserRegistry` に接続ユーザーが登録されることを直接アサート（`SimpUserRegistry.getUser(userId)` 非 null）。

### 7.3 非回帰（既存 UT はそのまま活きる）

配信元の既存 UT（`NotificationDispatchServiceTest`・`NotificationDispatchServiceVisibilityGuardTest` 等、`SimpMessagingTemplate` をモックして destination/ペイロードを検証するもの）は、**relay 導入後も `SimpMessagingTemplate` 抽象が不変のため非回帰でそのまま green**。relay は brokerChannel 下流に挿入され、発信元コードには一切触れない（例外: `MatchLiveBroadcastListener` は §4.6 の INCR 化で採番部が変わるため、当該 UT は採番モック差し替えの追随が必要）。

### 7.4 実機 E2E — ローカル 2 ノード再現手順（S2）

LB を使わずローカルで 2 ノードを決定論的に再現する（ポート規約: CLAUDE.md「常駐サーバーのポート規約」準拠）:

1. **ノード A = 本陣 BE（8080）**・**ノード B = 検証用 worktree BE（8081・`./gradlew bootRun --args='--server.port=8081'`）** の 2 プロセスを起動し、**同一のローカル Valkey（docker-compose の 6379）** に接続する（relay flag は両者 ON）。
2. ブラウザを 2 枚用意し、**それぞれ別ポートに直結**する（FE 3000 → BE 8080 / FE 3001 → BE 8081）。「別ノードに接続した 2 クライアント」が**運任せなしで**再現できる。
3. **LB 分散下の実機確認**（同一 URL からの接続が別ノードに割れるケース・AC-8 含む）は **staging（ECS `desired_count=2`）でのみ**実施する、と区分する。

#### 7.4.1 実機確認項目

- **AC-2**: 手順は §5 AC-2 固定手順。**通知発火前にブラウザをフルリロードして新規 CONNECT** させる（Principal 配線は CONNECT 時のみ・S8）。
- **AC-9**: 隊 5 の composable 経由で通知が `useNotificationStore` に反映されること（**テスト用 STOMP クライアントでの緑は AC-9 の緑にならない** — 実アプリ購読コードで検証）。
- **AC-1/AC-7 代表 destination**（チャット・サイネージ）のノード跨ぎ受信。
- **AC-10**: 両ノードで交互にイベント記録し、観戦ブラウザの受信 seq 単調性を確認。

#### 7.4.2 ノード識別の観測手段（S3）

- 各ノードの `node-id`（起動時 UUID）を **`/actuator/info` に公開**し、**CONNECT 時のサーバーログにも出力**する（例: `WebSocket認証成功: userId={}, nodeId={}`）。
- AC-2/AC-9 の実機判定は「**両セッションが別ノードに接続していることを上記で確認した上で**」行う（同一ノードに割れると relay を通らず**偽陰性の緑**になる）。ローカル 2 ノード手順（ポート直結）では構造的に別ノードが保証されるが、staging では必ずこの観測で裏取りする。

---

## 8. 非機能

### 8.1 フェイルオープン設計

- `RedisMessageListenerContainer` は接続喪失時に例外を投げず（Lettuce の再接続 + Spring の container 再起動）、購読を自動復旧。
- `WebSocketRelayPublisher` の publish 失敗は warn ログ + メトリクスのみ（配信経路を巻き戻さない）。
- **結果**: Valkey 断中は「マルチノード中継のみ停止」し、各ノードのローカル SimpleBroker 配信は継続。復旧後、購読再開で中継が自動的に戻る。アプリの可用性は Valkey に依存しない（キャッシュ層と同じ耐性設計思想）。
- **認証はフェイルオープンの対象外**: WS の JWT 検証（`AuthTokenService.parseAccessToken` = トークン署名検証）は現状 Valkey に依存しない。フェイルオープン方針が適用されるのは **relay（配信中継）のみ**であり、認証・購読認可の判断を緩めることはない。

### 8.2 ループ防止

§4.4 の originNodeId 判定 + リレーマーカー判定 + destination 判定（§4.2.1）の**三重防御**。単一ノード relay ON（段階 1）でも安全。

### 8.3 ファンアウト帯域見積り（N 倍配信の許容規模）

- ある配信 1 件は Valkey で N ノードに fan-out され、各ノードが自ノード購読者へ配る。Valkey pub/sub トラフィックは概ね **（配信件数 × N ノード × 平均ペイロードサイズ）**。
- 現状 destination の大半はチーム/チャネル/試合単位の限定購読で、1 配信あたりの購読者・配信頻度は限定的（07 §J.5「観戦者はチーム関係者中心で限定的」）。スループット上限は**本番ノードタイプ（最小・バースト型の `cache.t4g.micro` 級）の実値前提で保守的に見積もる**: 公表された確定値はないため、桁感として**数千 msg/s 程度を設計上の上限目安**とし、段階 1 で `relay.publish.count` を実測して見積りを更新する。当面のノード数 N（2〜数台）・現配信頻度に対しては十分に余裕。
- **presence は中継必須で除外不可**（§2.2 注記 — 切断由来の退室 broadcast は接続していたノードでしか発生しないため、除外すると他ノードの在席表示が恒久的に腐る）。中継除外の設定余地を残すのは**タイピング（§2.2 表 #3）のみ**。除外した場合の劣化 =「別ノード接続ユーザーのタイピング表示が出ない」という明示的な機能劣化であり、採用にはマスター判断を要する（段階 2 で配信量を計測して判断）。

### 8.4 監視メトリクス（型・タグ・公開方法・合否クエリつき・S4）

| メトリクス | 型 | タグ | 意味 |
|---|---|---|---|
| `relay.publish.count` | Counter | `nodeId`, `destinationType`（`broadcast` / `user`）| Valkey へ publish した件数 |
| `relay.publish.failure` | Counter | `nodeId`, `destinationType` | publish 失敗（Valkey 断等）|
| `relay.receive.count` | Counter | `nodeId`, `destinationType` | Valkey から受信した件数（自ノード発の破棄前段を含む）|
| `relay.receive.dropped` | Counter | `nodeId` | originNodeId 一致で破棄した件数 |
| `relay.reinject.count` / `relay.reinject.failure` | Counter | `nodeId`, `destinationType` | SimpleBroker 再注入の成功 / 失敗 |
| `relay.valkey.rtt` | Timer | `nodeId` | Valkey PING レイテンシ（遅延の代替指標）|

- 遅延の片道直接計測（publish→他ノード再注入）は**ノード間クロックスキューで不正確なため採用しない**。件数突合＋RTT で代替（補助: 同一ノード self-roundtrip を同一クロックで計測可）。
- **公開方法**: Micrometer で登録し、`/actuator/metrics/relay.publish.count` 等から確認する（既存 actuator 基盤・確認手順は `curl <node>/actuator/metrics/relay.publish.count`）。
- **段階 1 の合否クエリ例**（単一ノード relay ON）: `relay.receive.dropped{nodeId=A}` が `relay.publish.count{nodeId=A}` と**同数**（受信は全て自ノード発で破棄＝ループなし）かつ `relay.reinject.count{nodeId=A} = 0`。
- **段階 2 の合否クエリ例**: 24h 窓で「Σ `relay.publish.count`（全ノード）×（N−1）」対「Σ `relay.reinject.count`（全ノード）」の**突合差分 1% 未満**を正常とする（pub/sub は at-most-once のため 0 とは限らないが、常態的な欠損は容量/ネットワーク問題のシグナルとして調査発動）。
- Valkey 接続状態（Lettuce 再接続回数・container 稼働）も既存基盤で監視。新規ダッシュボードは運用整備時。

### 8.5 Cloudflare Tunnel 経由の WebSocket

- ALB → Cloudflare Tunnel 化は軍議前提として並行進行中とされる。**ただし現時点の IaC（`infra/terraform/**`）・docs には `cloudflared` / Tunnel の記述は皆無**（偵察で検索網羅・ヒットゼロ）。現状の Cloudflare は「ALB を公開オリジンとするプロキシ構成」（ACM 検証・Always Use HTTPS 等）に留まり、Tunnel（オリジン非公開化）への移行は未文書化。本設計はこの移行の有無に**依存しない**。
- A 案は**セッションアフィニティに非依存**（どのノードに振られても全配信が揃う）ため、ALB プロキシでも Tunnel でも**相性が良い**。sticky session を前提にしない分、将来 Tunnel 移行のルーティング自由度を損なわない。
- **フォールバックは存在しない（S6・FE 実コード裏取り済み）**: FE は sockjs-client を**使っておらず**、全 composable が生 `new WebSocket()` で `/ws` に直結している（BE の `.withSockJS()` が用意する HTTP フォールバック経路は実際には未使用）。したがって**経路（ALB / Tunnel いずれでも）の WebSocket アップグレード（`Upgrade: websocket`）透過は必須前提**であり、Tunnel 移行時は WS 透過の確認を導入条件とする。

### 8.6 IaC 連動（実装は別隊 = §9 隊 3）

`infra/terraform/modules/app/main.tf` の ECS サービス設定を、無停止ローリング対応へ変更する（現状 → 変更後）:

| 項目 | 現状 | 変更後 | 備考 |
|---|---|---|---|
| `deployment_minimum_healthy_percent` | `0` | `100` | ローリング中も稼働タスクを維持（断ゼロ）|
| `deployment_maximum_percent` | `100` | `200` | 新旧タスク並走を許可（relay で配信が揃うため安全）|
| deployment circuit breaker | なし | **追加**（`deployment_circuit_breaker { enable = true, rollback = true }`）| デプロイ失敗時の自動ロールバック |
| コメント（460-463 行の「WebSocket インメモリブローカーのため 2 タスク不可」）| — | **全面書き換え** | relay 導入で 2 タスク並走が可能になった旨に更新 |

- `desired_count` は段階移行に従い、段階 1 では `1` のまま（relay ON 検証）、段階 2 で `2` 以上へ。
- **relay 有効化の前提条件（セキュリティ・必須）**: 現状 `transit_encryption_enabled = false`（`modules/data/main.tf` 198 行）。relay を有効化すると**チャット本文・通知本文（PII）が平文で Valkey を流れる新経路**が生まれるため、**relay ON より前に転送時暗号化を有効化**する（隊 3 スコープに含める）:
  1. ElastiCache: `transit_encryption_enabled = true`（in-place 変更可否・ダウンタイム有無・`transit_encryption_mode` の段階適用要否の確定は**段階 1 着手前の隊 3 ゲート** — 未確定のまま relay 実装を段階 1 へ進めない・A.7）
  2. Spring 側: `application-prod.yml` に `spring.data.redis.ssl.enabled: true`（Lettuce TLS）を追加
  - 影響範囲: 接続は単一の `LettuceConnectionFactory` のため、既存の**キャッシュ・レート制限・presence TTL キーの接続もまとめて TLS 化**される（設定一箇所・アプリコード変更なし）。ローカル開発（docker-compose の valkey）は TLS 無しのままとする（prod/staging プロファイル限定の設定）。
- ElastiCache のノード構成（`aws_elasticache_replication_group.valkey`）は**単一ノード維持で変更なし**（上記 TLS 化のみ）。
- **取り込み順の協調**: 同じ ECS タスク定義/サービスに触れる **Graviton 化 PR #2219**（`runtime_platform` 変更）を**先行**させ、本件の IaC 変更（`deployment_*`・circuit breaker・TLS）は**その上に積む**。Cloudflare Tunnel 隊とも同様に main への取り込み順序を調整する。

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
| **隊 1: BE 中核** | relay 部品 + Principal 配線 + WebSocketConfig 是正 + **serverSeq 根治** | `WebSocketRelayPublisher` / `WebSocketRelaySubscriber` / `RelayEnvelope` / `WebSocketRelayProperties` / `RedisMessageListenerContainer` Bean / `StompPrincipal` / `WebSocketAuthChannelInterceptor` への `setUser` 追加（§2.3 の Y-1 地雷注意）/ feature flag / §2.1 の順序依存是正（Config 集約）/ **`serverSeq` の Valkey INCR 化（§4.6）** / node-id の `/actuator/info` 公開＋CONNECT ログ（§7.4.2）|
| **隊 2: 試練（テスト先行）** | 受け入れ条件 → red テスト | §7.2 の Principal red（`/queue` 登録済みの切り分けアサート含む）/ §7.1.1 手組み結合（broadcast・ループ防止）/ §7.1.2 2 コンテキスト実スタック（AC-1 実到達・user 宛・Principal・サイネージ・**serverSeq 単調 red**・**チャット認可 red**）/ §7.1.3 Valkey 断（pause/unpause）/ 非回帰確認 |
| **隊 3: IaC** | ECS deployment 設定 + Valkey TLS 化 | §8.6 の `deployment_*` 変更 + circuit breaker + コメント書き換え + **`transit_encryption_enabled=true` 化と `spring.data.redis.ssl` 有効化（relay ON の前提条件・適用可否確定は段階 1 着手前のゲート）** + relay flag の task definition 環境変数追加（§1.3）。Graviton #2219 先行の上に積む・Cloudflare Tunnel 隊と協調 |
| **隊 4: サイネージ系統の確定対応** | §2.1 の実挙動確定 | 起動時 Configurer 順序の実測確定 → 単一ブローカーマージ前提の妥当性確認。設定集約が隊 1 に吸収できるなら隊 1 に統合（調査結果次第）|
| **隊 5: FE 通知購読** | `/user/queue/notifications` の FE 受け口新設（§2.5・🔴）| ログイン後グローバル WS 接続の composable（SUBSCRIBE → `useNotificationStore.setLatestNotification` へ結線・**新 UI 不要の最小結線**）＋ AC-9 実機 E2E |
| **隊 6: チャット SUBSCRIBE 認可** | §2.6 の既存 IDOR 閉塞（御裁可済み）| `ChatChannelSubscriptionInterceptor`（チャネルメンバーシップ検査・既存 2 インターセプタと同パターン・`WebSocketConfig` の認証後段に登録）＋ AC-11 red 先行 |

> 順序: **隊 2（試練 red）→ 隊 1・隊 5・隊 6（green）→ 隊 3（IaC・TLS ゲートは段階 1 着手前に確定）**。Principal 根治は隊 1 の中核であり、隊 2 の red 実証が前提（BE/API テスト先行・`feedback_test_first_be_api`）。隊 5（FE）は BE の Principal 根治とセットで AC-9 が閉じる。

---

## 付録: 未解決点 / 実装時に確定させること

- **A.1** `configureMessageBroker` 二重呼び出しの順序依存（§2.1）— 実測で現挙動を確定し、単一 Config へ集約して根絶。
- **A.2**（確定済み）`SignageWebSocketPublisher` は 2 destination（`/emergency`・`/update`）。`/emergency` は `SignageEmergencyMessageEntity` として DB 永続（timestamp のみ揮発）、`/update` は再取得トリガの揮発シグナル。いずれも**再描画可能**で at-most-once 許容（§2.2 表 #9a/#9b で確定）。
- **A.3**（確定済み）チャネルは 2 チャネル固定・コード内定数化（§4.2）。destination 別のさらなる分割は将来最適化であり本設計スコープ外。
- **A.4** タイピング（§2.2 表 #3）の中継除外オプションを実装するか — **presence は中継必須で確定**（§2.2 注記・§8.3）。タイピング除外は明示的な機能劣化（別ノードのタイピング表示欠落）を伴うため、段階 2 の計測後に**マスター裁可事項**（§8.3。本付録で唯一の未確定項目・裁可待ちとして明示）。
- **A.5**（正確化・第 2 パス S7）SUBSCRIBE 認可が存在するのは現状 **2 destination（MatchLive・EmergencyClosure）のみ**であり、チャットは本戦役の隊 6 で追加する（§2.6）。relay 再注入はブローカー配信（アウトバウンド）であり SUBSCRIBE フレームを再評価しない点は不変（認可は各購読成立時に実施済み）。送信時フィルタ（07 §J.3.3 の機微情報非包含）が relay 経路でも維持されること（中継で機微フィールドが付加されないこと）は**実装時の red テスト対象**とする（「見込み」の残置ではなくテストで確定）。
- **A.6** user 経路のリレーマーカーが `UserDestinationMessageHandler` の解決ホップを経てもヘッダ保持されるか — **実装時にテストで確定**する（どちらの結果でも §4.2.1 の destination 判定により二重配信は防止されることを設計で担保済み・§4.4。挙動が変わる未確定ではなく事実確認のみ）。
- **A.7**（隊 3 ゲートへ格上げ）ElastiCache `transit_encryption_enabled` の in-place 変更可否（ダウンタイム有無・`transit_encryption_mode` の段階適用要否）— **段階 1 着手前に隊 3 が AWS 仕様を確認して確定**する（§8.6）。「実装時確認」の残置ではなく着手ゲート条件。
  - **確定（2026-07-11 隊3）**: 本番環境（`infra/terraform/envs/prod`）は**未 apply**（AWS 上に `aws_elasticache_replication_group.valkey` はまだ存在しない）。したがって `transit_encryption_enabled = true` は**既存稼働中クラスタへの in-place 変更ではなく、初回作成時から TLS 有効な構成**として適用される。in-place 変更のダウンタイム・`transit_encryption_mode` の段階適用（`preferred`→`required`）は**発生しない**（それらは既存クラスタに後付けで有効化する場合にのみ必要な考慮）。段階 1 着手のブロッカーは解消済み。
