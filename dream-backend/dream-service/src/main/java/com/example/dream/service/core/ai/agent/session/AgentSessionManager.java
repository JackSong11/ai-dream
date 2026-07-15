package com.example.dream.service.core.ai.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 会话管理器，对应 nanobot session.manager.SessionManager。
 * <p>
 * 采用内存 Map 维护 sessionKey → {@link AgentSession} 映射，线程安全。
 * 如需持久化，可在 {@link #save(AgentSession)} 中对接 DB（本项目已有会话表），
 * 此处保留可扩展的存取入口。
 *
 * @author dream
 */
@Slf4j
@Component
public class AgentSessionManager {

    private final ConcurrentMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话。
     *
     * @param key 会话 key
     * @return 会话对象
     */
    public AgentSession getOrCreate(String key) {
        return sessions.computeIfAbsent(key, AgentSession::new);
    }

    /**
     * 保存会话（内存态已即时可见，此处预留持久化扩展点）。
     *
     * @param session 会话对象
     */
    public void save(AgentSession session) {
        if (session == null) {
            return;
        }
        sessions.put(session.getKey(), session);
    log.debug("[AgentSessionManager] 会话已保存: key={}, messages={}",
                session.getKey(), session.getMessages().size());
    }

    /**
     * 移除会话（对应会话销毁）。
     *
     * @param key 会话 key
     */
    public void remove(String key) {
        sessions.remove(key);
    }

    /**
     * 会话是否存在。
     */
    public boolean exists(String key) {
        return sessions.containsKey(key);
    }
}