package com.mannschaft.app.social.announcement;

import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * お知らせ作成サービス（F02.6）。
 *
 * <p>
 * コンテンツのお知らせウィジェット登録・自動お知らせ化・告知ウィザード経由登録を担う。
 * </p>
 *
 * <p>
 * <b>IDOR 対策</b>:
 * {@code createAnnouncement} 時に {@code source_id} の実スコープと
 * パス変数の {@code scopeId} を照合し、他スコープのコンテンツのお知らせ化を防ぐ。
 * 詳細は設計書 §6.1 を参照。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementCreationService {

    /** タイトルキャッシュの最大文字数 */
    private static final int MAX_TITLE_CACHE_LENGTH = 200;

    private final AnnouncementFeedRepository feedRepository;
    private final AccessControlService accessControlService;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final AnnouncementSourceResolver sourceResolver;

    // ── 委員会関連リポジトリ（COMMITTEE スコープサポート用） ──
    private final CommitteeMemberRepository committeeMemberRepository;

    // ═════════════════════════════════════════════════════════════
    // 2.2 お知らせ化（公開登録）
    // ═════════════════════════════════════════════════════════════

    /**
     * コンテンツをお知らせウィジェットに登録する。
     *
     * <p>
     * <b>IDOR 検証フロー</b>:
     * <ol>
     *   <li>source_type に応じて元コンテンツを取得（存在しなければ ANNOUNCE_006）</li>
     *   <li>個人ブログ・ソーシャルプロフィール投稿を拒否（ANNOUNCE_007）</li>
     *   <li>元コンテンツの scope が request の scopeId と一致するか検証（ANNOUNCE_005）</li>
     *   <li>重複登録を拒否（ANNOUNCE_003）</li>
     *   <li>権限チェック：著者本人または ADMIN+（ANNOUNCE_002）</li>
     * </ol>
     * </p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param sourceType    元コンテンツ種別
     * @param sourceId      元コンテンツ ID
     * @param requestUserId リクエストユーザー ID
     * @return 作成したお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createAnnouncement(
            AnnouncementScopeType scopeType,
            Long scopeId,
            AnnouncementSourceType sourceType,
            Long sourceId,
            Long requestUserId) {

        // ── ソースコンテンツ検証・情報取得 ──
        AnnouncementSourceResolver.SourceInfo sourceInfo =
                sourceResolver.resolveSourceInfo(scopeType, scopeId, sourceType, sourceId, requestUserId);

        // ── 重複登録チェック ──
        feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                sourceType, sourceId, scopeType, scopeId)
                .ifPresent(existing -> {
                    throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_003);
                });

        // ── 権限チェック: 著者本人または ADMIN+（COMMITTEE スコープはメンバーチェック） ──
        boolean isAuthor = requestUserId.equals(sourceInfo.authorId());
        boolean isAdmin = AnnouncementScopeType.COMMITTEE.equals(scopeType)
                ? committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(scopeId, requestUserId)
                : accessControlService.isAdminOrAbove(requestUserId, scopeId, scopeType.name());
        if (!isAuthor && !isAdmin) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_002);
        }

        // ── エンティティ作成 ──
        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .authorId(requestUserId)
                .titleCache(sourceInfo.titleCache())
                .excerptCache(sourceInfo.excerptCache())
                .priority(sourceInfo.priority())
                .visibility(sourceInfo.visibility())
                .expiresAt(sourceInfo.expiresAt())
                .build();

        return feedRepository.save(entity);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.7 自動お知らせ化（アンケート・回覧板から呼ぶ）
    // ═════════════════════════════════════════════════════════════

    /**
     * アンケート・回覧板の公開時に自動お知らせ化する（Service 層内部から呼ぶ）。
     *
     * <p>
     * {@link #createAnnouncement} の内部ロジックを流用しつつ、
     * 重複時は例外を投げずに既存レコードを返す。
     * 呼び出し元 Service（SurveyService / CirculationService）が認可チェック済みの前提で動作する。
     * </p>
     *
     * @param sourceType ソース種別
     * @param sourceId   ソース ID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープ ID
     * @param authorId   登録者 ID（アンケート・回覧板の作成者）
     * @return 作成または既存のお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createFromSource(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long authorId) {

        // 重複チェック: 既存レコードがあれば返す（例外なし）
        Optional<AnnouncementFeedEntity> existing = feedRepository
                .findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(sourceType, sourceId, scopeType, scopeId);
        if (existing.isPresent()) {
            log.debug("自動お知らせ化スキップ（既存） sourceType={}, sourceId={}, scopeType={}, scopeId={}",
                    sourceType, sourceId, scopeType, scopeId);
            return existing.get();
        }

        // ソース情報取得（IDOR 検証は呼び出し元が保証。スコープ一致チェックのみ実施）
        AnnouncementSourceResolver.SourceInfo sourceInfo =
                sourceResolver.resolveSourceInfo(scopeType, scopeId, sourceType, sourceId, authorId);

        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .authorId(authorId)
                .titleCache(sourceInfo.titleCache())
                .excerptCache(sourceInfo.excerptCache())
                .priority(sourceInfo.priority())
                .visibility(sourceInfo.visibility())
                .expiresAt(sourceInfo.expiresAt())
                .build();

        AnnouncementFeedEntity saved = feedRepository.save(entity);
        log.info("自動お知らせ化完了 sourceType={}, sourceId={}, scopeType={}, scopeId={}",
                sourceType, sourceId, scopeType, scopeId);
        return saved;
    }

    // ═════════════════════════════════════════════════════════════
    // 2.8 告知ウィザード経由お知らせ化（F02.8）
    // ═════════════════════════════════════════════════════════════

    /**
     * 告知ウィザード（F02.8）経由でお知らせフィードを登録する。
     *
     * <p>{@link #createFromSource} に加えて priority / expiresAt / targetTeamIds を設定する。
     * コンテンツは告知ウィザードが事前に作成しているため、IDOR 検証は
     * {@link com.mannschaft.app.social.announcement.AnnouncementBroadcastService} が担保する。</p>
     *
     * <p>タイトルキャッシュは告知コンテンツのタイトルから直接設定する。
     * {@link AnnouncementSourceType#TODO} / {@link AnnouncementSourceType#SCHEDULE} は
     * F02.8 告知ウィザード専用であるため、{@link #createFromSource} 経由ではなく
     * 本メソッドで直接エンティティを構築する。</p>
     *
     * @param sourceType    ソース種別
     * @param sourceId      ソース ID（告知ウィザードが作成したコンテンツの ID）
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param authorId      作成者ユーザー ID
     * @param priority      優先度（NORMAL / IMPORTANT / URGENT）
     * @param expiresAt     表示期限（null = 期限なし）
     * @param targetTeamIds 組織告知でのチーム絞り込み（null = 全チーム対象）
     * @param titleCache    タイトルキャッシュ（告知コンテンツのタイトルから設定）
     * @param visibility    閲覧可能範囲（target_role から設定）
     * @return 作成されたお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createFromBroadcast(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long authorId,
            String priority,
            java.time.LocalDateTime expiresAt,
            String targetTeamIds,
            String titleCache,
            String visibility) {

        // 重複チェック: 既存レコードがあれば priority / expiresAt / targetTeamIds を上書きして返す
        Optional<AnnouncementFeedEntity> existing = feedRepository
                .findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(sourceType, sourceId, scopeType, scopeId);
        if (existing.isPresent()) {
            AnnouncementFeedEntity updated = existing.get().toBuilder()
                    .priority(priority != null ? priority : "NORMAL")
                    .expiresAt(expiresAt)
                    .targetTeamIds(targetTeamIds)
                    .build();
            return feedRepository.save(updated);
        }

        // 告知ウィザードは事前にコンテンツ作成済み。タイトルキャッシュはリクエストから直接設定する。
        String effectiveTitleCache = sourceResolver.truncate(
                titleCache != null ? titleCache : "(告知コンテンツ)", MAX_TITLE_CACHE_LENGTH);

        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .authorId(authorId)
                .titleCache(effectiveTitleCache)
                .priority(priority != null ? priority : "NORMAL")
                .visibility(visibility != null ? visibility : "MEMBERS_AND_ABOVE")
                .expiresAt(expiresAt)
                .targetTeamIds(targetTeamIds)
                .build();

        AnnouncementFeedEntity saved = feedRepository.save(entity);
        log.info("告知ウィザード経由フィード登録完了 feedId={}, sourceType={}, sourceId={}, priority={}",
                saved.getId(), sourceType, sourceId, priority);
        return saved;
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: 代理入力記録作成
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせ代理確認の proxy_input_records を作成して保存する（冪等性チェック付き）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済みの代理入力記録エンティティ
     */
    public ProxyInputRecordEntity buildAndSaveAnnouncementProxyRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("ANNOUNCEMENT_READ")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }
}
