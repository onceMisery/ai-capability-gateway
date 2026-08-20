package com.ai.gateway.adapter.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 将 Java 对象序列化为 PostgreSQL 的 JSONB 值、以及将 JSONB 列值反序列化回
 * Java 对象的工具类。
 *
 * <p>使用一个共享的 {@link ObjectMapper} 实例（配置完成后线程安全）以避免重复
 * 分配。JSONB 值通过 {@link PGobject} 绑定到 {@link PreparedStatement} 参数，
 * 以便 PostgreSQL 驱动发送正确的二进制类型。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class JsonbSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonbSupport() {
    }

    /**
     * 将给定的对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化的对象
     * @return JSON 字符串；若 {@code obj} 为 {@code null} 则返回 {@code null}
     * @throws RuntimeException 序列化失败时抛出
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize object to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 反序列化后的对象；若 {@code json} 为 {@code null} 则返回 {@code null}
     * @throws RuntimeException 反序列化失败时抛出
     */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize JSON to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定的 TypeReference 类型。
     *
     * @param json JSON 字符串
     * @param typeRef 类型引用
     * @param <T> 目标类型
     * @return 反序列化后的对象；若 {@code json} 为 {@code null} 则返回 {@code null}
     * @throws RuntimeException 反序列化失败时抛出
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 字符串作为 PostgreSQL 的 JSONB 值绑定到 {@link PreparedStatement} 参数。
     *
     * @param ps 预编译语句
     * @param index 基于 1 的参数索引
     * @param json JSON 字符串；若为 {@code null} 则绑定 SQL NULL
     * @throws SQLException 绑定失败时抛出
     */
    public static void setJsonb(PreparedStatement ps, int index, String json) throws SQLException {
        if (json == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(json);
            ps.setObject(index, pgObject);
        }
    }

    /**
     * 序列化给定对象并将其作为 JSONB 参数绑定。
     *
     * @param ps 预编译语句
     * @param index 基于 1 的参数索引
     * @param obj 待序列化并绑定的对象
     * @throws SQLException 绑定失败时抛出
     */
    public static void setJsonbObject(PreparedStatement ps, int index, Object obj) throws SQLException {
        setJsonb(ps, index, toJson(obj));
    }
}
