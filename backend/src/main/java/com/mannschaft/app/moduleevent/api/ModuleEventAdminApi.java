package com.mannschaft.app.moduleevent.api;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.moduleevent.admin.ModuleEventAdminService;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventQuarantineResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Entityを公開せず、運用DTOとIDだけを受け渡す管理窓口。 */
@Component
@RequiredArgsConstructor
public final class ModuleEventAdminApi {
    private final ModuleEventAdminService service;

    public PagedResponse<ModuleEventQuarantineResponse> list(String status, int page, int size) {
        return service.list(status, page, size);
    }

    public void resume(UUID publicationId, String reason) {
        service.resume(publicationId, reason);
    }
}
