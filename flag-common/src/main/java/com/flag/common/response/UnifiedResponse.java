package com.flag.common.response;

import lombok.Data;

import java.time.Instant;

@Data
public class UnifiedResponse<T> {
    private int code;
    private String message;
    private T data;
    private long timestamp;

    private UnifiedResponse() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    public static <T> UnifiedResponse<T> success(T data) {
        UnifiedResponse<T> resp = new UnifiedResponse<>();
        resp.code = 0;
        resp.message = "success";
        resp.data = data;
        return resp;
    }

    public static <T> UnifiedResponse<T> success() {
        return success(null);
    }

    public static <T> UnifiedResponse<T> error(int code, String message) {
        UnifiedResponse<T> resp = new UnifiedResponse<>();
        resp.code = code;
        resp.message = message;
        return resp;
    }

    public static <T> UnifiedResponse<T> error(int code, String message, T data) {
        UnifiedResponse<T> resp = new UnifiedResponse<>();
        resp.code = code;
        resp.message = message;
        resp.data = data;
        return resp;
    }
}