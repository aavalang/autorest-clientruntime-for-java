/**
 *
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 *
 */

package com.microsoft.rest.serializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.microsoft.rest.protocol.CollectionFormat;
import com.microsoft.rest.protocol.SerializerAdapter;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * A serialization helper class wrapped around {@link JacksonConverterFactory} and {@link ObjectMapper}.
 */
public class JacksonAdapter implements SerializerAdapter<ObjectMapper> {
    /**
     * An instance of {@link ObjectMapper} to serialize/deserialize objects.
     */
    private final ObjectMapper mapper;

    /**
     * An instance of {@link ObjectMapper} that does not do flattening.
     */
    private final ObjectMapper simpleMapper;

    /**
     * An instance of {@link JacksonConverterFactory} for Retrofit to use.
     */
    private JacksonConverterFactory converterFactory;

    public JacksonAdapter() {
        simpleMapper = initializeObjectMapper(new ObjectMapper());
        mapper = initializeObjectMapper(new ObjectMapper())
                .registerModule(FlatteningSerializer.getModule(simpleMapper()))
                .registerModule(FlatteningDeserializer.getModule(simpleMapper()));
    }

    /**
     * Gets a static instance of {@link ObjectMapper} that doesn't handle flattening.
     *
     * @return an instance of {@link ObjectMapper}.
     */
    protected ObjectMapper simpleMapper() {
        return simpleMapper;
    }

    public ObjectMapper serializer() {
        return mapper;
    }

    /**
     * Gets a static instance of JacksonConverter factory.
     *
     * @return an instance of JacksonConverter factory.
     */
    public JacksonConverterFactory converterFactory() {
        return JacksonConverterFactory.create(serializer());
    }

    /**
     * Serializes an object into a JSON string using the current {@link ObjectMapper}.
     *
     * @param object the object to serialize.
     * @return the serialized string. Null if the object to serialize is null.
     * @throws IOException exception from serialization.
     */
    public String serialize(Object object) throws IOException {
        if (object == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        serializer().writeValue(writer, object);
        return writer.toString();
    }

    /**
     * Serializes an object into a raw string using the current {@link ObjectMapper}.
     * The leading and trailing quotes will be trimmed.
     *
     * @param object the object to serialize.
     * @return the serialized string. Null if the object to serialize is null.
     */
    public String serializeRaw(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return CharMatcher.is('"').trimFrom(serialize(object));
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Serializes a list into a string with the delimiter specified with the
     * Swagger collection format joining each individual serialized items in
     * the list.
     *
     * @param list the list to serialize.
     * @param format the Swagger collection format.
     * @return the serialized string
     */
    public String serializeList(List<?> list, CollectionFormat format) {
        if (list == null) {
            return null;
        }
        List<String> serialized = new ArrayList<>();
        for (Object element : list) {
            String raw = serializeRaw(element);
            serialized.add(raw != null ? raw : "");
        }
        return Joiner.on(format.getDelimiter()).join(serialized);
    }

    /**
     * Deserializes a string into a {@link T} object using the current {@link ObjectMapper}.
     *
     * @param value the string value to deserialize.
     * @param <T> the type of the deserialized object.
     * @param type the type to deserialize.
     * @return the deserialized object.
     * @throws IOException exception in deserialization
     */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(String value, final Type type) throws IOException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return (T) serializer().readValue(value, new TypeReference<T>() {
            @Override
            public Type getType() {
                return type;
            }
        });
    }

    /**
     * Initializes an instance of JacksonMapperAdapter with default configurations
     * applied to the object mapper.
     *
     * @param mapper the object mapper to use.
     */
    private static ObjectMapper initializeObjectMapper(ObjectMapper mapper) {
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .registerModule(new JodaModule())
                .registerModule(ByteArraySerializer.getModule())
                .registerModule(Base64UrlSerializer.getModule())
                .registerModule(DateTimeSerializer.getModule())
                .registerModule(DateTimeRfc1123Serializer.getModule())
                .registerModule(HeadersSerializer.getModule());
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
        return mapper;
    }
}
