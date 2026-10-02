package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.proxy.ProxyInputRecordMapper;
import com.mannschaft.app.proxy.dto.ProxyInputConsentResponse;
import com.mannschaft.app.proxy.dto.ProxyInputRecordResponse;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 認可済みの同意一覧と実操作履歴をproxyドメイン内の読み取りTXで取得する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyConsentManagementQueryService {
    private final ProxyInputConsentRepository consents;
    private final ProxyInputRecordRepository records;
    private final ProxyInputRecordMapper recordMapper;

    public PagedResponse<ProxyInputConsentResponse> getConsents(Long organizationId, PageRequest pageable) {
        Page<Long> ids = consents.findPageIdsByOrganizationId(organizationId, pageable);
        // collection fetchにPageableを付けるとHibernateが全件を読み込むため二段階で取得する。
        Map<Long, ProxyInputConsentEntity> byId = ids.isEmpty() ? Map.of()
                : consents.findByIdIn(ids.getContent()).stream().collect(Collectors.toMap(
                        ProxyInputConsentEntity::getId, Function.identity()));
        List<ProxyInputConsentResponse> data = ids.getContent().stream()
                .map(byId::get).map(ProxyInputConsentResponse::from).toList();
        return PagedResponse.of(data, meta(ids));
    }

    public PagedResponse<ProxyInputRecordResponse> getRecords(
            Long organizationId, Long subjectUserId, PageRequest pageable) {
        var result = organizationId == null ? records.findPageBySubjectUserId(subjectUserId, pageable)
                : subjectUserId == null ? records.findPageByOrganizationId(organizationId, pageable)
                : records.findPageByOrganizationIdAndSubjectUserId(organizationId, subjectUserId, pageable);
        return PagedResponse.of(result.getContent().stream().map(recordMapper::toResponse).toList(), meta(result));
    }

    private PagedResponse.PageMeta meta(Page<?> page) {
        return new PagedResponse.PageMeta(page.getTotalElements(), page.getNumber(), page.getSize(),
                page.getTotalPages());
    }
}
