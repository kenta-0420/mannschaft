package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.dto.ChatFolderItemResponse;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class DashboardMapperImpl implements DashboardMapper {

    @Override
    public ChatFolderItemResponse toFolderItemResponse(ChatContactFolderItemEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long itemId = null;

        id = entity.getId();
        itemId = entity.getItemId();

        String itemType = entity.getItemType().name();

        ChatFolderItemResponse chatFolderItemResponse = new ChatFolderItemResponse( id, itemType, itemId );

        return chatFolderItemResponse;
    }

    @Override
    public List<ChatFolderItemResponse> toFolderItemResponseList(List<ChatContactFolderItemEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ChatFolderItemResponse> list = new ArrayList<ChatFolderItemResponse>( entities.size() );
        for ( ChatContactFolderItemEntity chatContactFolderItemEntity : entities ) {
            list.add( toFolderItemResponse( chatContactFolderItemEntity ) );
        }

        return list;
    }
}
