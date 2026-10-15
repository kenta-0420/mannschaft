package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.MultipartContentTarget;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclScopeType;

/** CMS 内部の保存台帳を Entity を含まない共通 ACL 参照へ変換する。 */
public final class BlogMediaAclTarget {
    private BlogMediaAclTarget() {}

    public static MultipartContentTarget from(BlogMediaUploadEntity media) {
        if (media.getScopeType() == null || media.getScopeId() == null || media.getId() == null) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        String id = String.valueOf(media.getId());
        return new MultipartContentTarget(
                new StorageAclScope(StorageAclScopeType.valueOf(media.getScopeType()),
                        String.valueOf(media.getScopeId())),
                new StorageAclContentReference("BLOG_MEDIA_UPLOAD", id),
                new StorageAclAttachmentBinding("BLOG_MEDIA_UPLOAD", id));
    }
}
