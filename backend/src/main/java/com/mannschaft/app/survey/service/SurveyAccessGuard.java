package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F05.4 アンケートの管理操作（作成・更新・公開・締切・削除・設問編集・配信対象/結果閲覧者の付与）に対する
 * 認可ゲート。認可根治戦役 Wave7。
 *
 * <h2>権限粒度（設計書 F05.4 §エンドポイント一覧に準拠）</h2>
 * <ul>
 *   <li><b>作成</b>（{@code POST /surveys}）— 当該スコープの会員（MEMBER+）。応援者（SUPPORTER）は作成できない。</li>
 *   <li><b>既存アンケートの管理操作</b>（更新 / 公開 / 締切 / 削除 / 設問追加・削除 /
 *       配信対象追加 / 結果閲覧者追加）— <b>作成者 または ADMIN+</b>。
 *       同ドメインの兄弟 {@code SurveyService#extendDeadline} / {@code #duplicateSurvey} と同一の粒度。</li>
 * </ul>
 *
 * <h2>BOLA 対策</h2>
 * <p>認可に用いるスコープは URL のパス変数ではなく <b>アンケート実体（{@code surveys.scope_type} /
 * {@code scope_id}）由来</b>で確定する。パス変数のスコープと実体のスコープが一致しない場合は
 * {@link SurveyErrorCode#SURVEY_NOT_FOUND}（404）を返して<b>存在を秘匿</b>する
 * （403 を返すと当該 ID のアンケートが実在することを漏らすため）。</p>
 *
 * <h2>ゲートを敷く位置</h2>
 * <p>本ゲートは <b>利用者が到達する public 入口である Controller</b> から呼ぶ。
 * {@code SurveyService#createSurvey} / {@code #publishSurvey} は予約タスクの materialize バッチ
 * （{@code ScheduleScheduledTaskBatchService}）と告知アダプタ（{@code SurveyAnnouncementAdapter}）
 * からも呼ばれる共有メソッドであり、そちらは呼び出し元で認可済み・SecurityContext を持たない
 * 経路もあるため、Service 本体にゲートを埋めるとシステム起点の処理を巻き添えで落とす。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyAccessGuard {

    /** CMP-041: ADMIN+ 委任判定に用いる permission 名。 */
    private static final String PERMISSION_MANAGE_SURVEYS = "MANAGE_SURVEYS";

    private final SurveyRepository surveyRepository;
    private final AccessControlService accessControlService;

    /**
     * アンケートを新規作成できるかを検証する。
     *
     * <p>当該スコープの会員であること（{@link AccessControlService#checkMembership}）を要求し、
     * 応援者（SUPPORTER）は作成対象から除外する（設計書 F05.4「SUPPORTER は作成不可」）。</p>
     *
     * @param userId    操作ユーザー ID
     * @param scopeType 正準スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID
     * @throws BusinessException 非会員または応援者の場合（COMMON_002 / 403）
     */
    public void checkCanCreate(Long userId, String scopeType, Long scopeId) {
        accessControlService.checkMembership(userId, scopeId, scopeType);
        if (accessControlService.isSupporter(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スコープ付きパス（{@code /api/v1/{scopeType}/{scopeId}/surveys/{surveyId}/...}）配下の
     * 管理操作を検証する。
     *
     * <p>パス変数のスコープと一致するアンケートを取得できた場合のみ先へ進む。取得できない場合
     * （不存在・論理削除済み・別スコープのアンケート ID 指定）は {@code SURVEY_NOT_FOUND}（404）で
     * 存在を秘匿する。</p>
     *
     * @param userId    操作ユーザー ID
     * @param scopeType 正準スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープ ID
     * @param surveyId  対象アンケート ID
     * @throws BusinessException 対象が当該スコープに存在しない場合（SURVEY_NOT_FOUND / 404）、
     *                           作成者でも ADMIN+ でもない場合（OPERATION_PERMISSION_DENIED / 403）
     */
    public void checkCanManage(Long userId, String scopeType, Long scopeId, Long surveyId) {
        SurveyEntity survey = surveyRepository.findByIdAndScopeTypeAndScopeId(surveyId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));
        checkCreatorOrAdmin(userId, survey);
    }

    /**
     * スコープを持たないパス（{@code /api/v1/surveys/{surveyId}/...}）の管理操作を検証する。
     *
     * <p>認可スコープはアンケート実体から解決する。不存在・論理削除済みは
     * {@code SURVEY_NOT_FOUND}（404）。</p>
     *
     * @param userId   操作ユーザー ID
     * @param surveyId 対象アンケート ID
     * @throws BusinessException 対象が存在しない場合（SURVEY_NOT_FOUND / 404）、
     *                           作成者でも ADMIN+ でもない場合（OPERATION_PERMISSION_DENIED / 403）
     */
    public void checkCanManage(Long userId, Long surveyId) {
        SurveyEntity survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));
        checkCreatorOrAdmin(userId, survey);
    }

    /**
     * 作成者本人、または実体スコープの ADMIN+ であることを要求する。
     *
     * <p>ADMIN 判定に渡すスコープは実体（{@code survey.getScopeId()} /
     * {@code survey.getScopeType()}）由来であり、パス変数は用いない。</p>
     */
    private void checkCreatorOrAdmin(Long userId, SurveyEntity survey) {
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId);
        if (isCreator) {
            return;
        }
        if (!accessControlService.hasAdminOrPermissionInScope(
                userId, survey.getScopeId(), survey.getScopeType(), PERMISSION_MANAGE_SURVEYS)) {
            throw new BusinessException(SurveyErrorCode.OPERATION_PERMISSION_DENIED);
        }
    }
}
