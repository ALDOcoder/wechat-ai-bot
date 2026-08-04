package com.example.wechataibot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 个人微信自动回复机器人启动类。
 *
 * <p>⚠️ 风险提示：
 * <ul>
 *     <li>本项目使用非官方网页版协议操作个人微信，违反微信用户协议，
 *         账号存在被限制登录、封禁的风险，请务必使用【测试小号】运行；</li>
 *     <li>聊天内容会发送到远端大模型服务，注意保护隐私。</li>
 * </ul>
 */




@SpringBootApplication
public class WechatAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WechatAiApplication.class, args);
    }
}