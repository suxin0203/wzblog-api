package com.wzblog.common.api;

import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.TraceId;

/**
 * 统一响应壳 —— 全项目所有 HTTP 接口的唯一出参形状（教学详解见 docs/学习文档/05）。
 *
 * <p>【它是什么】四字段 record：业务码 + 人类可读消息 + 业务数据 + 链路追踪号。</p>
 *
 * <pre>{@code
 * {
 *   "code": "OK",            // 业务码：字符串枚举，前端做逻辑判断用（自解释，优于数字码）
 *   "message": "success",    // 文案：可直接展示给用户
 *   "data": { ... },         // 业务数据，出错时为 null
 *   "traceId": "e3f8c2..."   // 与服务端日志同源，用户报障→grep 即可还原全链路日志
 * }
 * }</pre>
 *
 * <p>【为什么用 record】纯数据载体：编译器自动生成只读访问器 code()/data() 与
 * equals/hashCode/toString；字段不可变（无 setter），创建后不可能被中途篡改。
 * 项目约定：DTO/VO/事件这类"传输对象"用 record；数据库实体（需要 MyBatis-Plus 反射写字段）
 * 才用普通类 + Lombok。</p>
 *
 * <p>【为什么用静态工厂而不是直接 new】构造逻辑收口：code 的合法取值、traceId 的采集方式
 * 只写在这一处，调用方写 {@code Result.ok(x)} 永远不可能拼错字段顺序或忘记塞 traceId。
 * 这是《Effective Java》"静态工厂优于构造器"的最小落地。</p>
 *
 * <p>【自己写新接口时怎么用】Controller 方法直接
 * {@code return Result.ok(service.doSomething());}；失败情况不要自己拼 —— 抛
 * {@code BusinessException(ErrorCode.XXX)}，由 GlobalExceptionHandler 统一翻译成
 * {@code Result.fail(...)}。永远不要在 Controller 里 try-catch 后手工拼 Map。</p>
 */
public record Result<T>(String code, String message, T data, String traceId) {

    /**
     * 成功且有数据的响应。
     * @param data 业务数据（任意类型，泛型 T 保证编译期类型检查）
     */
    public static <T> Result<T> ok(T data) {
        // code 固定取 ErrorCode.OK（"OK"），traceId 从 MDC 取当前请求的追踪号：
        // 响应体里的 traceId 与服务端日志行里的完全一致，实现"报障→日志"对账。
        return new Result<>(ErrorCode.OK.code(), "success", data, TraceId.current());
    }

    /** 成功但无数据返回的响应（删除、更新等动作型接口）。Result<Void> 的 data 恒为 null。 */
    public static Result<Void> ok() {
        return new Result<>(ErrorCode.OK.code(), "success", null, TraceId.current());
    }

    /** 失败响应：使用错误码自带默认文案（绝大多数场景够用，文案集中在 ErrorCode 登记）。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    /** 失败响应：覆盖定制文案（如带具体业务上下文"积分不足，还差 5 分"）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, TraceId.current());
    }

    /**
     * 失败响应 + 结构化附加数据。
     * 典型场景：FILE_IN_USE 时在 data 里带上"哪些文章正在引用该文件"的位置清单，
     * 让前端能直接展示冲突详情，而不是只给一句干巴巴的报错。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.code(), message, data, TraceId.current());
    }
}
