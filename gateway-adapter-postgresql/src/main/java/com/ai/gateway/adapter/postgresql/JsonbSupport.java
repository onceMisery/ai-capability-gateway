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
 * Utility for serializing Java objects to PostgreSQL JSONB values and
 * deserializing JSONB column values back to Java objects.
 *
 * <p>Uses a shared {@link ObjectMapper} instance (thread-safe after
 * configuration) to avoid repeated allocation. JSONB values are bound
 * to {@link PreparedStatement} parameters via {@link PGobject} so the
 * PostgreSQL driver sends the correct binary type.</p>
 *
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
     * Serializes the given object to a JSON string.
     *
     * @param obj the object to serialize
     * @return the JSON string, or {@code null} if {@code obj} is {@code null}
     * @throws RuntimeException if serialization fails
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
     * Deserializes a JSON string to the specified type.
     *
     * @param json the JSON string
     * @param type the target type
     * @param <T> the target type
     * @return the deserialized object, or {@code null} if {@code json} is {@code null}
     * @throws RuntimeException if deserialization fails
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
     * Deserializes a JSON string to the specified type reference.
     *
     * @param json the JSON string
     * @param typeRef the type reference
     * @param <T> the target type
     * @return the deserialized object, or {@code null} if {@code json} is {@code null}
     * @throws RuntimeException if deserialization fails
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
     * Binds a JSON string to a {@link PreparedStatement} parameter as a
     * PostgreSQL JSONB value.
     *
     * @param ps the prepared statement
     * @param index the 1-based parameter index
     * @param json the JSON string; if {@code null}, SQL NULL is bound
     * @throws SQLException if binding fails
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
     * Serializes the given object and binds it as a JSONB parameter.
     *
     * @param ps the prepared statement
     * @param index the 1-based parameter index
     * @param obj the object to serialize and bind
     * @throws SQLException if binding fails
     */
    public static void setJsonbObject(PreparedStatement ps, int index, Object obj) throws SQLException {
        setJsonb(ps, index, toJson(obj));
    }
}
