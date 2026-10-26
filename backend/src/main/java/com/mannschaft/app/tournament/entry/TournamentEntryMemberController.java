package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.entry.dto.EntryLoadResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberListResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberSummaryResponse;
import com.mannschaft.app.tournament.entry.dto.LoadFromTeamRequest;
import com.mannschaft.app.tournament.entry.dto.UpsertEntryMembersRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
// NOTE: apply-template は TournamentEntryTemplateController で提供

import java.util.UUID;

/**
 * 大会エントリー表メンバー管理コントローラー。
 *
 * <p>F08.7 Phase 9: エントリー表の取得・一括ロード・全置換・個別削除・PDF出力・サマリー取得。</p>
 *
 * <p>設計書: docs/features/F08.7_tournament_league.md §Phase9</p>
 */
@RestController
@Tag(name = "大会エントリー表", description = "F08.7 Phase 9 エントリー表メンバー管理")
@RequiredArgsConstructor
public class TournamentEntryMemberController {

    private final TournamentEntryMemberService entryMemberService;

    /**
     * エントリー表メンバー一覧を取得する。
     *
     * @param orgId              組織ID
     * @param tId                大会ID
     * @param divId              ディビジョンID
     * @param pId                参加チームID
     * @param includeTeamMembers チームメンバー候補を含めるか
     * @return エントリーメンバー一覧
     */
    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members")
    @Operation(summary = "エントリー表メンバー一覧取得")
    public ResponseEntity<ApiResponse<EntryMemberListResponse>> getEntryMembers(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId,
            @RequestParam(defaultValue = "false") boolean includeTeamMembers) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryMemberListResponse result = entryMemberService.getEntryMembers(
                orgId, tId, divId, pId, includeTeamMembers, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * チームメンバーからエントリー表を一括ロードする。
     *
     * @param orgId 組織ID
     * @param tId   大会ID
     * @param divId ディビジョンID
     * @param pId   参加チームID
     * @param req   ロードリクエスト
     * @return ロード結果
     */
    @PostMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members/load-from-team")
    @Operation(summary = "チームメンバーから一括ロード")
    public ResponseEntity<ApiResponse<EntryLoadResponse>> loadFromTeam(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId,
            @Valid @RequestBody LoadFromTeamRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryLoadResponse result = entryMemberService.loadFromTeamMembers(
                orgId, tId, divId, pId, req, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * エントリー表メンバーを全置換（確定保存）する。
     *
     * @param orgId 組織ID
     * @param tId   大会ID
     * @param divId ディビジョンID
     * @param pId   参加チームID
     * @param req   全置換リクエスト
     * @return 更新後のエントリーメンバー一覧
     */
    @PutMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members")
    @Operation(summary = "エントリー全置換（確定保存）")
    public ResponseEntity<ApiResponse<EntryMemberListResponse>> upsertEntryMembers(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId,
            @Valid @RequestBody UpsertEntryMembersRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryMemberListResponse result = entryMemberService.upsertEntryMembers(
                orgId, tId, divId, pId, req, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * エントリーメンバーを個別削除する。
     *
     * @param orgId         組織ID
     * @param tId           大会ID
     * @param divId         ディビジョンID
     * @param pId           参加チームID
     * @param entryMemberId 削除対象エントリーメンバーID
     * @param force         強制削除フラグ
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members/{entryMemberId}")
    @Operation(summary = "エントリーメンバー個別削除")
    public ResponseEntity<Void> deleteEntryMember(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId,
            @PathVariable UUID entryMemberId,
            @RequestParam(defaultValue = "false") boolean force) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        entryMemberService.deleteEntryMember(orgId, tId, divId, pId, entryMemberId, force, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * エントリー表PDFを出力する。
     *
     * @param orgId 組織ID
     * @param tId   大会ID
     * @param divId ディビジョンID
     * @param pId   参加チームID
     * @return PDF バイト列
     */
    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members/pdf")
    @Operation(summary = "エントリー表PDF出力")
    public ResponseEntity<byte[]> generateEntryPdf(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        byte[] pdf = entryMemberService.generateEntryPdf(orgId, tId, divId, pId, currentUserId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "entry-members.pdf");
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    /**
     * 全チームエントリーサマリーを取得する（主催者向け）。
     *
     * @param orgId 組織ID
     * @param tId   大会ID
     * @param divId ディビジョンID
     * @return エントリーサマリー
     */
    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/entry-summary")
    @Operation(summary = "全チームエントリーサマリー（主催者向け）")
    public ResponseEntity<ApiResponse<EntryMemberSummaryResponse>> getEntrySummary(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryMemberSummaryResponse result = entryMemberService.getEntrySummary(
                orgId, tId, divId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
