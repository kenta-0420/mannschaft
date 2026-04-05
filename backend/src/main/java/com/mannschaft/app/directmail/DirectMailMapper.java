package com.mannschaft.app.directmail;

import com.mannschaft.app.directmail.dto.DirectMailRecipientResponse;
import com.mannschaft.app.directmail.dto.DirectMailResponse;
import com.mannschaft.app.directmail.dto.DirectMailTemplateResponse;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.entity.DirectMailTemplateEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * ダイレクトメール機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface DirectMailMapper {

    DirectMailResponse toMailResponse(DirectMailLogEntity entity);

    List<DirectMailResponse> toMailResponseList(List<DirectMailLogEntity> entities);

    DirectMailRecipientResponse toRecipientResponse(DirectMailRecipientEntity entity);

    List<DirectMailRecipientResponse> toRecipientResponseList(List<DirectMailRecipientEntity> entities);

    DirectMailTemplateResponse toTemplateResponse(DirectMailTemplateEntity entity);

    List<DirectMailTemplateResponse> toTemplateResponseList(List<DirectMailTemplateEntity> entities);
}
