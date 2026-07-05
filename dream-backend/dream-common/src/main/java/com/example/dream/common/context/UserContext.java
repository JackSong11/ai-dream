package com.example.dream.common.context;

/**
 * 登录用户上下文工具类。
 * <p>基于 {@link ThreadLocal} 存储当前请求线程绑定的登录用户 userId，
 * 登录成功后由拦截器写入，业务代码可通过 {@link #getUserId()} 随时获取。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 *   String userId = UserContext.getUserId();
 * }</pre>
 *
 * @author dream
 */
public final class UserContext {

    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 设置当前线程的登录用户 userId
     *
     * @param userId 登录账号
     */
    public static void setUserId(String userId) {
        USER_ID_HOLDER.set(userId);
    }

    /**
     * 获取当前线程的登录用户 userId
     *
     * @return userId，未登录返回 null
     */
    public static String getUserId() {
        return USER_ID_HOLDER.get();
    }

    /**
     * 是否已登录
     */
    public static boolean isLogin() {
        return USER_ID_HOLDER.get() != null;
    }

    /**
     * 清除当前线程上下文，请求结束时务必调用，防止线程复用导致数据串号
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}