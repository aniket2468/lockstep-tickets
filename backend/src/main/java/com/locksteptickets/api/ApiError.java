package com.locksteptickets.api;

public record ApiError(ErrorDetail error) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(new ErrorDetail(code, message, requestId));
    }

    public record ErrorDetail(String code, String message, String requestId) {
    }
}
