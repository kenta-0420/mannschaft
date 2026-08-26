package com.mannschaft.app.survey.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.UnrespondedVisibility;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.OptionResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.QuestionResultResponse;
import com.mannschaft.app.survey.dto.SurveyTeamBreakdownResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * アンケート結果サービス。結果の集計・閲覧権限管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyResultService {

    /** CSV エクスポート時の匿名性保証のための最小回答者数（5名未満は集計マスク）。 */
    private static final int MIN_RESPONDENTS_FOR_DETAIL_EXPORT = 5;

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyService surveyService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    /** 結果閲覧可否の唯一の判定点（詳細応答の viewerCanViewResults と共用）。 */
    private final SurveyResultAccessGuard resultAccessGuard;
    private final OrganizationMembershipService organizationMembershipService;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * アンケート結果を取得する。閲覧権限チェックを行う。
     *
     * @param surveyId アンケートID
     * @param userId   閲覧者ユーザーID
     * @return アンケート結果レスポンス
     */
    public SurveyResultResponse getResults(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        validateResultAccess(survey, userId);

        return buildResultResponse(survey);
    }

    /**
     * 結果閲覧権限を検証する。
     *
     * <p>F00 Phase C (2026-05-04): {@code ContentVisibilityChecker} 経由の判定に切り替えた。
     * Resolver ({@link com.mannschaft.app.survey.visibility.SurveyVisibilityResolver}) が
     * status × {@code ResultsVisibility} 合成を一元処理する。</p>
     *
     * <p>Issue #2779: その 2 段（作成者高速パス → 可視性基盤への委譲）を
     * {@link SurveyResultAccessGuard} へ抽出した。アンケート詳細応答の
     * {@code viewerCanViewResults} も同じメソッドを呼ぶため、
     * <b>「見られる」と応答したのに 403</b>（またはその逆）が構造的に起こらない。</p>
     */
    private void validateResultAccess(SurveyEntity survey, Long userId) {
        // Issue #2779: 「作成者高速パス → ContentVisibilityChecker 委譲」の 2 段は
        // SurveyResultAccessGuard に抽出済み。詳細応答の viewerCanViewResults も
        // 同じメソッドを呼ぶため、応答と実際の 403 が構造的に食い違わない。
        // canView=false の場合は既存と同じ SurveyErrorCode.RESULT_ACCESS_DENIED で返す
        // （根治治療: 既存挙動と同じ ErrorCode を投げ、上位 API 契約を保つ）。
        if (!resultAccessGuard.canViewResults(survey, userId)) {
            throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
        }
    }

    /**
     * 回答者一覧（未回答者を含む）を取得する。F05.4 §7.2「未回答者一覧の可視化」。
     *
     * <p>認可:
     * <ul>
     *   <li>ADMIN+ / 作成者 / survey_result_viewers → 全件返却（has_responded 付き）</li>
     *   <li>{@code unresponded_visibility = ALL_MEMBERS} かつ MEMBER（本人が survey_targets に含まれる）
     *       → 未回答者のみ（user_id, display_name, avatar_url のみ。responded_at は null）</li>
     *   <li>それ以外 → {@link SurveyErrorCode#RESPONDENTS_ACCESS_DENIED}</li>
     * </ul>
     *
     * <p>母集団の決定（設計書 §1035-1036 に準拠）:
     * <ul>
     *   <li>{@link DistributionMode#ALL} → スコープ内全メンバー（user_roles 経由）</li>
     *   <li>{@link DistributionMode#TARGETED} → {@code survey_targets} 登録ユーザー</li>
     * </ul>
     *
     * @param surveyId 対象アンケートID
     * @param userId   閲覧者ユーザーID
     * @return 回答者一覧
     */
    public List<RespondentResponse> getRespondents(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId);
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, survey.getScopeId(), survey.getScopeType());
        boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(survey.getId(), userId);

        boolean fullAccess = isAdmin || isCreator || isViewer;

        if (!fullAccess) {
            // MEMBER 経路: ALL_MEMBERS かつ自分が母集団に含まれる場合のみ未回答者リスト閲覧可
            if (survey.getUnrespondedVisibility() != UnrespondedVisibility.ALL_MEMBERS
                    || !isUserInUniverse(survey, userId)) {
                throw new BusinessException(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED);
            }
        }

        List<SurveyResponseEntity> allResponses = responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId);

        Set<Long> respondedUserIds = new HashSet<>();
        Map<Long, SurveyResponseEntity> firstResponseByUser = new HashMap<>();
        for (SurveyResponseEntity r : allResponses) {
            respondedUserIds.add(r.getUserId());
            firstResponseByUser.putIfAbsent(r.getUserId(), r);
        }

        List<Long> universeUserIds = resolveUniverseUserIds(survey);

        List<UserEntity> users = userRepository.findAllById(universeUserIds);
        Map<Long, UserEntity> userById = users.stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        List<RespondentResponse> result = new ArrayList<>();
        for (Long uid : universeUserIds) {
            UserEntity u = userById.get(uid);
            if (u == null) {
                continue;
            }
            boolean hasResponded = respondedUserIds.contains(uid);

            if (!fullAccess) {
                // MEMBER 経路: 未回答者のみ・respondedAt は null
                if (hasResponded) {
                    continue;
                }
                result.add(new RespondentResponse(u.getId(), u.getLastName() + " " + u.getFirstName(), mediaUrlResolver.resolve(u.getAvatarUrl()), false, null));
            } else {
                java.time.LocalDateTime respondedAt = hasResponded
                        ? firstResponseByUser.get(uid).getCreatedAt()
                        : null;
                result.add(new RespondentResponse(u.getId(), u.getLastName() + " " + u.getFirstName(), mediaUrlResolver.resolve(u.getAvatarUrl()),
                        hasResponded, respondedAt));
            }
        }
        return result;
    }

    /**
     * 集計結果と全回答の生データを CSV として返す（F05.4 §4.5 results/export）。
     *
     * <p>認可: ADMIN+ / 作成者 / {@code survey_result_viewers} 登録者。
     * 匿名アンケート（{@code is_anonymous=true}）の場合は「回答者」列を「匿名」と表示する。
     * 5名未満の場合は匿名性保証のため詳細データをマスクし、集計サマリのみ返す。</p>
     *
     * <p>BOM 付き UTF-8 で出力し、Excel での文字化けを防ぐ。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param surveyId      対象アンケート ID
     * @param currentUserId 操作実行者ユーザー ID
     * @return CSV バイト列（BOM 付き UTF-8）
     */
    public byte[] exportResultsCsv(String scopeType, Long scopeId, Long surveyId, Long currentUserId) {
        SurveyEntity survey = surveyRepository.findByIdAndScopeTypeAndScopeId(surveyId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));

        // 認可
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(
                currentUserId, survey.getScopeId(), survey.getScopeType());
        boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(surveyId, currentUserId);
        if (!isAdmin && !isCreator && !isViewer) {
            throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
        }

        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        long uniqueRespondents = responseRepository.countDistinctUsersBySurveyId(surveyId);
        boolean isAnonymous = Boolean.TRUE.equals(survey.getIsAnonymous());
        boolean maskDetails = uniqueRespondents < MIN_RESPONDENTS_FOR_DETAIL_EXPORT;

        StringBuilder sb = new StringBuilder();
        // BOM 付き UTF-8 のため、先頭に "﻿" を付ける（後で getBytes(UTF_8)）
        sb.append('﻿');

        if (maskDetails) {
            // 5名未満: 集計サマリのみ
            sb.append("項目,値\n");
            sb.append("アンケート名,").append(escape(survey.getTitle())).append('\n');
            sb.append("回答者数,").append(uniqueRespondents).append('\n');
            sb.append("注記,匿名性保証のため5名未満の場合は集計詳細を表示していません\n");
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        // ヘッダー行: 回答日時, 回答者, Q1_..., Q2_..., ...
        sb.append("回答日時,回答者");
        for (SurveyQuestionEntity q : questions) {
            sb.append(',').append(escape("Q" + q.getDisplayOrder() + "_" + q.getQuestionText()));
        }
        sb.append('\n');

        // 回答者ごとに行を構築
        List<SurveyResponseEntity> allResponses =
                responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId);
        Map<Long, List<SurveyResponseEntity>> byUser = allResponses.stream()
                .collect(Collectors.groupingBy(SurveyResponseEntity::getUserId));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        for (Map.Entry<Long, List<SurveyResponseEntity>> entry : byUser.entrySet()) {
            Long uid = entry.getKey();
            List<SurveyResponseEntity> userResponses = entry.getValue();
            java.time.LocalDateTime respondedAt = userResponses.get(0).getCreatedAt();
            sb.append(respondedAt != null ? respondedAt.format(fmt) : "").append(',');

            // 回答者カラム
            if (isAnonymous) {
                sb.append("匿名");
            } else {
                UserEntity u = userRepository.findById(uid).orElse(null);
                String name = u != null
                        ? ((u.getLastName() != null ? u.getLastName() : "") + " "
                           + (u.getFirstName() != null ? u.getFirstName() : "")).trim()
                        : "";
                sb.append(escape(name));
            }

            // 設問ごとの回答
            Map<Long, List<SurveyResponseEntity>> byQuestion = userResponses.stream()
                    .collect(Collectors.groupingBy(SurveyResponseEntity::getQuestionId));
            for (SurveyQuestionEntity q : questions) {
                sb.append(',');
                List<SurveyResponseEntity> qResponses = byQuestion.getOrDefault(q.getId(), List.of());
                sb.append(escape(formatAnswerForCsv(q, qResponses)));
            }
            sb.append('\n');
        }

        log.info("アンケート結果 CSV エクスポート: surveyId={}, rows={}, by={}",
                surveyId, byUser.size(), currentUserId);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 設問単位の回答を CSV セル用文字列にフォーマットする。
     */
    private String formatAnswerForCsv(SurveyQuestionEntity question, List<SurveyResponseEntity> responses) {
        if (responses.isEmpty()) {
            return "";
        }
        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE
                || question.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
            List<String> labels = new ArrayList<>();
            for (SurveyResponseEntity r : responses) {
                if (r.getOptionId() != null) {
                    optionRepository.findById(r.getOptionId())
                            .ifPresent(o -> labels.add(o.getOptionText()));
                }
            }
            return String.join(",", labels);
        }
        // FREE_TEXT / SCALE は最初の textResponse を使用
        return responses.get(0).getTextResponse() != null ? responses.get(0).getTextResponse() : "";
    }

    /**
     * CSV セルとしてエスケープする（カンマ・改行・ダブルクォート対応）。
     */
    private String escape(String input) {
        if (input == null) {
            return "";
        }
        boolean needsQuote = input.contains(",") || input.contains("\n")
                || input.contains("\r") || input.contains("\"");
        String escaped = input.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    /**
     * アンケート結果レスポンスを構築する。
     */
    private SurveyResultResponse buildResultResponse(SurveyEntity survey) {
        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(survey.getId());

        long totalRespondents = responseRepository.countDistinctUsersBySurveyId(survey.getId());

        List<QuestionResultResponse> questionResults = new ArrayList<>();
        for (SurveyQuestionEntity question : questions) {
            questionResults.add(buildQuestionResult(survey.getId(), question, totalRespondents));
        }

        return new SurveyResultResponse(
                survey.getId(),
                survey.getTitle(),
                survey.getResponseCount(),
                survey.getTargetCount(),
                questionResults
        );
    }

    /**
     * 設問ごとの結果を構築する。
     */
    private QuestionResultResponse buildQuestionResult(Long surveyId, SurveyQuestionEntity question,
                                                        long totalRespondents) {
        List<OptionResultResponse> optionResults = new ArrayList<>();
        List<String> textResponses = new ArrayList<>();

        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE
                || question.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
            List<SurveyOptionEntity> options =
                    optionRepository.findByQuestionIdOrderByDisplayOrderAsc(question.getId());

            for (SurveyOptionEntity option : options) {
                long count = responseRepository.countBySurveyIdAndQuestionIdAndOptionId(
                        surveyId, question.getId(), option.getId());
                double percentage = totalRespondents > 0
                        ? (double) count / totalRespondents * 100.0 : 0.0;
                optionResults.add(new OptionResultResponse(
                        option.getId(), option.getOptionText(), count, percentage));
            }
        }

        if (question.getQuestionType() == QuestionType.FREE_TEXT
                || question.getQuestionType() == QuestionType.SCALE) {
            List<SurveyResponseEntity> responses =
                    responseRepository.findBySurveyIdAndQuestionId(surveyId, question.getId());
            for (SurveyResponseEntity resp : responses) {
                if (resp.getTextResponse() != null) {
                    textResponses.add(resp.getTextResponse());
                }
            }
        }

        return new QuestionResultResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType().name(),
                optionResults,
                textResponses
        );
    }

    // ===================================================================================
    // (B) 組織→参加チーム配信 案C フェーズB（アンケートのチーム別内訳 by_team）
    // ===================================================================================

    /**
     * アンケート結果をチーム別内訳（by_team）で集計取得する
     * （(B) 組織→参加チーム配信 案C フェーズB・御裁可A/B）。
     *
     * <p>全体集計（{@code total}・実人数 DISTINCT）＋チームごとの内訳（{@code byTeam}・重複計上あり）を返す。
     * 個別回答者（user_id・氏名）は含まない。</p>
     *
     * <p><b>byTeam を返す条件</b>: {@code team_breakdown_enabled = TRUE} かつ組織スコープ
     * （{@code scope_type = ORGANIZATION}）かつ<b>非匿名</b>のときのみ。トグル OFF（既定）・
     * 非組織スコープ・匿名アンケートは {@code byTeam = null}（従来挙動・後方互換）。匿名 ×
     * トグル ON はそもそも作成時バリデーション（{@link SurveyService#createSurvey}）で禁止されるが、
     * 既存データ・将来の経路を考慮し集計側でも二重防御で byTeam を出さない（御裁可B）。</p>
     *
     * <p><b>御裁可A（全チーム計上）</b>: 配下の複数チームに所属する回答者は所属全チームへ 1 票ずつ計上する
     * （{@link OrganizationMembershipService#resolveMemberTeams(Long, boolean)} の重複計上前提）。
     * したがって byTeam 各チームの回答者数の合計は total の実回答者数以上になりうる。</p>
     *
     * <p><b>御裁可B（5名未満マスク）</b>: 非匿名でも回答者 {@value #MIN_RESPONDENTS_FOR_DETAIL_EXPORT}
     * 名未満のチームは {@code masked = true} とし、設問ごとの内訳（optionResults）を出さない。</p>
     *
     * <p><b>チーム別回答率の分母</b>: 当該チームの実回答者数（チーム内 DISTINCT user_id）。
     * 全体回答率の分母は全体の実回答者数。</p>
     *
     * <p><b>認可</b>: チーム別内訳は組織の管理ビューであり、当該組織（{@code scopeType}/{@code scopeId}）の
     * ADMIN / DEPUTY_ADMIN のみ取得できる。これは結果閲覧可否（{@code ResultsVisibility}）よりも厳格な
     * 管理ビュー専用ゲートであり、ResultsVisibility と独立して常に ADMIN を要求する（権限がない場合 403）。
     * survey ドメインの既存 EP（{@code exportResultsCsv} / {@code getRespondents}）と同じく Service 層で認可する。</p>
     *
     * @param surveyId アンケートID
     * @param userId   閲覧者ユーザーID（当該組織の ADMIN/DEPUTY_ADMIN でなければ 403）
     * @return チーム別内訳レスポンス
     */
    public SurveyTeamBreakdownResponse getTeamBreakdown(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        // 認可: 組織の管理ビューゆえ ADMIN/DEPUTY_ADMIN を強制（ResultsVisibility より厳格）。
        accessControlService.checkAdminOrAbove(userId, survey.getScopeId(), survey.getScopeType());

        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(survey.getId());
        // 設問ID → 選択肢一覧（出現順）。全体・チーム両方の集計で使い回す（N+1 回避）。
        Map<Long, List<SurveyOptionEntity>> optionsByQuestion = new LinkedHashMap<>();
        for (SurveyQuestionEntity q : questions) {
            optionsByQuestion.put(q.getId(),
                    optionRepository.findByQuestionIdOrderByDisplayOrderAsc(q.getId()));
        }

        // 全回答行（全体・チーム両方で使う）。
        List<SurveyResponseEntity> allResponses =
                responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId);

        // 全体の実回答者数（DISTINCT user_id）。御裁可A の total 別建て。
        Set<Long> allRespondentUserIds = new HashSet<>();
        for (SurveyResponseEntity r : allResponses) {
            allRespondentUserIds.add(r.getUserId());
        }
        int totalRespondentCount = allRespondentUserIds.size();

        // 全体集計（全回答行を 1 バケットに集約）。
        SurveyTeamBreakdownResponse.TeamQuestionResults total = new SurveyTeamBreakdownResponse.TeamQuestionResults(
                totalRespondentCount,
                aggregateQuestionResults(questions, optionsByQuestion, allResponses, totalRespondentCount));

        // byTeam を返さない条件（トグル OFF / 非組織 / 匿名）。
        boolean teamBreakdownEnabled = Boolean.TRUE.equals(survey.getTeamBreakdownEnabled());
        boolean isOrganizationScope = "ORGANIZATION".equals(survey.getScopeType());
        boolean isAnonymous = Boolean.TRUE.equals(survey.getIsAnonymous());
        if (!teamBreakdownEnabled || !isOrganizationScope || isAnonymous) {
            return new SurveyTeamBreakdownResponse(survey.getId(), survey.getTitle(), total, null);
        }

        // userId → 所属配下チーム（複数・組織直属は teamId=null 枠）。SUPPORTER 包含は配信トグルに従う。
        boolean includeSupporters = Boolean.TRUE.equals(survey.getIncludeSupporters());
        Map<Long, List<OrganizationMembershipService.TeamRef>> memberTeams =
                organizationMembershipService.resolveMemberTeams(survey.getScopeId(), includeSupporters);

        // teamKey（null=組織直接枠）→ 当該チームに帰属する回答行のバケット（御裁可A: 全チームへ計上）。
        Map<Long, List<SurveyResponseEntity>> responsesByTeam = new LinkedHashMap<>();
        Map<Long, Set<Long>> respondentsByTeam = new LinkedHashMap<>();
        Map<Long, String> teamNameById = new HashMap<>();
        // 回答行を userId でまとめ、各回答者の所属全チームへ複製計上する。
        Map<Long, List<SurveyResponseEntity>> responsesByUser = allResponses.stream()
                .collect(Collectors.groupingBy(SurveyResponseEntity::getUserId));
        for (Map.Entry<Long, List<SurveyResponseEntity>> entry : responsesByUser.entrySet()) {
            Long respondentUserId = entry.getKey();
            List<OrganizationMembershipService.TeamRef> teams = memberTeams.get(respondentUserId);
            if (teams == null || teams.isEmpty()) {
                // 配信母集団に属さない回答者（母集団変更・退会等）。byTeam では計上しない。
                continue;
            }
            for (OrganizationMembershipService.TeamRef ref : teams) {
                Long teamKey = ref.teamId(); // null = 組織直接メンバー枠
                responsesByTeam.computeIfAbsent(teamKey, k -> new ArrayList<>()).addAll(entry.getValue());
                respondentsByTeam.computeIfAbsent(teamKey, k -> new HashSet<>()).add(respondentUserId);
                if (teamKey != null && ref.teamName() != null) {
                    teamNameById.putIfAbsent(teamKey, ref.teamName());
                }
            }
        }

        List<SurveyTeamBreakdownResponse.TeamBreakdownItem> byTeam = new ArrayList<>();
        for (Map.Entry<Long, List<SurveyResponseEntity>> e : responsesByTeam.entrySet()) {
            Long teamKey = e.getKey();
            int teamRespondentCount = respondentsByTeam.getOrDefault(teamKey, Set.of()).size();
            // 御裁可B: 5名未満のチームは内訳をマスク（設問内訳を出さない）。
            boolean masked = teamRespondentCount < MIN_RESPONDENTS_FOR_DETAIL_EXPORT;
            List<SurveyTeamBreakdownResponse.TeamQuestionResult> teamQuestionResults = masked
                    ? List.of()
                    : aggregateQuestionResults(questions, optionsByQuestion, e.getValue(), teamRespondentCount);
            byTeam.add(new SurveyTeamBreakdownResponse.TeamBreakdownItem(
                    teamKey,
                    teamKey == null ? null : teamNameById.get(teamKey),
                    teamRespondentCount,
                    masked,
                    teamQuestionResults));
        }

        return new SurveyTeamBreakdownResponse(survey.getId(), survey.getTitle(), total, byTeam);
    }

    /**
     * 与えられた回答行集合（全体 or 1 チーム分）から、設問ごとの選択肢集計を構築する。
     *
     * <p>選択式設問（SINGLE_CHOICE / MULTIPLE_CHOICE）のみ optionResults を算出する。
     * 自由記述（FREE_TEXT / SCALE）はチーム別内訳・匿名性の観点から対象外とし optionResults は空。
     * 回答率の分母は引数 {@code respondentCount}（全体 or 当該チームの実回答者数）。</p>
     */
    private List<SurveyTeamBreakdownResponse.TeamQuestionResult> aggregateQuestionResults(
            List<SurveyQuestionEntity> questions,
            Map<Long, List<SurveyOptionEntity>> optionsByQuestion,
            List<SurveyResponseEntity> responses,
            int respondentCount) {
        // (questionId, optionId) → 選択数。
        Map<Long, Map<Long, Long>> optionCounts = new HashMap<>();
        for (SurveyResponseEntity r : responses) {
            if (r.getOptionId() == null) {
                continue;
            }
            optionCounts.computeIfAbsent(r.getQuestionId(), k -> new HashMap<>())
                    .merge(r.getOptionId(), 1L, Long::sum);
        }

        List<SurveyTeamBreakdownResponse.TeamQuestionResult> results = new ArrayList<>();
        for (SurveyQuestionEntity q : questions) {
            List<SurveyTeamBreakdownResponse.TeamOptionResult> optionResults = new ArrayList<>();
            if (q.getQuestionType() == QuestionType.SINGLE_CHOICE
                    || q.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
                Map<Long, Long> countsForQuestion =
                        optionCounts.getOrDefault(q.getId(), Map.of());
                for (SurveyOptionEntity option : optionsByQuestion.getOrDefault(q.getId(), List.of())) {
                    long count = countsForQuestion.getOrDefault(option.getId(), 0L);
                    double percentage = respondentCount > 0
                            ? (double) count / respondentCount * 100.0 : 0.0;
                    optionResults.add(new SurveyTeamBreakdownResponse.TeamOptionResult(
                            option.getId(), option.getOptionText(), count, percentage));
                }
            }
            results.add(new SurveyTeamBreakdownResponse.TeamQuestionResult(
                    q.getId(),
                    q.getQuestionText(),
                    q.getQuestionType().name(),
                    optionResults));
        }
        return results;
    }

    /**
     * distribution_mode に応じて母集団ユーザーIDリストを取得する。
     *
     * <p>F05.4 §1035-1036 準拠:
     * <ul>
     *   <li>{@link DistributionMode#ALL} → user_roles 経由でスコープ内全メンバー</li>
     *   <li>{@link DistributionMode#TARGETED} → survey_targets 登録ユーザー</li>
     * </ul>
     */
    private List<Long> resolveUniverseUserIds(SurveyEntity survey) {
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            // 組織×ALL は配下参加チームを展開する（OrganizationMembershipService 経由・越境是正）。
            // これは「回答を期待する母集団（未回答者リスト/回答率の分母）」であり、
            // 実際の配信母集団（SurveyPublishNotificationListener / extend / remind）と一致させる。
            // チームスコープ（および COMMITTEE 等）は配下展開なし・従来挙動を維持する。
            // フェーズM1: 可視性(view)判定経路 isUserInUniverse も再帰版へ追従済み（分母と回答可否を一致）。
            if ("ORGANIZATION".equals(survey.getScopeType())) {
                return organizationMembershipService.resolveOrgDistributionUserIds(
                        survey.getScopeId(), Boolean.TRUE.equals(survey.getIncludeSupporters()));
            }
            return userRoleRepository.findUserIdsByScope(survey.getScopeType(), survey.getScopeId());
        }
        List<SurveyTargetEntity> targets = targetRepository.findBySurveyId(survey.getId());
        return targets.stream()
                .map(SurveyTargetEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 指定ユーザーが当該アンケートの母集団に属するか判定する。
     *
     * <p>MEMBER 経路の認可（{@code unresponded_visibility = ALL_MEMBERS}）で
     * 「自分が母集団内のメンバーかどうか」をチェックする際に使用する。</p>
     *
     * <p><b>フェーズM1（universe 再帰化）</b>: 組織×ALL のときは
     * {@link #resolveUniverseUserIds(SurveyEntity)} が
     * {@link OrganizationMembershipService#resolveOrgDistributionUserIds(Long, boolean)} 経由で
     * 配下組織ツリーへ再帰展開されるのと整合させ、本判定も
     * {@link OrganizationMembershipService#isUserInOrgDistributionUniverse(Long, Long)} で
     * 配下ツリーの「直属 ∪ 配下チーム」を単発 EXISTS 判定する（分母と回答可否を一致させる）。
     * 1 ユーザー判定なので配信母集団全件を取得せず EXISTS でコストを抑える。
     * チームスコープ（および COMMITTEE 等）は配下展開なし・従来挙動を維持する。</p>
     */
    private boolean isUserInUniverse(SurveyEntity survey, Long userId) {
        if (userId == null) {
            return false;
        }
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            // 組織×ALL は配下組織ツリーへ再帰展開（resolveUniverseUserIds と整合）。
            if ("ORGANIZATION".equals(survey.getScopeType())) {
                return organizationMembershipService.isUserInOrgDistributionUniverse(
                        survey.getScopeId(), userId);
            }
            return userRoleRepository.findUserIdsByScope(survey.getScopeType(), survey.getScopeId())
                    .contains(userId);
        }
        return targetRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
    }
}
