package com.mannschaft.app.proxy;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.proxy.dto.ProxyInputRecordResponse;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.time.LocalDateTime;

/** 操作記録のHTTP応答への変換。 */
@Mapper(componentModel = "spring")
public interface ProxyInputRecordMapper {
    @Mapping(target = "consentId", source = "proxyInputConsentId")
    ProxyInputRecordResponse toResponse(ProxyInputRecordEntity entity);

    /** 既存の業務ゾーンの壁時計を、操作が起きた瞬間として返す。 */
    default Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }
}
