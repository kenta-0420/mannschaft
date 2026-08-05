package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationErrorCode;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.NotificationStatsResponse;
import com.mannschaft.app.notification.dto.SnoozeRequest;
import com.mannschaft.app.notification.dto.UnreadCountResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderQueryService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 通知サービス。通知のCRUD・既読管理・スヌーズを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationMapper notificationMapper;

    /**
     * F00 Phase F セキュリティ漏れ修正で導入。通知発行先ユーザーが
     * 通知のソースコンテンツ ({@code sourceType} + {@code sourceId}) を閲覧可能か
     * を判定し、不可の場合は通知作成自体をスキップする (fail-soft, §11.1)。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.1 / §13.5。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * F15.3: マイスコープフォルダによる通知フィルタ用クエリサービス。
     * folderId 指定時にフォルダ内 scopeId 集合を取得する（設計書 §5.2.4）。
     */
    private final MyScopeFolderQueryService myScopeFolderQueryService;

    /**
     * ユーザーの通知一覧をページング取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページング情報
     * @return 通知レスポンスのページ
     */
    @Timed(value = "mannschaft.repository.query", extraTags = {"operation", "NotificationService.listNotifications"})
    public Page<NotificationResponse> listNotifications(Long userId, Pageable pageable) {
        Page<NotificationEntity> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return page.map(notificationMapper::toNotificationResponse);
    }

    /**
     * フォルダ単位での通知一覧を取得する（F15.3 §5.2.4）。
     *
     * <p>{@code folderId} と {@code scopeType} の両方が指定されたときのみフォルダフィルタを適用する。
     * folderId の所有検証は {@link MyScopeFolderQueryService#getScopeIdsInFolder} 内で行われ、
     * 他人 folderId の場合は {@code SCOPE_FOLDER_NOT_FOUND} が投げられる（設計書 §9.7）。</p>
     *
     * <p>folderId 未指定時は {@link #listNotifications} と同等の挙動（後方互換）。</p>
     *
     * @param userId    ユーザーID
     * @param folderId  フォルダID（任意。null なら全件）
     * @param scopeType スコープタイプ（folderId と共に指定）
     * @param pageable  ページング
     * @return フィルタ済みの通知ページ
     */
    public Page<NotificationResponse> listNotificationsByFolder(
            Long userId, Long folderId, ScopeType scopeType, Pageable pageable) {
        if (folderId == null || scopeType == null) {
            return listNotifications(userId, pageable);
        }

        // 1) folder の所有 + アイテム scope_id 集合を取得（IDOR 防止込み）
        List<Long> scopeIds = myScopeFolderQueryService.getScopeIdsInFolder(userId, folderId);
        if (scopeIds.isEmpty()) {
            // フォルダにアイテムが無い → 結果空
            return Page.empty(pageable);
        }

        // 2) scope_type + scope_id IN (...) で絞り込み
        // NotificationEntity.scopeType は @Enumerated(EnumType.STRING) の enum 属性であり、
        // scopeType.name() の String を渡すと Hibernate のパラメータ束縛で型不一致になる（実行時 500）。
        // 必ず NotificationScopeType へ写像してから渡すこと。
        Page<NotificationEntity> page = notificationRepository
                .findByUserIdAndScopeTypeAndScopeIdInOrderByCreatedAtDesc(
                        userId, toNotificationScopeType(scopeType), scopeIds, pageable);
        return page.map(notificationMapper::toNotificationResponse);
    }

    /**
     * scopefolder ドメインの {@link ScopeType} を通知ドメインの {@link NotificationScopeType} へ写像する。
     *
     * <p><b>値集合の関係</b>: {@code ScopeType} は {@code TEAM} / {@code ORGANIZATION} の 2 値のみで、
     * いずれも {@code NotificationScopeType} に同名の定数が存在する。したがって本写像は全域（total）であり、
     * 「写像できない値」は存在しない。逆に {@code NotificationScopeType} 側には
     * {@code PERSONAL} / {@code SYSTEM} / {@code FRIEND_TEAM} / {@code FRIEND_FOLDER} / {@code COMMITTEE}
     * が余分にあるが、マイスコープフォルダはチーム／組織しか分類しない（F15.3 §4.3）ため
     * それらがフォルダフィルタの引数に来ることはない。</p>
     *
     * <p><b>なぜ switch を使わないのか（重要・戻さないこと）</b>:
     * enum に対する {@code switch} を書くと、javac が {@code $SwitchMap$...} を保持する
     * <b>合成クラス {@code NotificationService$1} を自動生成する</b>。この合成クラスは外側クラスの
     * {@code ScopeType} 依存をそのまま写して持つため、クロスドメイン Entity 参照の番人
     * （{@code CrossDomainEntityImportArchTest} / D-1）が<b>新しいクラス名の新規違反</b>として検出し
     * CI が落ちる（{@code NotificationService} 本体の依存は凍結ストア済みだが、合成クラスは別名のため
     * 凍結に当たらない）。D-1 は合成クラスを除外していないため、ここでは
     * <b>{@code ==} による enum 参照比較</b>で書く。こうすれば {@code $SwitchMap} は生成されない。
     * 「switch のほうが安全だ」と善意で書き戻すと CI が再び赤になるので注意すること。</p>
     *
     * <p><b>将来 {@code ScopeType} に定数が増えた場合</b>: switch を捨てたためコンパイル時の網羅性保証は
     * 失われる。代わりに未知値では {@link IllegalStateException} を投げて<b>必ず落とす</b>
     * （黙って null や空ページを返して握りつぶすことはしない）。さらに番人テスト
     * {@code NotificationScopeTypeMappingTest} が {@code ScopeType.values()} を全件ループして
     * 写像可能性を検査しているため、<b>定数が増えた瞬間にそのテストが落ちて</b>写像先の判断を強制できる。</p>
     *
     * @param scopeType scopefolder ドメインのスコープ種別
     * @return 通知ドメインのスコープ種別
     * @throws IllegalStateException 写像先が定義されていないスコープ種別が渡された場合
     */
    static NotificationScopeType toNotificationScopeType(ScopeType scopeType) {
        if (scopeType == ScopeType.TEAM) {
            return NotificationScopeType.TEAM;
        }
        if (scopeType == ScopeType.ORGANIZATION) {
            return NotificationScopeType.ORGANIZATION;
        }
        throw new IllegalStateException(
                "ScopeType から NotificationScopeType への写像が未定義です: " + scopeType
                        + "（NotificationService#toNotificationScopeType に写像先を追加すること）");
    }

    /**
     * ユーザーの未読通知件数を取得する。
     *
     * @param userId ユーザーID
     * @return 未読件数レスポンス
     */
    public UnreadCountResponse getUnreadCount(Long userId) {
        long count = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return new UnreadCountResponse(count);
    }

    /**
     * 通知を既読にする。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse markAsRead(Long userId, Long notificationId) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        if (entity.isAlreadyRead()) {
            throw new BusinessException(NotificationErrorCode.ALREADY_READ);
        }

        entity.markAsRead();
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知既読: userId={}, notificationId={}", userId, notificationId);
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * 通知を未読に戻す。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse markAsUnread(Long userId, Long notificationId) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        if (!entity.isAlreadyRead()) {
            throw new BusinessException(NotificationErrorCode.ALREADY_UNREAD);
        }

        entity.markAsUnread();
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知未読戻し: userId={}, notificationId={}", userId, notificationId);
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * 通知をスヌーズする。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @param request        スヌーズリクエスト
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse snoozeNotification(Long userId, Long notificationId, SnoozeRequest request) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        // 絶対時刻（オフセット付き）→ JST 壁時計に変換してから保存・比較する。
        // フロントは .toISOString()（UTC）で送るため、LocalDateTime で受けると Jackson が
        // オフセットを捨て、JST 固定 JVM の LocalDateTime.now() と約 9 時間ずれる。これを根治する。
        LocalDateTime snoozedUntilJst =
                request.getSnoozedUntil().atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();
        if (snoozedUntilJst.isBefore(LocalDateTime.now())) {
            throw new BusinessException(NotificationErrorCode.INVALID_SNOOZE_TIME);
        }

        entity.snooze(snoozedUntilJst);
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知スヌーズ: userId={}, notificationId={}, until={}", userId, notificationId, snoozedUntilJst);
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * ユーザーの未読通知を全て既読にする。
     *
     * @param userId ユーザーID
     * @return 既読にした件数
     */
    @Transactional
    public int markAllAsRead(Long userId) {
        int count = notificationRepository.markAllAsReadByUserId(userId);
        log.info("通知全件既読: userId={}, count={}", userId, count);
        return count;
    }

    /**
     * 通知を作成する（内部利用）。organizationId なし版（後方互換）。
     *
     * @param userId           宛先ユーザーID
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeType        スコープ種別
     * @param scopeId          スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     * @return 作成された通知エンティティ
     */
    @Transactional
    public NotificationEntity createNotification(Long userId, String notificationType,
                                                  NotificationPriority priority, String title, String body,
                                                  String sourceType, Long sourceId,
                                                  NotificationScopeType scopeType, Long scopeId,
                                                  String actionUrl, Long actorId) {
        return createNotification(userId, notificationType, priority, title, body,
                sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, null);
    }

    /**
     * 通知を作成する（内部利用）。organizationId あり版。
     *
     * <p>Phase 4-B: テナントシャーディング布石として organizationId を保持する。
     * 既存の呼び出し元との後方互換は organizationId=null で維持する。</p>
     *
     * @param userId           宛先ユーザーID
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeType        スコープ種別
     * @param scopeId          スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     * @param organizationId   組織ID（NULL許容・テナント絞り込み用）
     * @return 作成された通知エンティティ
     */
    @Transactional
    public NotificationEntity createNotification(Long userId, String notificationType,
                                                  NotificationPriority priority, String title, String body,
                                                  String sourceType, Long sourceId,
                                                  NotificationScopeType scopeType, Long scopeId,
                                                  String actionUrl, Long actorId, Long organizationId) {
        // ----------------------------------------------------------------
        // F00 Phase F: 通知発行前の visibility ガード (§11.1)
        // ----------------------------------------------------------------
        // sourceType を ReferenceType にマップして閲覧権を確認。閲覧不可の
        // ユーザーには通知を作らない (受信者リスト確定後の filterAccessible
        // 相当を、単発 createNotification 経路でも担保する)。
        //
        // fail-soft: ReferenceType に対応しない sourceType (例: MEMBER_PAYMENT)
        // や、sourceId が null の通知は visibility 判定対象外として通過させる。
        // これにより既存の Resolver 未配備 sourceType を破壊しない。
        if (!isAccessible(sourceType, sourceId, userId)) {
            log.warn("通知作成スキップ (visibility deny): userId={}, type={}, sourceType={}, sourceId={}",
                    userId, notificationType, sourceType, sourceId);
            return null;
        }

        NotificationEntity entity = NotificationEntity.builder()
                .userId(userId)
                .organizationId(organizationId)
                .notificationType(notificationType)
                .priority(priority)
                .title(title)
                .body(body)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actionUrl(actionUrl)
                .actorId(actorId)
                .build();

        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知作成: userId={}, type={}, orgId={}, notificationId={}",
                userId, notificationType, organizationId, saved.getId());
        return saved;
    }

    /**
     * 配信認可済み受信者向けの通知を作成する（配信＝受信権 統一・関所(1)通知）。
     *
     * <p>本メソッドは {@link #createNotification(Long, String, NotificationPriority, String, String,
     * String, Long, NotificationScopeType, Long, String, Long, Long)} の <b>visibility ガード
     * （{@code isAccessible} / {@code canView}）をスキップする</b>専用オーバーロードである。
     * 受信者が「配信母集団（{@code resolveOrgDistributionUserIds} がコンテンツの {@code includeSupporters}
     * トグル準拠で展開した集合）」に属することを呼び出し側で事前認可済みの場合にのみ使用する。</p>
     *
     * <p><b>なぜ専用経路が必要か</b>: 既存 {@code createNotification} の Phase F ガード
     * （§11.1）は SCHEDULE / SURVEY の {@code sourceType} を {@link ReferenceType} に解決し
     * {@code canView} で個別判定する。SURVEY の canView は結果閲覧（ResultsVisibility）軸も絡む
     * Resolver に委譲されるため、配信母集団に属する直属一般メンバー／配下チームメンバーへの
     * 公開通知が誤って deny されていた（(B) 通知レグレッションの真因＝関所(1)）。配信済みの受信者は
     * 母集団で事前認可されているため、ここでは二重の canView 判定を行わない。</p>
     *
     * <p><b>既存ガードは不変</b>: 既存 {@code createNotification} は他通知（個別通知・他ドメイン）の
     * Phase F ガードとしてそのまま機能する。本メソッドは別シグネチャの新設であり、一律バイパスではない。</p>
     *
     * @param userId           宛先ユーザーID（配信母集団で事前認可済みであること）
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeType        スコープ種別
     * @param scopeId          スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     * @return 作成された通知エンティティ（常に非 null。visibility deny によるスキップは発生しない）
     */
    @Transactional
    public NotificationEntity createNotificationPreAuthorized(
            Long userId, String notificationType,
            NotificationPriority priority, String title, String body,
            String sourceType, Long sourceId,
            NotificationScopeType scopeType, Long scopeId,
            String actionUrl, Long actorId) {
        // 配信母集団で事前認可済みのため Phase F の isAccessible(canView) 二重判定はスキップする。
        NotificationEntity entity = NotificationEntity.builder()
                .userId(userId)
                .notificationType(notificationType)
                .priority(priority)
                .title(title)
                .body(body)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actionUrl(actionUrl)
                .actorId(actorId)
                .build();

        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知作成(配信認可済): userId={}, type={}, notificationId={}",
                userId, notificationType, saved.getId());
        return saved;
    }

    /**
     * 通知ソースに対する受信者の閲覧可否を判定する。
     *
     * <p>F00 Phase F セキュリティガード (§11.1)。{@code sourceType} を
     * {@link ReferenceType} に解決できない、または {@code sourceId} が null の
     * 通知は対象外として true を返す (fail-soft)。
     *
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @param userId     受信者 userId
     * @return アクセス可能または判定対象外なら true
     */
    private boolean isAccessible(String sourceType, Long sourceId, Long userId) {
        if (sourceId == null) {
            return true;
        }
        Optional<ReferenceType> refType = NotificationSourceTypeMapper.resolve(sourceType);
        if (refType.isEmpty()) {
            return true;
        }
        return visibilityChecker.canView(refType.get(), sourceId, userId);
    }

    /**
     * 管理者向け通知統計を取得する。
     *
     * @return 通知統計レスポンス
     */
    public NotificationStatsResponse getStats() {
        long total = notificationRepository.count();
        long totalSubscriptions = pushSubscriptionRepository.count();

        // 全ユーザーの合計未読数・既読数をカスタムクエリで集計
        long unread = notificationRepository.countByIsReadFalse();
        long read = total - unread;

        return new NotificationStatsResponse(total, unread, read, totalSubscriptions);
    }

    /**
     * 通知を取得する。存在しない場合は例外をスローする。
     */
    private NotificationEntity findNotificationOrThrow(Long userId, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }
}
