package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.SubmissionStatus;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * フォーム提出リポジトリ。
 */
public interface FormSubmissionRepository extends JpaRepository<FormSubmissionEntity, Long> {

    /**
     * F08.7.1/06: 大会提出枠（tournament_submission_requirement.id）に紐付く全提出を取得する。
     *
     * <p>提出状況ダッシュボード（未提出/提出済/受理/差戻し）の集計に使う。論理削除は
     * {@code @SQLRestriction} で除外される。</p>
     */
    List<FormSubmissionEntity> findByTournamentSubmissionRequirementId(UUID tournamentSubmissionRequirementId);

    /**
     * F08.7.1/06: 大会提出枠 + scope（提出チーム）で提出を取得する。
     *
     * <p>提出 = 自チーム単位（{@code scopeType='TEAM'} / {@code scopeId=teamId}）で 1 件に正規化されるため、
     * 当該チームの既存提出（再提出の差し戻しフロー）を引くために使う。</p>
     */
    Optional<FormSubmissionEntity> findByTournamentSubmissionRequirementIdAndScopeTypeAndScopeId(
            UUID tournamentSubmissionRequirementId, String scopeType, Long scopeId);

    /**
     * テンプレートに紐付く提出一覧をページング取得する。
     */
    Page<FormSubmissionEntity> findByTemplateIdOrderByCreatedAtDesc(Long templateId, Pageable pageable);

    /**
     * テンプレートに紐付く提出をステータス指定でページング取得する。
     */
    Page<FormSubmissionEntity> findByTemplateIdAndStatusOrderByCreatedAtDesc(
            Long templateId, SubmissionStatus status, Pageable pageable);

    /**
     * ユーザーのスコープ内提出一覧をページング取得する。
     */
    Page<FormSubmissionEntity> findBySubmittedByAndScopeTypeAndScopeIdOrderByCreatedAtDesc(
            Long submittedBy, String scopeType, Long scopeId, Pageable pageable);

    /**
     * IDと提出者IDで提出を取得する。
     */
    Optional<FormSubmissionEntity> findByIdAndSubmittedBy(Long id, Long submittedBy);

    /**
     * テンプレートとユーザーの提出回数を取得する。
     */
    long countByTemplateIdAndSubmittedBy(Long templateId, Long submittedBy);

    /**
     * テンプレートのステータス別提出件数を取得する。
     */
    long countByTemplateIdAndStatus(Long templateId, SubmissionStatus status);

    /**
     * F05.7 Phase 11 第四陣 4-B: ユーザー横断「自分の提出」一覧をページング取得する。
     *
     * <p>{@code GET /api/v1/me/form-submissions} 用。スコープを問わず提出者で絞り込む。</p>
     */
    Page<FormSubmissionEntity> findBySubmittedByOrderByCreatedAtDesc(Long submittedBy, Pageable pageable);

    /**
     * F05.7 Phase 11 第四陣 4-B: テンプレート別の提出一覧（フィルタなし、ページングなし）。
     *
     * <p>CSV エクスポート用に全件を一度に取り出す。テンプレート単位の最大件数は
     * 設計書 §5 によりスコープあたり 1 件あたり上限なしのため、必要に応じて
     * limit を別途設けることを推奨。現状は素直に全件返す。</p>
     */
    List<FormSubmissionEntity> findByTemplateIdOrderByCreatedAtDesc(Long templateId);

    /**
     * F05.7 Phase 11 第四陣 4-B: テンプレートに対して、指定ユーザーが SUBMITTED 以降の
     * 提出をしているかを判定する。リマインド対象の絞り込みに使う（既に提出済みのユーザーは
     * 対象外とする）。
     */
    @Query("""
        SELECT s.submittedBy FROM FormSubmissionEntity s
        WHERE s.templateId = :templateId
          AND s.deletedAt IS NULL
          AND s.status <> com.mannschaft.app.forms.SubmissionStatus.DRAFT
          AND s.submittedBy IN :userIds
        """)
    List<Long> findSubmittedUserIds(Long templateId, List<Long> userIds);
}
