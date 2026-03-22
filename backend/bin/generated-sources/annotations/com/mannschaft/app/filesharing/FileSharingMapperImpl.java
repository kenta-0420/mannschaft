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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class FileSharingMapperImpl implements FileSharingMapper {

    @Override
    public FolderResponse toFolderResponse(SharedFolderEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        Long userId = null;
        Long parentId = null;
        String name = null;
        String description = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        userId = entity.getUserId();
        parentId = entity.getParentId();
        name = entity.getName();
        description = entity.getDescription();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();

        FolderResponse folderResponse = new FolderResponse( id, scopeType, teamId, organizationId, userId, parentId, name, description, createdBy, createdAt, updatedAt );

        return folderResponse;
    }

    @Override
    public List<FolderResponse> toFolderResponseList(List<SharedFolderEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FolderResponse> list = new ArrayList<FolderResponse>( entities.size() );
        for ( SharedFolderEntity sharedFolderEntity : entities ) {
            list.add( toFolderResponse( sharedFolderEntity ) );
        }

        return list;
    }

    @Override
    public FileResponse toFileResponse(SharedFileEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long folderId = null;
        String name = null;
        String fileKey = null;
        Long fileSize = null;
        String contentType = null;
        String description = null;
        Long createdBy = null;
        Integer currentVersion = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        folderId = entity.getFolderId();
        name = entity.getName();
        fileKey = entity.getFileKey();
        fileSize = entity.getFileSize();
        contentType = entity.getContentType();
        description = entity.getDescription();
        createdBy = entity.getCreatedBy();
        currentVersion = entity.getCurrentVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        FileResponse fileResponse = new FileResponse( id, folderId, name, fileKey, fileSize, contentType, description, createdBy, currentVersion, createdAt, updatedAt );

        return fileResponse;
    }

    @Override
    public List<FileResponse> toFileResponseList(List<SharedFileEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FileResponse> list = new ArrayList<FileResponse>( entities.size() );
        for ( SharedFileEntity sharedFileEntity : entities ) {
            list.add( toFileResponse( sharedFileEntity ) );
        }

        return list;
    }

    @Override
    public FileVersionResponse toVersionResponse(SharedFileVersionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long fileId = null;
        Integer versionNumber = null;
        String fileKey = null;
        Long fileSize = null;
        String contentType = null;
        Long uploadedBy = null;
        String comment = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        fileId = entity.getFileId();
        versionNumber = entity.getVersionNumber();
        fileKey = entity.getFileKey();
        fileSize = entity.getFileSize();
        contentType = entity.getContentType();
        uploadedBy = entity.getUploadedBy();
        comment = entity.getComment();
        createdAt = entity.getCreatedAt();

        FileVersionResponse fileVersionResponse = new FileVersionResponse( id, fileId, versionNumber, fileKey, fileSize, contentType, uploadedBy, comment, createdAt );

        return fileVersionResponse;
    }

    @Override
    public List<FileVersionResponse> toVersionResponseList(List<SharedFileVersionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FileVersionResponse> list = new ArrayList<FileVersionResponse>( entities.size() );
        for ( SharedFileVersionEntity sharedFileVersionEntity : entities ) {
            list.add( toVersionResponse( sharedFileVersionEntity ) );
        }

        return list;
    }

    @Override
    public PermissionResponse toPermissionResponse(FilePermissionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String targetType = null;
        Long targetId = null;
        Long permissionTargetId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        targetType = entity.getTargetType();
        targetId = entity.getTargetId();
        permissionTargetId = entity.getPermissionTargetId();
        createdAt = entity.getCreatedAt();

        String permissionType = entity.getPermissionType().name();
        String permissionTargetType = entity.getPermissionTargetType().name();

        PermissionResponse permissionResponse = new PermissionResponse( id, targetType, targetId, permissionType, permissionTargetType, permissionTargetId, createdAt );

        return permissionResponse;
    }

    @Override
    public List<PermissionResponse> toPermissionResponseList(List<FilePermissionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PermissionResponse> list = new ArrayList<PermissionResponse>( entities.size() );
        for ( FilePermissionEntity filePermissionEntity : entities ) {
            list.add( toPermissionResponse( filePermissionEntity ) );
        }

        return list;
    }

    @Override
    public StarResponse toStarResponse(SharedFileStarEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long fileId = null;
        Long userId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        fileId = entity.getFileId();
        userId = entity.getUserId();
        createdAt = entity.getCreatedAt();

        StarResponse starResponse = new StarResponse( id, fileId, userId, createdAt );

        return starResponse;
    }

    @Override
    public List<StarResponse> toStarResponseList(List<SharedFileStarEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<StarResponse> list = new ArrayList<StarResponse>( entities.size() );
        for ( SharedFileStarEntity sharedFileStarEntity : entities ) {
            list.add( toStarResponse( sharedFileStarEntity ) );
        }

        return list;
    }

    @Override
    public CommentResponse toCommentResponse(SharedFileCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long fileId = null;
        Long userId = null;
        String body = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        fileId = entity.getFileId();
        userId = entity.getUserId();
        body = entity.getBody();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        CommentResponse commentResponse = new CommentResponse( id, fileId, userId, body, createdAt, updatedAt );

        return commentResponse;
    }

    @Override
    public List<CommentResponse> toCommentResponseList(List<SharedFileCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CommentResponse> list = new ArrayList<CommentResponse>( entities.size() );
        for ( SharedFileCommentEntity sharedFileCommentEntity : entities ) {
            list.add( toCommentResponse( sharedFileCommentEntity ) );
        }

        return list;
    }

    @Override
    public LinkResponse toLinkResponse(SharedFileLinkEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long fileId = null;
        String token = null;
        LocalDateTime expiresAt = null;
        Integer accessCount = null;
        LocalDateTime lastAccessedAt = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        fileId = entity.getFileId();
        token = entity.getToken();
        expiresAt = entity.getExpiresAt();
        accessCount = entity.getAccessCount();
        lastAccessedAt = entity.getLastAccessedAt();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();

        boolean hasPassword = entity.getPasswordHash() != null;

        LinkResponse linkResponse = new LinkResponse( id, fileId, token, expiresAt, hasPassword, accessCount, lastAccessedAt, createdBy, createdAt );

        return linkResponse;
    }

    @Override
    public List<LinkResponse> toLinkResponseList(List<SharedFileLinkEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<LinkResponse> list = new ArrayList<LinkResponse>( entities.size() );
        for ( SharedFileLinkEntity sharedFileLinkEntity : entities ) {
            list.add( toLinkResponse( sharedFileLinkEntity ) );
        }

        return list;
    }

    @Override
    public TagResponse toTagResponse(SharedFileTagEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long fileId = null;
        String tagName = null;
        Long userId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        fileId = entity.getFileId();
        tagName = entity.getTagName();
        userId = entity.getUserId();
        createdAt = entity.getCreatedAt();

        TagResponse tagResponse = new TagResponse( id, fileId, tagName, userId, createdAt );

        return tagResponse;
    }

    @Override
    public List<TagResponse> toTagResponseList(List<SharedFileTagEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TagResponse> list = new ArrayList<TagResponse>( entities.size() );
        for ( SharedFileTagEntity sharedFileTagEntity : entities ) {
            list.add( toTagResponse( sharedFileTagEntity ) );
        }

        return list;
    }
}
