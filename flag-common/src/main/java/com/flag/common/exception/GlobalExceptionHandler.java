package com.flag.common.exception;

import com.flag.common.response.UnifiedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public UnifiedResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return UnifiedResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public UnifiedResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return UnifiedResponse.error(ErrorCode.BAD_REQUEST.getCode(), e.getMessage());
    }

    /**
     * Handle JPA optimistic lock failure caused by concurrent admin modifications.
     * Returns 409 Conflict with specific error code instead of a raw 500.
     * Frontend can catch code=20012 and prompt the user to refresh.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public UnifiedResponse<Void> handleOptimisticLock(org.springframework.dao.OptimisticLockingFailureException e) {
        log.warn("Optimistic lock failure: {}", e.getMessage());
        return UnifiedResponse.error(ErrorCode.FLAG_VERSION_CONFLICT.getCode(),
                "The flag was modified by another admin. Please refresh and retry.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public UnifiedResponse<Void> handleException(Exception e) {
        log.error("Unexpected error: ", e);
        return UnifiedResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), "Internal server error");
    }
}