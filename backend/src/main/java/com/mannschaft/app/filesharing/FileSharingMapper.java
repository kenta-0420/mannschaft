package com.mannschaft.app.filesharing;

import com.mannschaft.app.filesharing.dto.CommentResponse;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.FileVersionResponse;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.dto.PermissionResponse;
import com.mannschaft.app.filesharing.dto.StarResponse;
import com.mannschaft.app.filesharing.dto.TagResponse;
import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import com.mannschaft.app.filesharing.entity.SharedFileCommentEntity;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.filesharing.entity.SharedFileTagEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ファイル共有機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface FileSharingMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    FolderResponse toFolderResponse(SharedFolderEntity entity);

    List<FolderResponse> toFolderResponseList(List<SharedFolderEntity> entities);

    FileResponse toFileResponse(SharedFileEntity entity);

    List<FileResponse> toFileResponseList(List<SharedFileEntity> entities);

    FileVersionResponse toVersionResponse(SharedFileVersionEntity entity);

    List<FileVersionResponse> toVersionResponseList(List<SharedFileVersionEntity> entities);

    @Mapping(target = "permissionType", expression = "java(entity.getPermissionType().name())")
    @Mapping(target = "permissionTargetType", expression = "java(entity.getPermissionTargetType().name())")
    PermissionResponse toPermissionResponse(FilePermissionEntity entity);

    List<PermissionResponse> toPermissionResponseList(List<FilePermissionEntity> entities);

    StarResponse toStarResponse(SharedFileStarEntity entity);

    List<StarResponse> toStarResponseList(List<SharedFileStarEntity> entities);

    CommentResponse toCommentResponse(SharedFileCommentEntity entity);

    List<CommentResponse> toCommentResponseList(List<SharedFileCommentEntity> entities);

    @Mapping(target = "hasPassword", expression = "java(entity.getPasswordHash() != null)")
    LinkResponse toLinkResponse(SharedFileLinkEntity entity);

    List<LinkResponse> toLinkResponseList(List<SharedFileLinkEntity> entities);

    TagResponse toTagResponse(SharedFileTagEntity entity);

    List<TagResponse> toTagResponseList(List<SharedFileTagEntity> entities);
}
