package com.example.agentcostcontrol.data;

/** A safe error contract for screens; server details are limited to a diagnostic code. */
public final class ApiException extends Exception {
    public enum Category {
        AUTH_REQUIRED,
        INVALID_CREDENTIALS,
        EMAIL_CONFIRMATION_REQUIRED,
        PROFILE_REQUIRED,
        RETRY_REQUIRED,
        VALIDATION,
        CONFLICT,
        FORBIDDEN,
        NOT_FOUND,
        NETWORK,
        CONFIGURATION,
        SESSION_STORAGE,
        SERVER,
        DATA_FORMAT,
        UNKNOWN
    }

    private final Category category;
    private final String userMessage;
    private final String serverCode;
    private final int httpStatus;

    public ApiException(Category category, String userMessage, String serverCode, int httpStatus, Throwable cause) {
        super(userMessage, cause);
        this.category = category;
        this.userMessage = userMessage;
        this.serverCode = serverCode;
        this.httpStatus = httpStatus;
    }

    public ApiException(Category category, String userMessage, String serverCode, int httpStatus) {
        this(category, userMessage, serverCode, httpStatus, null);
    }

    public Category getCategory() { return category; }
    public String getUserMessage() { return userMessage; }
    public String getServerCode() { return serverCode; }
    public int getHttpStatus() { return httpStatus; }
}
