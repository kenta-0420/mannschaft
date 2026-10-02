package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.dto.ProxyInputRecordResponse;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 操作記録のHTTP応答への変換。 */
@Mapper(componentModel = "spring")
public interface ProxyInputRecordMapper {
    @Mapping(target = "consentId", source = "proxyInputConsentId")
    ProxyInputRecordResponse toResponse(ProxyInputRecordEntity entity);
}
