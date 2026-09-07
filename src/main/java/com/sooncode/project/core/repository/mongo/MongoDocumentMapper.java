package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.SnapshotWrapper;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * Mongo 事件溯源文档的统一映射定义。
 *
 * <p>单条仓储和批量仓储都通过这里构造 Mongo 文档，避免两套字段定义
 * 随着版本演进逐渐产生差异。</p>
 */
final class MongoDocumentMapper {
    static final String EVENT_METADATA = "eventMetadata";
    static final String EVENT_SOURCE = "eventSource";
    static final String EVENT_SNAPSHOT = "eventSnapshot";
    static final String ID = "id";
    static final String VERSION = "version";
    static final String STREAM_ID = "streamId";
    static final String INVALID = "invalid";
    static final String TYPE = "type";
    static final String CREATE_DATE = "createDate";
    static final String VER = "ver";
    static final String EVENT = "event";
    static final String EVENT_TYPE = "eventType";
    static final String CREATER = "creater";
    static final String DESCRIPTION = "description";
    static final String SNAPSHOT_TYPE = "snapshotType";
    static final String SNAPSHOT = "snapshot";

    private MongoDocumentMapper() {
    }

    static Document metadata(EventStream stream) {
        return new Document(ID, stream.getId())
                .append(VERSION, stream.getVersion())
                .append(INVALID, stream.getIsInvalid())
                .append(TYPE, stream.getEntityType().getName())
                .append(CREATE_DATE, stream.getCreateDate())
                .append(VER, MongoEventSourcingRepository.VER);
    }

    static Map<String, Object> metadataFields(EventStream stream) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(VERSION, stream.getVersion());
        fields.put(INVALID, stream.getIsInvalid());
        return fields;
    }

    static Document event(EventWrapper wrapper) {
        return new Document(ID, wrapper.getId())
                .append(VERSION, wrapper.getEventVersion())
                .append(STREAM_ID, wrapper.getEventStreamId())
                .append(EVENT, MongoJsonUtil.toJsonObject(wrapper.getEvent()))
                .append(EVENT_TYPE, wrapper.getEventType().getName())
                .append(CREATER, MongoJsonUtil.toJsonObject(wrapper.getCreater()))
                .append(CREATE_DATE, wrapper.getCreateDate())
                .append(DESCRIPTION, MongoJsonUtil.toJsonObject(wrapper.getDescription()));
    }

    static Document snapshot(SnapshotWrapper wrapper) {
        return new Document(STREAM_ID, wrapper.getStreamId())
                .append(SNAPSHOT_TYPE, wrapper.getSnapshotType().getName())
                .append(SNAPSHOT, MongoJsonUtil.toJsonObject(wrapper.getSnapshot()))
                .append(CREATE_DATE, wrapper.getCreateDate());
    }

    static Map<String, Object> snapshotFields(SnapshotWrapper wrapper) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(SNAPSHOT_TYPE, wrapper.getSnapshotType().getName());
        fields.put(SNAPSHOT, MongoJsonUtil.toJsonObject(wrapper.getSnapshot()));
        fields.put(CREATE_DATE, wrapper.getCreateDate());
        return fields;
    }

    static EventStream toEventStream(Document document) {
        String streamName = document == null ? null : document.getString(ID);
        try {
            return new EventStream(document.getString(ID), document.getInteger(VERSION),
                    document.getInteger(INVALID), Class.forName(document.getString(TYPE)),
                    document.getDate(CREATE_DATE));
        } catch (Exception ex) {
            throw new DomainException("读取元数据失败:" + streamName);
        }
    }
}
