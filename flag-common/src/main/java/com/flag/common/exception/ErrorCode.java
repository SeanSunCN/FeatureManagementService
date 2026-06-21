package com.flag.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // General errors 10000-19999
    BAD_REQUEST(10000, "bad request"),
    UNAUTHORIZED(10001, "unauthorized"),
    FORBIDDEN(10002, "forbidden"),
    NOT_FOUND(10003, "resource not found"),
    METHOD_NOT_ALLOWED(10004, "method not allowed"),
    INTERNAL_ERROR(10005, "internal server error"),
    SERVICE_UNAVAILABLE(10006, "service unavailable"),

    // Business errors 20000-29999
    APP_NOT_FOUND(20000, "application not found"),
    APP_ID_CONFLICT(20001, "appId already exists"),
    FLAG_NOT_FOUND(20010, "feature flag not found"),
    FLAG_KEY_CONFLICT(20011, "flag key already exists"),
    FLAG_VERSION_CONFLICT(20012, "flag version conflict"),
    FLAG_DISABLED(20013, "feature flag is disabled"),

    // Data plane errors 30000-39999
    EVAL_PARAM_INVALID(30000, "evaluation parameter invalid"),
    EVAL_ENGINE_ERROR(30001, "evaluation engine error"),

    // Pipeline errors 40000-49999
    INGEST_OVERLOAD(40000, "ingest channel overload, request degraded"),
    KAFKA_SEND_FAILED(40001, "audit log send failed"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}