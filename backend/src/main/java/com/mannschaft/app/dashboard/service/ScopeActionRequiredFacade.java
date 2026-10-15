package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.service.SurveyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * F22.1 第二波: 統合「要対応」集計のファサード（dashboard ドメイン）。
 *
 * <p>回覧板（circulation）・アンケート（survey）・出席確認（schedule attendance）の 3 ドメインを
 * 跨ぐ <b>読み取り集計</b>を 1 つのレスポンスに集約する。各ドメインの Service メソッドを呼び、
 * 各メソッド内で per-scope 認可が必ず適用される（集計バイパス禁止）。ファサード自身も、
 * エンドポイントの所属検証とは別に、集約の入口で所属検証を通して二重防御する。</p>
 *
 * <p><b>欠陥Z 根治（組織→配下チーム配信）</b>: ORGANIZATION スコープでは、配下チームのみに所属する
 * メンバーも組織発の出欠/アンケートに回答できる必要があるため、入口および survey/attendance の
 * per-scope 認可は {@code checkMembershipOrDescendant}（純 SUPPORTER 除外）を用いる。circulation は
 * 組織→配下チーム配信の対象外であり、従来どおり直接所属の {@code checkMembership} を用いる
 * （配下メンバーの回覧区分は per-recipient ACL に登録が無ければ 0 件＝縮退でなく正しい結果）。</p>
 *
 * <p><b>原則 5 遵守</b>: {@code @Transactional} をドメイン跨ぎにしない。本ファサードはトランザクション
 * 境界を持たず（読み取り集計）、各ドメイン Service の読み取りメソッドを個別に呼ぶだけ。</p>
 *
 * <p><b>縮退設計</b>: 3 区分を {@link CompletableFuture} で並行集計し、1 ドメインが例外を投げても
 * 当該区分のみ 0 件に縮退し、他区分は返す（02 §3.4 / 04 §5.3）。例外は握り潰さずログに出す
 * （対処療法禁止: 症状を隠さない）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.4 /
 * 04_widgets.md §5</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeActionRequiredFacade {

    private final CirculationService circulationService;
    private final SurveyService surveyService;
    private final ScheduleAttendanceService scheduleAttendanceService;
    private final AccessControlService accessControlService;

    /** 各区分の直近アイテム最大件数（04 §5.1: 直近 3 件）。 */
    private static final int RECENT_ITEM_LIMIT = 3;

    /**
     * 指定スコープ・ユーザーの統合「要対応」集計を取得する。
     *
     * @param userId    閲覧ユーザー ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @return 統合「要対応」集計レスポンス
     */
    public ActionRequiredSummaryResponse getActionRequired(Long userId, String scopeType, Long scopeId) {
        // 集約の入口で所属検証（各ドメイン Service 内でも再度 checkMembershipOrDescendant される＝二重防御）。
        // 配信＝受信権 統一: 集約入口は単一コンテンツではないため広め（includeSupporters=true）で通す。
        // トグル ON で配信された出欠/アンケに回答できる配下 SUPPORTER も入口で弾かれないようにする。
        // per-content の絞りは各未回答クエリ（buildAttendance/buildSurvey）が userId の materialize 済み行で
        // 自然に効くため、過小排除も漏洩も起きない（母集団外ユーザーは未回答 0 件になる）。
        accessControlService.checkMembershipOrDescendant(userId, scopeId, scopeType, true);

        CompletableFuture<ActionRequiredSummaryResponse.CirculationSection> circulationFuture =
                CompletableFuture.supplyAsync(() -> buildCirculation(userId, scopeType, scopeId));
        CompletableFuture<ActionRequiredSummaryResponse.SurveySection> surveyFuture =
                CompletableFuture.supplyAsync(() -> buildSurvey(userId, scopeType, scopeId));
        CompletableFuture<ActionRequiredSummaryResponse.AttendanceSection> attendanceFuture =
                CompletableFuture.supplyAsync(() -> buildAttendance(userId, scopeType, scopeId));

        ActionRequiredSummaryResponse.CirculationSection circulation =
                join(circulationFuture, "circulation", emptyCirculation());
        ActionRequiredSummaryResponse.SurveySection survey =
                join(surveyFuture, "survey", emptySurvey());
        ActionRequiredSummaryResponse.AttendanceSection attendance =
                join(attendanceFuture, "attendance", emptyAttendance());

        long total = circulation.unconfirmedCount()
                + survey.unansweredCount()
                + attendance.unansweredCount();

        return ActionRequiredSummaryResponse.builder()
                .circulation(circulation)
                .survey(survey)
                .attendance(attendance)
                .totalActionCount(total)
                .build();
    }

    // ─────────────────────────────────────────────
    // 区分別ビルダー（各ドメイン Service 呼び出し・per-scope 認可は Service 内で実施）
    // ─────────────────────────────────────────────

    private ActionRequiredSummaryResponse.CirculationSection buildCirculation(
            Long userId, String scopeType, Long scopeId) {
        CirculationService.UnconfirmedCirculations result =
                circulationService.getUnconfirmedForUserInScope(scopeType, scopeId, userId, RECENT_ITEM_LIMIT);
        List<ActionRequiredSummaryResponse.CirculationItem> items = result.items().stream()
                .map(this::toCirculationItem)
                .toList();
        return ActionRequiredSummaryResponse.CirculationSection.builder()
                .unconfirmedCount(result.unconfirmedCount())
                .items(items)
                .build();
    }

    private ActionRequiredSummaryResponse.SurveySection buildSurvey(
            Long userId, String scopeType, Long scopeId) {
        SurveyService.UnansweredSurveys result =
                surveyService.getUnansweredForUserInScope(scopeType, scopeId, userId, RECENT_ITEM_LIMIT);
        List<ActionRequiredSummaryResponse.SurveyItem> items = result.items().stream()
                .map(this::toSurveyItem)
                .toList();
        return ActionRequiredSummaryResponse.SurveySection.builder()
                .unansweredCount(result.unansweredCount())
                .items(items)
                .build();
    }

    private ActionRequiredSummaryResponse.AttendanceSection buildAttendance(
            Long userId, String scopeType, Long scopeId) {
        ScheduleAttendanceService.UnansweredAttendances result =
                scheduleAttendanceService.getUnansweredForUserInScope(scopeType, scopeId, userId, RECENT_ITEM_LIMIT);
        List<ActionRequiredSummaryResponse.AttendanceItem> items = result.items().stream()
                .map(this::toAttendanceItem)
                .toList();
        return ActionRequiredSummaryResponse.AttendanceSection.builder()
                .unansweredCount(result.unansweredCount())
                .items(items)
                .build();
    }

    // ─────────────────────────────────────────────
    // エンティティ → アイテム DTO 変換
    // ─────────────────────────────────────────────

    private ActionRequiredSummaryResponse.CirculationItem toCirculationItem(CirculationDocumentEntity d) {
        return ActionRequiredSummaryResponse.CirculationItem.builder()
                .id(d.getId())
                .title(d.getTitle())
                .circulatedAt(d.getCreatedAt())
                .deadline(d.getDueDate())
                .build();
    }

    private ActionRequiredSummaryResponse.SurveyItem toSurveyItem(SurveyEntity s) {
        return ActionRequiredSummaryResponse.SurveyItem.builder()
                .id(s.getId())
                .title(s.getTitle())
                .deadline(s.getExpiresAt())
                .build();
    }

    private ActionRequiredSummaryResponse.AttendanceItem toAttendanceItem(ScheduleEntity s) {
        return ActionRequiredSummaryResponse.AttendanceItem.builder()
                .scheduleId(s.getId())
                .eventTitle(s.getTitle())
                .startsAt(s.getStartAt())
                .build();
    }

    // ─────────────────────────────────────────────
    // 縮退ヘルパー（1 区分の例外を握り潰さずログ＋当該区分のみ 0 件）
    // ─────────────────────────────────────────────

    private <T> T join(CompletableFuture<T> future, String section, T fallback) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            // 認可例外（COMMON_002 等）はそのまま伝播させたいケースもあるが、
            // 本機能はエンドポイント入口で checkMembership 済みのため、
            // ここに来る例外は当該ドメインの一時障害とみなし、当該区分のみ縮退する。
            log.warn("ScopeActionRequiredFacade: '{}' 区分の集計に失敗。当該区分を 0 件に縮退します。", section,
                    ex.getCause() != null ? ex.getCause() : ex);
            return fallback;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("ScopeActionRequiredFacade: '{}' 区分の集計が中断されました。0 件に縮退します。", section, ex);
            return fallback;
        }
    }

    private static ActionRequiredSummaryResponse.CirculationSection emptyCirculation() {
        return ActionRequiredSummaryResponse.CirculationSection.builder()
                .unconfirmedCount(0).items(List.of()).build();
    }

    private static ActionRequiredSummaryResponse.SurveySection emptySurvey() {
        return ActionRequiredSummaryResponse.SurveySection.builder()
                .unansweredCount(0).items(List.of()).build();
    }

    private static ActionRequiredSummaryResponse.AttendanceSection emptyAttendance() {
        return ActionRequiredSummaryResponse.AttendanceSection.builder()
                .unansweredCount(0).items(List.of()).build();
    }
}
