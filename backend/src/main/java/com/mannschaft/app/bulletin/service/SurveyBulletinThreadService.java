package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * アンケートと掲示板スレッドの連携サービス。
 *
 * <p>アンケート作成時に専用の掲示板スレッドを自動生成し、
 * アンケート締め切り時にそのスレッドをロックする。</p>
 *
 * <p>クロスドメイン依存の制御:
 * このサービスは bulletin ドメインの Repository のみを使用する。
 * survey ドメインとの連携はイベント経由で行う（直接依存を排除）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyBulletinThreadService {

    private static final String SURVEY_SOURCE_TYPE = "SURVEY";

    private final BulletinThreadRepository bulletinThreadRepository;
    /** フラット enrichment（投稿者名/アバター・カテゴリ・既読・リアクション）を共通経路で適用する。 */
    private final BulletinThreadService bulletinThreadService;

    /**
     * アンケートに対応する掲示板スレッドを作成する。
     *
     * <p>既にスレッドが存在する場合は重複作成しない（冪等性保証）。</p>
     *
     * <p>スコープ対応: bulletin ドメインは ORGANIZATION / TEAM のみ対応するため、
     * COMMITTEE 等それ以外の scopeType はすべて ORGANIZATION として扱う。</p>
     *
     * <p>authorId は null（システム生成スレッド）。
     * 設計書 F05.1 §3 に従い categoryId も null（未分類）で作成する。</p>
     *
     * @param surveyId  アンケートID
     * @param scopeType スコープ種別文字列（ORGANIZATION / TEAM / COMMITTEE 等）
     * @param scopeId   スコープID
     * @param title     アンケートのタイトル
     * @return 作成または既存のスレッドエンティティ
     */
    @Transactional
    public BulletinThreadEntity createForSurvey(long surveyId, String scopeType, long scopeId, String title) {
        // 冪等性保証: 既にスレッドが存在する場合は作成しない
        Optional<BulletinThreadEntity> existing =
                bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(SURVEY_SOURCE_TYPE, surveyId);
        if (existing.isPresent()) {
            log.debug("アンケートスレッドは既に存在するためスキップ: surveyId={}", surveyId);
            return existing.get();
        }

        // bulletin ドメインの ScopeType に変換（TEAM / ORGANIZATION のみ対応）
        ScopeType bulletinScopeType = resolveBulletinScopeType(scopeType);

        BulletinThreadEntity thread = BulletinThreadEntity.builder()
                // categoryId は null（未分類: F05.1 §3 許容済み）
                .scopeType(bulletinScopeType)
                .scopeId(scopeId)
                .authorId(null)           // システム生成スレッドのため author なし
                .title(title + " — 掲示板")
                .body("")
                .sourceType(SURVEY_SOURCE_TYPE)
                .sourceId(surveyId)
                .build();

        BulletinThreadEntity saved = bulletinThreadRepository.save(thread);
        log.info("アンケートスレッド自動作成: surveyId={}, threadId={}", surveyId, saved.getId());
        return saved;
    }

    /**
     * アンケート締め切り時に対応する掲示板スレッドをロックする。
     *
     * <p>スレッドが見つからない場合は何もしない（アンケート作成時の自動生成が
     * 失敗した場合など、スレッドが存在しない状況に対して安全に動作する）。</p>
     *
     * @param surveyId アンケートID
     */
    @Transactional
    public void lockForSurvey(long surveyId) {
        bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(SURVEY_SOURCE_TYPE, surveyId)
                .ifPresentOrElse(thread -> {
                    if (!thread.getIsLocked()) {
                        thread.toggleLock();
                        bulletinThreadRepository.save(thread);
                        log.info("アンケートスレッドをロック: surveyId={}, threadId={}", surveyId, thread.getId());
                    }
                }, () -> log.debug("ロック対象のアンケートスレッドが見つからない: surveyId={}", surveyId));
    }

    /**
     * アンケートIDに対応する掲示板スレッドを検索する。
     *
     * @param surveyId アンケートID
     * @return スレッドエンティティ（存在しない場合は empty）
     */
    public Optional<BulletinThreadEntity> findBySurveyId(long surveyId) {
        return bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(SURVEY_SOURCE_TYPE, surveyId);
    }

    /**
     * アンケートIDに対応する掲示板スレッドをフラット enrich 済みレスポンスで取得する。
     *
     * <p>一覧/詳細と同じ enrichment（投稿者名/アバター・カテゴリ名/色・既読・リアクション集計）を通すため、
     * {@link BulletinThreadService#enrichSingle} に委譲する。スレッドが無ければ empty。</p>
     *
     * @param surveyId      アンケートID
     * @param currentUserId 操作ユーザーID（既読・myReactions の主体。null 可）
     * @return enrich 済みスレッドレスポンス（存在しない場合は empty）
     */
    @Transactional(readOnly = true)
    public Optional<ThreadResponse> findThreadResponseBySurveyId(long surveyId, Long currentUserId) {
        return findBySurveyId(surveyId)
                .map(thread -> bulletinThreadService.enrichSingle(thread, currentUserId));
    }

    /**
     * survey の scopeType 文字列を bulletin の {@link ScopeType} enum に変換する。
     *
     * <p>bulletin ドメインは ORGANIZATION / TEAM のみ対応する。
     * COMMITTEE 等、TEAM でも ORGANIZATION でもない scopeType は ORGANIZATION として扱う。</p>
     */
    private ScopeType resolveBulletinScopeType(String scopeType) {
        if (scopeType == null) {
            return ScopeType.ORGANIZATION;
        }
        return switch (scopeType.toUpperCase()) {
            case "TEAM" -> ScopeType.TEAM;
            default -> ScopeType.ORGANIZATION;
        };
    }
}
