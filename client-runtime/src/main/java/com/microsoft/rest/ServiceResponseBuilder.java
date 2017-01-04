/**
 *
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 *
 */

package com.microsoft.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Maps;
import com.microsoft.rest.protocol.ResponseBuilder;
import com.microsoft.rest.protocol.SerializerAdapter;
import okhttp3.ResponseBody;
import retrofit2.Response;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * The builder for building a {@link ServiceResponse}.
 *
 * @param <T> The return type the caller expects from the REST response.
 * @param <E> the exception to throw in case of error.
 */
public final class ServiceResponseBuilder<T, E extends RestException> implements ResponseBuilder<T, E> {
    /**
     * A mapping of HTTP status codes and their corresponding return types.
     */
    private final Map<Integer, Type> responseTypes;

    /**
     * The exception type to thrown in case of error.
     */
    private Class<? extends RestException> exceptionType;

    /**
     * The mapperAdapter used for deserializing the response.
     */
    private final SerializerAdapter<?> serializerAdapter;

    /**
     * Create a ServiceResponseBuilder instance.
     *
     * @param serializerAdapter the serialization utils to use for deserialization operations
     */
    private ServiceResponseBuilder(SerializerAdapter<?> serializerAdapter) {
        this.serializerAdapter = serializerAdapter;
        this.responseTypes = new HashMap<>();
        this.exceptionType = ServiceException.class;
        this.responseTypes.put(0, Object.class);
    }

    @Override
    public ServiceResponseBuilder<T, E> register(int statusCode, final Type type) {
        this.responseTypes.put(statusCode, type);
        return this;
    }


    @Override
    public ServiceResponseBuilder<T, E> registerError(final Class<? extends RestException> type) {
        this.exceptionType = type;
        try {
            Field f = type.getDeclaredField("body");
            this.responseTypes.put(0, f.getType());
        } catch (NoSuchFieldException e) {
            // AutoRestException always has a body. Register Object as a fallback plan.
            this.responseTypes.put(0, Object.class);
        }
        return this;
    }

    /**
     * Register all the mappings from a response status code to a response
     * destination type stored in a {@link Map}.
     *
     * @param responseTypes the mapping from response status codes to response types.
     * @return the same builder instance.
     */
    public ServiceResponseBuilder<T, E> registerAll(Map<Integer, Type> responseTypes) {
        this.responseTypes.putAll(responseTypes);
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServiceResponse<T> build(Response<ResponseBody> response) throws E, IOException {
        if (response == null) {
            return null;
        }

        int statusCode = response.code();
        ResponseBody responseBody;
        if (response.isSuccessful()) {
            responseBody = response.body();
        } else {
            responseBody = response.errorBody();
        }

        if (responseTypes.containsKey(statusCode)) {
            return new ServiceResponse<>((T) buildBody(statusCode, responseBody), response);
        } else if (response.isSuccessful() && responseTypes.size() == 1) {
            return new ServiceResponse<>((T) buildBody(statusCode, responseBody), response);
        } else if ("GET".equals(response.raw().request().method()) && statusCode == 404) {
            return new ServiceResponse<>(null, response);
        } else {
            try {
                String responseContent = responseBody.string();
                responseBody = ResponseBody.create(responseBody.contentType(), responseContent);
                E exception = (E) exceptionType.getConstructor(String.class).newInstance(responseContent);
                exceptionType.getMethod("setResponse", response.getClass()).invoke(exception, response);
                exceptionType.getMethod("setBody", (Class<?>) responseTypes.get(0)).invoke(exception, buildBody(statusCode, responseBody));
                throw exception;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new IOException("Invalid status code " + statusCode + ", but an instance of " + exceptionType.getCanonicalName()
                    + " cannot be created.", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public ServiceResponse<T> buildEmpty(Response<Void> response) throws E, IOException {
        int statusCode = response.code();
        if (responseTypes.containsKey(statusCode)) {
            return new ServiceResponse<>(response);
        } else if (response.isSuccessful() && responseTypes.size() == 1) {
            return new ServiceResponse<>(response);
        } else {
            try {
                E exception = (E) exceptionType.getConstructor(String.class).newInstance("Invalid status code " + statusCode);
                exceptionType.getMethod("setResponse", response.getClass()).invoke(exception, response);
                response.errorBody().close();
                throw exception;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new IOException("Invalid status code " + statusCode + ", but an instance of " + exceptionType.getCanonicalName()
                        + " cannot be created.", e);
            }
        }
    }

    @Override
    public <THeader> ServiceResponseWithHeaders<T, THeader> buildWithHeaders(Response<ResponseBody> response, Class<THeader> headerType) throws E, IOException {
        ServiceResponse<T> bodyResponse = build(response);
        THeader headers = serializerAdapter.deserialize(
                serializerAdapter.serialize(response.headers()),
                headerType);
        return new ServiceResponseWithHeaders<>(bodyResponse.getBody(), headers, bodyResponse.getResponse());
    }

    @Override
    public <THeader> ServiceResponseWithHeaders<T, THeader> buildEmptyWithHeaders(Response<Void> response, Class<THeader> headerType) throws E, IOException {
        ServiceResponse<T> bodyResponse = buildEmpty(response);
        THeader headers = serializerAdapter.deserialize(
                serializerAdapter.serialize(response.headers()),
                headerType);
        ServiceResponseWithHeaders<T, THeader> serviceResponse = new ServiceResponseWithHeaders<>(headers, bodyResponse.getHeadResponse());
        serviceResponse.setBody(bodyResponse.getBody());
        return serviceResponse;
    }

    /**
     * Builds the body object from the HTTP status code and returned response
     * body undeserialized and wrapped in {@link ResponseBody}.
     *
     * @param statusCode the HTTP status code
     * @param responseBody the response body
     * @return the response body, deserialized
     * @throws IOException thrown for any deserialization errors
     */
    private Object buildBody(int statusCode, ResponseBody responseBody) throws IOException {
        if (responseBody == null) {
            return null;
        }

        Type type;
        if (responseTypes.containsKey(statusCode)) {
            type = responseTypes.get(statusCode);
        } else if (responseTypes.get(0) != Object.class) {
            type = responseTypes.get(0);
        } else {
            type = new TypeReference<T>() { }.getType();
        }

        // Void response
        if (type == Void.class) {
            return null;
        }
        // Return raw response if InputStream is the target type
        else if (type == InputStream.class) {
            InputStream stream = responseBody.byteStream();
            return stream;
        }
        // Deserialize
        else {
            String responseContent = responseBody.string();
            responseBody.close();
            if (responseContent.length() <= 0) {
                return null;
            }
            return serializerAdapter.deserialize(responseContent, type);
        }
    }

    /**
     * @return the exception type to thrown in case of error.
     */
    public Class<? extends RestException> exceptionType() {
        return exceptionType;
    }

    /**
     * Check if the returned status code will be considered a success for
     * this builder.
     *
     * @param statusCode the status code to check
     * @return true if it's a success, false otherwise.
     */
    public boolean isSuccessful(int statusCode) {
        return responseTypes != null && responseTypes.containsKey(statusCode);
    }

    /**
     * A factory to create a service response builder.
     */
    public static final class Factory implements ResponseBuilder.Factory {
        private final Map<Map.Entry<String, String>, ServiceResponseBuilder<?, ?>> cachedBuilders;
        private final SerializerAdapter<?> serializerAdapter;

        /**
         * Creates a factory with a serializer adapter.
         *
         * @param serializerAdapter the serializer adapter instance.
         */
        public Factory(final SerializerAdapter<?> serializerAdapter) {
            this.cachedBuilders = new HashMap<>();
            this.serializerAdapter = serializerAdapter;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T, E extends RestException> ServiceResponseBuilder<T, E> newInstance(Class<T> returnType, Class<E> exceptionType) {
            Map.Entry<String, String> key = Maps.immutableEntry(returnType.getName(), exceptionType.getName());
            if (!cachedBuilders.containsKey(key)) {
                cachedBuilders.put(key, new ServiceResponseBuilder<T, E>(serializerAdapter));
            }
            return (ServiceResponseBuilder<T, E>) cachedBuilders.get(key);
        }
    }
}
