# -*- coding: utf-8 -*-
"""
微信 AI 自动回复机器人 —— Python 桥接端（wxauto 3.9 免费版）

架构：
    Python(wxauto) 监听微信新消息
        -> POST http://127.0.0.1:8080/api/reply   （Java Spring Boot 调 DeepSeek 等远端大模型）
        -> wx.SendMsg(reply, who=...) 把 AI 回复发回微信

适用环境（重要！）：
    - Windows 10/11
    - 微信 PC 客户端 3.9.8.15（wxauto 3.9 只适配 3.9.x 客户端；
      你现在的微信 4.1 需要先降级，或用 wechat_bridge_4x.py）
    - Python 3.9+（wxauto 依赖 uiautomation，仅支持 Windows）
    - 安装依赖见同目录 requirements.txt

风险提示（务必阅读）：
    1. 个人微信自动化违反《微信软件许可及服务协议》，账号存在被限制登录、封禁的风险，
       请务必使用【测试小号】运行，不要使用主号！
    2. 自动回复有风控风险：请控制回复频率，避免高频收发。
    3. 聊天内容会发送到远端大模型服务（DeepSeek/OpenAI），注意保护隐私。
    4. 微信客户端必须保持打开、保持登录、窗口不要最小化到系统托盘（wxauto 依赖 UI）。
"""

import argparse
import hashlib
import os
import re
import sys
import time

import requests

from bot_config import apply_cli_overrides, load_config, should_reply, should_use_rag

# 强制 stdout/stderr 行缓冲：即使输出被重定向或从启动器运行，也能实时看到日志
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(line_buffering=True)


# ---------------------------------------------------------------------------
# 配置（优先级：命令行参数 > 环境变量 > 默认值）
# ---------------------------------------------------------------------------
DEFAULT_JAVA_URL = os.environ.get("BOT_JAVA_URL", "http://127.0.0.1:8080")
DEFAULT_INTERVAL = float(os.environ.get("BOT_POLL_INTERVAL", "2.0"))
DEFAULT_AI_TIMEOUT = int(os.environ.get("BOT_AI_TIMEOUT", "90"))


def parse_args():
    parser = argparse.ArgumentParser(
        description="微信 AI 自动回复机器人（wxauto 3.9 桥接端）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python wechat_bridge_3x.py                 # 默认轮询所有未读新消息\n"
            "  python wechat_bridge_3x.py --ping          # 只检查 Java 服务是否在线\n"
            "  python wechat_bridge_3x.py --reply-groups  # 群消息也回复\n"
            "  python wechat_bridge_3x.py --listen 张三 工作群  # 只监听指定聊天对象\n"
        ),
    )
    parser.add_argument("--java-url", default=DEFAULT_JAVA_URL,
                        help=f"Java 服务地址（默认 {DEFAULT_JAVA_URL}）")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                        help=f"轮询间隔秒数（默认 {DEFAULT_INTERVAL}）")
    parser.add_argument("--ai-timeout", type=int, default=DEFAULT_AI_TIMEOUT,
                        help=f"等待 Java/AI 回复的超时秒数（默认 {DEFAULT_AI_TIMEOUT}）")
    parser.add_argument("--reply-groups", action="store_true",
                        help="群消息也自动回复（默认只回私聊，降低风险）")
    parser.add_argument("--reply-friends", metavar="NAMES",
                        help="只回复这些好友（逗号分隔，覆盖 config.ini 的 friends）")
    parser.add_argument("--rag-friends", metavar="NAMES",
                        help="只有这些好友能触发 Obsidian 知识库检索（逗号分隔，覆盖 config.ini 的 rag_friends）")
    parser.add_argument("--group-names", metavar="NAMES",
                        help="开启群回复后，只回复这些群（逗号分隔，覆盖 config.ini 的 group_names）")
    parser.add_argument("--skip-chats", metavar="NAMES",
                        help="跳过这些会话，不读取也不回复（逗号分隔，覆盖 config.ini 的 skip_chats）")
    parser.add_argument("--config", metavar="PATH",
                        help="配置文件路径（默认 python/config.ini）")
    parser.add_argument("--listen", nargs="+", metavar="NAME",
                        help="只监听指定好友/群名（用 AddListenChat，可传多个）")
    parser.add_argument("--ping", action="store_true",
                        help="检查 Java 服务健康后退出")
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Java 服务调用
# ---------------------------------------------------------------------------
def ping_java(java_url: str) -> bool:
    try:
        resp = requests.get(f"{java_url}/api/health", timeout=5)
        resp.raise_for_status()
        print(f"[OK] Java 服务在线：{java_url} -> {resp.json()}")
        return True
    except Exception as e:
        print(f"[FAIL] 无法连接 Java 服务 {java_url}：{e}")
        print("       请先启动 Spring Boot：mvn spring-boot:run")
        return False


def ask_ai(java_url: str, sender: str, content: str, timeout: int, use_rag: bool = True) -> str:
    """把微信消息发给 Java 端，返回 AI 回复文本。"""
    resp = requests.post(
        f"{java_url}/api/reply",
        json={"sender": sender, "content": content, "useRag": use_rag},
        timeout=timeout,
    )
    resp.raise_for_status()
    reply = resp.json().get("reply", "").strip()
    return reply


# ---------------------------------------------------------------------------
# 消息处理
# ---------------------------------------------------------------------------
_SEEN_IDS = set()
_SEEN_HASHES = set()


def _is_group_chat(chat: str, sender: str) -> bool:
    """判断聊天对象是否为群聊。

    wxauto 3.9 的规则：
    - 私聊时 chat 名 == 发送者名；
    - 群聊时 chat 名带人数后缀（如 “工作群(123)” / “工作群（123）”）且发送者是群成员。
    """
    if re.search(r"[（(]\d+[)）]$", chat):
        return True
    if sender and chat and sender != chat:
        return True
    return False


def _msg_key(chat: str, msg_id: str, sender: str, content: str) -> str:
    """消息去重 key：优先用 wxauto 返回的消息 id，取不到再用内容哈希兜底。"""
    if msg_id:
        return f"{chat}|{msg_id}"
    h = hashlib.md5(f"{chat}|{sender}|{content}".encode("utf-8")).hexdigest()
    return f"hash|{h}"


def _is_duplicate(key: str) -> bool:
    if key in _SEEN_IDS:
        return True
    _SEEN_IDS.add(key)
    if len(_SEEN_IDS) > 5000:  # 防止内存无限增长，保留最近 5000 条
        _SEEN_IDS.clear()
    return False


def handle_message(wx, java_url: str, chat: str, sender: str, content: str,
                   msg_id: str, ai_timeout: int, cfg) -> None:
    """处理单条微信消息：调 Java/AI -> 回复。"""
    if chat in cfg["skip_chats"]:
        print(f"[SKIP] 会话 {chat} 在 skip_chats 配置中，跳过")
        return
    content = (content or "").strip()
    if not content:
        return

    own_nick = getattr(wx, "nickname", None)
    if sender and own_nick and sender == own_nick:
        print(f"[SKIP] 自己发的消息（{sender}），忽略避免死循环")
        return

    chat_type = "group" if _is_group_chat(chat, sender) else "friend"
    if not should_reply(cfg, chat, sender, chat_type):
        if chat_type == "group":
            print(f"[SKIP] 群消息（{chat}），未开启群回复或不在群白名单")
        else:
            print(f"[SKIP] 好友 {sender} 不在白名单，不回复")
        return

    key = _msg_key(chat, msg_id, sender, content)
    if _is_duplicate(key):
        print(f"[SKIP] 重复消息：{chat} <- {sender}：{content[:30]}")
        return

    print(f"[RECV] {chat} <- {sender}：{content}")
    try:
        reply = ask_ai(java_url, sender, content, ai_timeout, should_use_rag(cfg, sender))
    except Exception as e:
        print(f"[ERROR] 调用 Java/AI 失败：{e}")
        return

    if not reply:
        print("[ERROR] AI 返回内容为空，不发消息")
        return

    try:
        wx.SendMsg(reply, who=chat)
        print(f"[SEND] {chat} <- AI：{reply}")
    except Exception as e:
        print(f"[ERROR] 发送失败：{e}")
    time.sleep(1.0)  # 发送节流，降低风控风险


# ---------------------------------------------------------------------------
# 两种监听模式
# ---------------------------------------------------------------------------
def run_polling_loop(wx, args, cfg) -> None:
    """默认模式：轮询 GetNextNewMessage（自动发现所有未开启免打扰的新消息）。"""
    print(f"[INFO] 开始轮询新消息，间隔 {args.interval}s（Ctrl+C 退出）")
    while True:
        try:
            # 返回形如 {'张三': [<Message>, <Message>, ...]}，没有新消息时返回 {}
            new_msgs = wx.GetNextNewMessage()
            for chat, msg_list in new_msgs.items():
                for msg in msg_list:
                    # wxauto 3.9 消息对象：type / sender / content / id
                    # （个别版本返回 ['sender', 'content', 'id'] 列表，做兼容处理）
                    if hasattr(msg, "type"):
                        handle_message(
                            wx, args.java_url, chat,
                            getattr(msg, "sender", ""),
                            getattr(msg, "content", ""),
                            getattr(msg, "id", ""),
                            args.ai_timeout, cfg,
                        )
                    elif isinstance(msg, (list, tuple)) and len(msg) >= 3:
                        handle_message(
                            wx, args.java_url, chat,
                            str(msg[0]), str(msg[1]), str(msg[2]),
                            args.ai_timeout, cfg,
                        )
        except KeyboardInterrupt:
            print("\n[INFO] 已退出")
            break
        except Exception as e:
            print(f"[ERROR] 轮询异常：{e}")
        time.sleep(args.interval)


def run_listen_loop(wx, args, listen_names, cfg) -> None:
    """监听模式：AddListenChat 指定对象 + GetListenMessage 轮询。"""
    for name in listen_names:
        try:
            wx.AddListenChat(who=name)
            print(f"[INFO] 已添加监听：{name}")
        except Exception as e:
            print(f"[ERROR] 添加监听失败 {name}：{e}")

    print(f"[INFO] 开始监听指定对象，间隔 {args.interval}s（Ctrl+C 退出）")
    while True:
        try:
            # 返回 {'张三': [<Message>, ...], '李四': []}
            new_msgs = wx.GetListenMessage()
            for chat, msg_list in new_msgs.items():
                for msg in msg_list:
                    if not hasattr(msg, "type"):
                        continue
                    handle_message(
                        wx, args.java_url, chat,
                        getattr(msg, "sender", ""),
                        getattr(msg, "content", ""),
                        getattr(msg, "id", ""),
                        args.ai_timeout, cfg,
                    )
        except KeyboardInterrupt:
            print("\n[INFO] 已退出")
            break
        except Exception as e:
            print(f"[ERROR] 监听异常：{e}")
        time.sleep(args.interval)


# ---------------------------------------------------------------------------
def main():
    args = parse_args()

    if args.ping:
        sys.exit(0 if ping_java(args.java_url) else 1)

    cfg = apply_cli_overrides(load_config(args.config), args)
    print("[INFO] 回复范围："
          f"好友白名单={cfg['reply_friends'] or '全部'}，"
          f"群回复={'开' if cfg['reply_groups'] else '关'}"
          + (f"，群白名单={cfg['reply_group_names']}" if cfg['reply_group_names'] else ""))

    try:
        from wxauto import WeChat
    except ImportError:
        print("[FATAL] 未安装 wxauto 3.9，请先执行：")
        print("       pip install -r python/requirements.txt")
        print("       （wxauto 不在 PyPI，需从 GitHub 安装，见 requirements.txt 注释）")
        sys.exit(1)

    print("[INFO] 正在连接微信客户端（请确认微信 3.9.8.15 已打开并登录）……")
    try:
        wx = WeChat()
    except Exception as e:
        print(f"[FATAL] 初始化 wxauto 失败：{e}")
        print("       可能原因：微信版本不是 3.9.8.15、微信未登录、微信被最小化到托盘。")
        sys.exit(1)

    print(f"[INFO] 当前登录账号昵称：{getattr(wx, 'nickname', '未知')}")

    if not ping_java(args.java_url):
        print("[FATAL] Java 服务未启动，先运行 mvn spring-boot:run")
        sys.exit(1)

    if args.listen:
        run_listen_loop(wx, args, args.listen, cfg)
    else:
        run_polling_loop(wx, args, cfg)


if __name__ == "__main__":
    main()
