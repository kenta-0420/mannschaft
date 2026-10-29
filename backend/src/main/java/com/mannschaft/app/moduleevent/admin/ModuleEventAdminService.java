package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventQuarantineResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 隔離一覧と明示的な再開を担う内部サービス。先行試練用の最小骨格。 */
@Service
@Transactional(readOnly = true)
public class ModuleEventAdminService {

    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PagedResponse<ModuleEventQuarantineResponse> list(String status, int page, int size) {
        return PagedResponse.of(List.of(), new PagedResponse.PageMeta(0, page, size, 0));
    }

    /** 再開と監査の永続契約が確定するまで、書き込みを行わない骨格。 */
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    public void resume(UUID publicationId, String reason, Long actorUserId) {
        throw new BusinessException(CommonErrorCode.COMMON_003);
    }
}
