# -*- coding: utf-8 -*-
"""
微信 AI 自动回复机器人 —— Python 桥接端（wxautox4 Plus 版，微信 4.x）

架构：
    Python(wxautox4) 监听微信新消息
        -> POST http://127.0.0.1:8080/api/reply   （Java Spring Boot 调 DeepSeek 等远端大模型）
        -> wx.SendMsg(msg=reply, who=chat_name) 把 AI 回复发回微信

适用环境（重要！）：
    - Windows 10/11
    - 微信 PC 客户端 4.x（当前微信 4.1 即可，无需降级）
    - 需要安装并【激活】wxautox4（Plus 付费版）：
          pip install wxautox4
          wxautox4 auth activate 你的激活码
      ⚠️ 免费版 wxauto4 没有 GetNextNewMessage/AddListenChat 监听接口，
         做不了自动回复，请勿安装成 wxauto4。
    - Python 3.9 ~ 3.13（wxautox4 仅支持 Windows）

风险提示（务必阅读）：
    1. 个人微信自动化违反《微信软件许可及服务协议》，账号存在被限制登录、封禁的风险，
       请务必使用【测试小号】运行，不要使用主号！
    2. 自动回复有风控风险：请控制回复频率，避免高频收发。
    3. 聊天内容会发送到远端大模型服务（DeepSeek/OpenAI），注意保护隐私。
    4. 微信客户端必须保持打开、保持登录、窗口不要最小化到系统托盘（UI 自动化依赖）。
"""

import argparse
import hashlib
import os
import sys
import time

import requests

from bot_config import apply_cli_overrides, load_config, should_reply

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
        description="微信 AI 自动回复机器人（wxautox4 / 微信 4.x 桥接端）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python wechat_bridge_4x.py                # 默认轮询新消息\n"
            "  python wechat_bridge_4x.py --ping         # 只检查 Java 服务是否在线\n"
            "  python wechat_bridge_4x.py --reply-groups # 群消息也回复\n"
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
    parser.add_argument("--group-names", metavar="NAMES",
                        help="开启群回复后，只回复这些群（逗号分隔，覆盖 config.ini 的 group_names）")
    parser.add_argument("--skip-chats", metavar="NAMES",
                        help="跳过这些会话，不读取也不回复（逗号分隔，覆盖 config.ini 的 skip_chats）")
    parser.add_argument("--config", metavar="PATH",
                        help="配置文件路径（默认 python/config.ini）")
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


def ask_ai(java_url: str, sender: str, content: str, timeout: int) -> str:
    """把微信消息发给 Java 端，返回 AI 回复文本。"""
    resp = requests.post(
        f"{java_url}/api/reply",
        json={"sender": sender, "content": content},
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json().get("reply", "").strip()


# ---------------------------------------------------------------------------
# 消息处理
# ---------------------------------------------------------------------------
_SEEN_IDS = set()


def _msg_key(chat: str, msg_hash: str, msg_id: str, sender: str, content: str) -> str:
    """去重 key：wxautox4 的 msg.hash 切换 UI 后不变，优先使用；没有再用 id/内容哈希兜底。"""
    if msg_hash:
        return f"{chat}|hash|{msg_hash}"
    if msg_id:
        return f"{chat}|id|{msg_id}"
    h = hashlib.md5(f"{chat}|{sender}|{content}".encode("utf-8")).hexdigest()
    return f"{chat}|fallback|{h}"


def _is_duplicate(key: str) -> bool:
    if key in _SEEN_IDS:
        return True
    _SEEN_IDS.add(key)
    if len(_SEEN_IDS) > 5000:
        _SEEN_IDS.clear()
    return False


def handle_message(wx, java_url: str, chat: str, chat_type: str,
                   sender: str, content: str, msg_hash: str, msg_id: str,
                   ai_timeout: int, cfg) -> None:
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

    if not should_reply(cfg, chat, sender, chat_type):
        if chat_type == "group":
            print(f"[SKIP] 群消息（{chat}），未开启群回复或不在群白名单")
        else:
            print(f"[SKIP] 好友 {sender} 不在白名单，不回复")
        return

    key = _msg_key(chat, msg_hash, msg_id, sender, content)
    if _is_duplicate(key):
        print(f"[SKIP] 重复消息：{chat} <- {sender}：{content[:30]}")
        return

    print(f"[RECV] {chat} <- {sender}：{content}")
    try:
        reply = ask_ai(java_url, sender, content, ai_timeout)
    except Exception as e:
        print(f"[ERROR] 调用 Java/AI 失败：{e}")
        return

    if not reply:
        print("[ERROR] AI 返回内容为空，不发消息")
        return

    try:
        wx.SendMsg(msg=reply, who=chat)
        print(f"[SEND] {chat} <- AI：{reply}")
    except Exception as e:
        print(f"[ERROR] 发送失败：{e}")
    time.sleep(1.0)  # 发送节流，降低风控风险


def collect_message(chat_name, chat_type, sender, content, msg_hash, msg_id):
    """把回调中读到的消息字段暂存（不在回调里发送/回复，避免窗口重置）。"""
    return {
        "chat": chat_name,
        "chat_type": chat_type,
        "sender": sender,
        "content": content,
        "msg_hash": msg_hash,
        "msg_id": msg_id,
    }


def run_loop(wx, args, cfg) -> None:
    print(f"[INFO] 开始监听新消息，间隔 {args.interval}s（Ctrl+C 退出）")
    while True:
        pending = []

        def on_message(msg):
            # ⚠️ 回调里只能读取消息，不能发送/引用（会把窗口重置，导致后续消息获取中断）
            chat_info = msg.chat_info() if hasattr(msg, "chat_info") else {}
            chat_type = chat_info.get("chat_type", "") or ""
            chat_name = chat_info.get("chat_name", "") or ""
            attr = getattr(msg, "attr", "")
            if attr == "self":
                return
            if getattr(msg, "type", "") != "text":
                return
            pending.append(collect_message(
                chat_name, chat_type,
                getattr(msg, "sender", ""),
                getattr(msg, "content", ""),
                getattr(msg, "hash", ""),
                getattr(msg, "id", ""),
            ))

        try:
            result = wx.GetNextNewMessage(callback=on_message)
        except KeyboardInterrupt:
            print("\n[INFO] 已退出")
            break
        except Exception as e:
            print(f"[ERROR] 获取新消息异常：{e}")
            time.sleep(args.interval)
            continue

        # 兜底：万一回调没触发，尝试从返回值里取 msg 列表
        if not pending and isinstance(result, dict):
            chat_name = result.get("chat_name", "") or ""
            chat_type = result.get("chat_type", "") or ""
            for msg in result.get("msg", []) or []:
                attr = getattr(msg, "attr", "")
                if attr == "self" or getattr(msg, "type", "") != "text":
                    continue
                pending.append(collect_message(
                    chat_name, chat_type,
                    getattr(msg, "sender", ""),
                    getattr(msg, "content", ""),
                    getattr(msg, "hash", ""),
                    getattr(msg, "id", ""),
                ))

        for m in pending:
            handle_message(
                wx, args.java_url,
                m["chat"], m["chat_type"],
                m["sender"], m["content"],
                m["msg_hash"], m["msg_id"],
                args.ai_timeout, cfg,
            )
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
        from wxautox4 import WeChat
    except ImportError:
        print("[FATAL] 未安装 wxautox4（Plus 付费版），请先执行：")
        print("       pip install wxautox4")
        print("       wxautox4 auth activate 你的激活码")
        print("       ⚠️ 注意：不要装成免费版 wxauto4，它没有监听接口，无法自动回复。")
        sys.exit(1)

    print("[INFO] 正在连接微信客户端（请确认微信 4.x 已打开并登录）……")
    try:
        wx = WeChat()
    except Exception as e:
        print(f"[FATAL] 初始化 wxautox4 失败：{e}")
        print("       可能原因：微信未登录、版本不兼容、未激活授权。")
        sys.exit(1)

    if hasattr(wx, "IsOnline"):
        try:
            if not wx.IsOnline():
                print("[FATAL] 微信不在线，请先登录微信")
                sys.exit(1)
        except Exception:
            pass

    print(f"[INFO] 当前登录账号昵称：{getattr(wx, 'nickname', '未知')}")

    if not ping_java(args.java_url):
        print("[FATAL] Java 服务未启动，先运行 mvn spring-boot:run")
        sys.exit(1)

    run_loop(wx, args, cfg)


if __name__ == "__main__":
    main()
