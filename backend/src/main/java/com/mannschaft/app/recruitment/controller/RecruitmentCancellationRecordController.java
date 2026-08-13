package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse;
import com.mannschaft.app.recruitment.dto.WaiveCancellationFeeRequest;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationFeeWaiveService;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationRecordQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11.1 募集キャンセル料の記録に対する操作 Controller（設計書 §10.1）。
 *
 * <p>受取先側の管理者も呼ぶため {@code /system-admin/} 配下には置かない（R-5 御裁可）。</p>
 */
@RestController
@RequestMapping("/api/v1/recruitment-cancellation-records")
@Tag(name = "F03.11.1 募集キャンセル料", description = "募集キャンセル料の免除")
@RequiredArgsConstructor
public class RecruitmentCancellationRecordController {

    private final RecruitmentCancellationFeeWaiveService waiveService;
    private final RecruitmentCancellationRecordQueryService queryService;

    /**
     * キャンセル料の記録一覧を取得する（設計書 §12・免除 UI のための一覧）。
     *
     * <p>認可: {@link RecruitmentCancellationRecordQueryService#list} が
     * {@code AccessControlService} を直接呼び出し、操作者が受取先側の精算管理者・受取先本人・
     * {@code SYSTEM_ADMIN} のいずれかである記録のみへ絞り込む。<b>この絞り込みは
     * 「見えるかどうか」であり、免除できるかどうかの最終判定ではない</b>——免除の実行時には
     * {@link RecruitmentCancellationFeeWaiveService#waive} が payment ドメインで別途再検証する
     * （二段構え。詳細は {@link RecruitmentCancellationRecordQueryService} の Javadoc）。</p>
     *
     * <p>債務者（キャンセル料を負っている本人）向けの一覧は本 EP のスコープ外。</p>
     *
     * @param status 絞り込む決済ステータス（カンマ区切り。未指定なら免除可能な既定 3 状態）
     * @param page   ページ番号（0 始まり）
     * @param size   1 ページの件数
     * @return ページング済みの一覧
     */
    @GetMapping
    @Operation(summary = "キャンセル料記録の一覧",
            description = "受取先側の管理者・受取先本人・運営管理者が、自分が受け取るべき"
                    + "キャンセル料の記録を一覧する（免除対象を選ぶための一覧）。")
    public ResponseEntity<PagedResponse<RecruitmentCancellationRecordSummaryResponse>> list(
            @RequestParam(required = false) List<CancellationPaymentStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentCancellationRecordSummaryResponse> result = queryService.list(
                SecurityUtils.getCurrentUserId(), status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * キャンセル料を免除する。
     *
     * <p>認可根治済み: {@link RecruitmentCancellationFeeWaiveService#waive} が
     * 「受取先側の精算管理者（escrow の payee に基づく TEAM/ORG/個人の 3 種）」または
     * {@code SYSTEM_ADMIN} であることを検証し、いずれでもなければ {@code COMMON_002}(403) で拒否する。
     * キャンセル料を負っている本人はこのいずれにも該当しないため免除できない（§10.2）。</p>
     *
     * <p>{@code PAID} への免除は 409（免除ではなく返金の話であり混同させない）。
     * {@code WAIVED} への再免除は冪等に 200 で返す（終端状態なら何でも 409、にはしない）。</p>
     *
     * @param recordId 対象のキャンセル記録 ID
     * @param request  免除理由（必須）
     * @return 本文なしの 200
     */
    @AuthorizedInService
    @PostMapping("/{recordId}/waive")
    @Operation(summary = "キャンセル料の免除",
            description = "受取先側の管理者または運営管理者がキャンセル料の請求を取り消す。"
                    + "免除は債権の放棄を必ず行うが、そのユーザーに他の未払いが残っている場合は"
                    + "募集への申込制限は解除されない。")
    public ResponseEntity<Void> waive(
            @PathVariable Long recordId,
            @Valid @RequestBody WaiveCancellationFeeRequest request) {
        waiveService.waive(recordId, SecurityUtils.getCurrentUserId(), request.getReason());
        return ResponseEntity.ok().build();
    }
}
