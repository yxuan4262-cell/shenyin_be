package com.tongji.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>用于在业务校验失败时携带明确的 {@link ErrorCode} 抛出，由全局异常处理器统一转为 HTTP 响应。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码，用于前端/调用方做稳定的错误分支处理。
     */
    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认文案构造异常。
     *
     * @param errorCode 错误码（必填）
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用自定义文案构造异常（错误码不变）。
     *
     * @param errorCode 错误码（必填）
     * @param message 自定义提示文案
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用自定义文案与根因构造异常（错误码不变）。
     *
     * <p>用于包装底层异常（如序列化、持久化失败）后抛出：既携带稳定的
     * {@link ErrorCode} 供全局异常处理器转换为 HTTP 响应，又通过 {@code cause}
     * 保留原始堆栈便于排查——避免“包装后丢失根因”。</p>
     *
     * @param errorCode 错误码（必填）
     * @param message   自定义提示文案
     * @param cause     原始异常（根因）
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

}
