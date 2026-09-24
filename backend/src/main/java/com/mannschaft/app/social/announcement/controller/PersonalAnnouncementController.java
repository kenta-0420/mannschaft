package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedItemDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedMetaDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** F02.6 個人ダッシュボード向けの横断お知らせ API。 */
@RestController
@RequestMapping("/api/v1/announcements")
@Tag(name = "個人横断お知らせウィジェット", description = "F02.6 個人ダッシュボード向け横断お知らせ API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalAnnouncementController {

    private final AnnouncementFeedService announcementFeedService;

    /**
     * 現役のチーム・組織所属を joined_at DESC, id DESC で最大20件選び、
     * 閲覧可能なお知らせを横断取得する。
     *
     * <p>F02.6 の現行契約は limit のみでページ継続要求を受け付けないため、
     * meta.nextCursor は null、meta.hasNext は false を返す。</p>
     *
     * @param limit 取得件数（省略時15、最大50）
     * @param includeRead 既読のお知らせも含めるか
     * @return 個人横断お知らせ一覧
     */
    @GetMapping("/me")
    @Operation(
            summary = "個人横断お知らせ一覧取得",
            description = "現役のチーム・組織所属のお知らせを横断取得する。ページ継続は未提供。")
    public ResponseEntity<AnnouncementFeedResponseDto> getPersonalFeed(
            @RequestParam(defaultValue = "15") int limit,
            @RequestParam(name = "include_read", defaultValue = "false") boolean includeRead) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementFeedService.AnnouncementFeedResult result =
                announcementFeedService.getPersonalFeed(userId, limit, includeRead);
        List<AnnouncementFeedItemDto> items = result.data().stream()
                .map(AnnouncementFeedItemDto::from)
                .toList();
        return ResponseEntity.ok(AnnouncementFeedResponseDto.builder()
                .data(items)
                .meta(AnnouncementFeedMetaDto.builder()
                        .nextCursor(null)
                        .hasNext(false)
                        .unreadCount(result.unreadCount())
                        .build())
                .build());
    }
}
