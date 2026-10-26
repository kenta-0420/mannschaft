package com.mannschaft.app.digest;

import com.mannschaft.app.digest.dto.DigestConfigResponse;
import com.mannschaft.app.digest.dto.DigestDetailResponse;
import com.mannschaft.app.digest.dto.DigestSummaryResponse;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * ダイジェストドメインのエンティティ⇔DTOマッピング定義。
 */
@Mapper(componentModel = "spring")
public interface DigestMapper {

    /**
     * TimelineDigestConfigEntity → DigestConfigResponse
     */
    @Mapping(target = "scheduleType", expression = "java(config.getScheduleType() != null ? config.getScheduleType().name() : null)")
    @Mapping(target = "digestStyle", expression = "java(config.getDigestStyle() != null ? config.getDigestStyle().name() : null)")
    DigestConfigResponse toConfigResponse(TimelineDigestConfigEntity config);

    /**
     * TimelineDigestEntity → DigestSummaryResponse
     */
    @Mapping(target = "digestStyle", expression = "java(digest.getDigestStyle() != null ? digest.getDigestStyle().name() : null)")
    @Mapping(target = "status", expression = "java(digest.getStatus() != null ? digest.getStatus().name() : null)")
    DigestSummaryResponse toSummaryResponse(TimelineDigestEntity digest);

    /**
     * TimelineDigestEntity → DigestDetailResponse
     * triggeredBy は Service 側でユーザー情報を取得して手動設定するため ignore とする。
     */
    @Mapping(target = "scopeType", expression = "java(digest.getScopeType() != null ? digest.getScopeType().name() : null)")
    @Mapping(target = "digestStyle", expression = "java(digest.getDigestStyle() != null ? digest.getDigestStyle().name() : null)")
    @Mapping(target = "status", expression = "java(digest.getStatus() != null ? digest.getStatus().name() : null)")
    @Mapping(target = "triggeredBy", ignore = true)
    DigestDetailResponse toDetailResponse(TimelineDigestEntity digest);
}
