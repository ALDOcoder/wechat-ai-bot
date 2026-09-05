package com.example.wechataibot.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话设置：权限 + 偏好，一个会话一行。
 *
 * <p>preferredModel：该会话默认模型（zhipu / deepseek）；
 * allowDeepseek：是否允许该会话切换到付费模型（默认 0，全禁）。
 */
@Data
public class ConversationSetting {

    /** 会话键：私聊=好友名 / 群聊=group:群名:发送者 / web=web:sessionId */
    private String conversationId;

    /** friend / group / web */
    private String scene;

    /** zhipu（默认）/ deepseek */
    private String preferredModel;

    private Boolean allowDeepseek;

    private LocalDateTime updatedAt;
}
