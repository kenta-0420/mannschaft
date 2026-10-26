package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.CharterArticleCreateRequest;
import com.mannschaft.app.village.dto.CharterArticleOrderUpdateRequest;
import com.mannschaft.app.village.dto.CharterArticleResponse;
import com.mannschaft.app.village.dto.CharterArticleUpdateRequest;
import com.mannschaft.app.village.dto.CharterDrafterCreateRequest;
import com.mannschaft.app.village.dto.CharterRevisionCreateRequest;
import com.mannschaft.app.village.dto.VillageCharterResponse;
import com.mannschaft.app.village.service.VillageCharterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.3 村憲章 Controller（全 8 EP・設計書 §4.4/§18）。
 *
 * <h2>認可</h2>
 * <p>read（{@code GET}）は<b>データ依存の開放条件</b>（PUBLIC 村は非メンバーにも開く・§3.2）ゆえ
 * {@code @PreAuthorize} では表現できない。相性 Controller と同じく {@link AuthorizedInService}
 * マーカーで「認可は Service 層で行う」ことを明示し、認可番人（{@code AuthzControllerGuardArchTest}・
 * Wave4）と整合させる。未ログインは各メソッド先頭の {@link SecurityUtils#getCurrentUserId()} が
 * {@code 401} を投げる。write（{@code POST}/{@code PUT}/{@code DELETE}/{@code PATCH}）は Service が
 * 「村状態ガード → 現役 HEADMAN/ELDER」の 2 段（§3.3）で認可する。</p>
 *
 * <ul>
 *   <li>read の根拠: PUBLIC のみ／UNLISTED はメンバー・SYSTEM_ADMIN のみ／それ以外 404（存在秘匿・§3.2）</li>
 *   <li>write の根拠: 生きている村の現役 HEADMAN/ELDER のみ（可視性に依らない・§3.3）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/charter")
@Tag(name = "村憲章 (F17.3)", description = "村ごとの拠りどころ＝憲章の条文・策定者・改定履歴（村ニックネーム世界）")
@RequiredArgsConstructor
// read はデータ依存の開放条件（非メンバーに開く）ゆえ @PreAuthorize では表現できず、
// write は Service の 2 段ガードで認可する。よって全 EP を Service 層認可とし本マーカーで明示する。
@AuthorizedInService
public class VillageCharterController {

    private final VillageCharterService charterService;

    /** 憲章メタ＋条一覧（自動採番）＋策定者＋改定履歴を返す（未制定も 200・§12.2）。 */
    @GetMapping
    @Operation(summary = "村憲章を取得する（read 公開ゲート・PUBLIC は非メンバー可・UNLISTED はメンバー/SYSTEM_ADMIN）")
    public ApiResponse<VillageCharterResponse> get(@PathVariable("villageId") UUID villageId) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.getCharter(villageId, viewerId));
    }

    /** 条を末尾に追加（初回は charter 自動生成・悲観ロック直列化・§4.5）。 */
    @PostMapping("/articles")
    @Operation(summary = "条を末尾に追加する（初回は憲章を自動生成し制定日をセット・§4.5）")
    public ApiResponse<VillageCharterResponse> addArticle(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody CharterArticleCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.addArticle(villageId, request, actorUserId));
    }

    /** 条の本文/付則を更新（条単位 {@code @Version} 層1 楽観ロック・§7）。 */
    @PutMapping("/articles/{articleId}")
    @Operation(summary = "条の本文/付則を更新する（層1 楽観ロック・§7）")
    public ApiResponse<CharterArticleResponse> updateArticle(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("articleId") UUID articleId,
            @Valid @RequestBody CharterArticleUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.updateArticle(villageId, articleId, request, actorUserId));
    }

    /** 条を論理削除し残条を再連番（悲観ロック直列化・§6.3）。 */
    @DeleteMapping("/articles/{articleId}")
    @Operation(summary = "条を論理削除し残る条を再連番する（悲観ロック直列化・409 なし・§6.3）")
    public ApiResponse<VillageCharterResponse> deleteArticle(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("articleId") UUID articleId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.deleteArticle(villageId, articleId, actorUserId));
    }

    /** 条の並び順を一括更新（親 charter {@code @Version} 層2 楽観検査・§7）。 */
    @PatchMapping("/articles/order")
    @Operation(summary = "条の並び順を一括更新する（層2 楽観ロック・charterVersion 同送・§7）")
    public ApiResponse<VillageCharterResponse> reorderArticles(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody CharterArticleOrderUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.reorderArticles(villageId, request, actorUserId));
    }

    /** 策定者を追加（村ニックネームを焼付・§5.2）。 */
    @PostMapping("/drafters")
    @Operation(summary = "策定者を追加する（村ニックネームを焼き付け・§5.2）")
    public ApiResponse<VillageCharterResponse> addDrafter(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody CharterDrafterCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.addDrafter(villageId, request, actorUserId));
    }

    /** 策定者を削除（更新後の憲章全体を返す・再連番・§5.3/AC-16b）。 */
    @DeleteMapping("/drafters/{drafterId}")
    @Operation(summary = "策定者を削除する（更新後の憲章全体を返す・再連番・AC-16b）")
    public ApiResponse<VillageCharterResponse> removeDrafter(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("drafterId") UUID drafterId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.removeDrafter(villageId, drafterId, actorUserId));
    }

    /** 「改正を確定」＝{@code last_revised_at} 更新＋改定履歴に 1 行追記（§8.2）。 */
    @PostMapping("/revisions")
    @Operation(summary = "改正を確定する（改定日・改定履歴を刻む里程標・条文の可視性は変えない・§8.2）")
    public ApiResponse<VillageCharterResponse> addRevision(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody CharterRevisionCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(charterService.addRevision(villageId, request, actorUserId));
    }
}
