# -*- coding: utf-8 -*-
"""
微信 AI 自动回复机器人 —— Python 桥接端（wxauto4 免费版，微信 4.x 轮询方案）

背景：
    - wxautox4（Plus 付费版）有 GetNextNewMessage / AddListenChat 监听接口，但需要激活码；
    - 免费版 wxauto4 没有监听接口，但可以用 GetSession + ChatWith + GetAllMessage + SendMsg
      手工轮询：遍历会话列表，打开“有新消息”的聊天，读取消息，调 Java/AI 后回复。

架构：
    Python(wxauto4) 轮询会话
        -> POST http://127.0.0.1:8080/api/reply   （Java Spring Boot 调 DeepSeek 等远端大模型）
        -> wx.SendMsg(msg=reply, who=chat_name) 把 AI 回复发回微信

适用环境（重要！）：
    - Windows 10/11
    - 微信 PC 客户端 4.x（4.1 可用），无需降级
    - Python 3.9 ~ 3.12（免费版 wxauto4 只支持到 3.12）
    - 安装：pip install wxauto4
    - ⚠️ 免费版没有监听接口，本脚本是“轮询 + 逐会话打开”的折中方案：
      - 默认只检查【有未读角标的会话】+【当前打开的聊天】（脚本打开过的聊天，
        新消息不产生未读角标，只看角标会漏消息，所以当前聊天也要查）；
        可用 --scan-all 改为全量扫描（更慢、更吵，一般不需要）；
      - 首次遇到某个会话时只记录最后一条消息（不回复历史消息），之后只回复新增消息。
      它比 wxautox4 慢、UI 切换多，但零成本，可以先试试效果。

风险提示（务必阅读）：
    1. 个人微信自动化违反《微信软件许可及服务协议》，账号存在被限制登录、封禁的风险，
       请务必使用【测试小号】运行，不要使用主号！
    2. 自动回复有风控风险：请控制回复频率，避免高频收发。
    3. 聊天内容会发送到远端大模型服务（DeepSeek/OpenAI），注意保护隐私。
    4. 微信客户端必须保持打开、保持登录、窗口不要最小化到系统托盘。
"""

import argparse
import hashlib
import logging
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

# 关掉 wxauto4 自己的调试日志（默认 DEBUG 刷屏，还会拖慢消息解析）
for _name in ("wxauto4", "wxautox", "wxautox4"):
    logging.getLogger(_name).setLevel(logging.WARNING)


# ---------------------------------------------------------------------------
# 配置（优先级：命令行参数 > 环境变量 > 默认值）
# ---------------------------------------------------------------------------
DEFAULT_JAVA_URL = os.environ.get("BOT_JAVA_URL", "http://127.0.0.1:8080")
DEFAULT_INTERVAL = float(os.environ.get("BOT_POLL_INTERVAL", "2.0"))
DEFAULT_AI_TIMEOUT = int(os.environ.get("BOT_AI_TIMEOUT", "90"))


def parse_args():
    parser = argparse.ArgumentParser(
        description="微信 AI 自动回复机器人（wxauto4 免费版 / 微信 4.x 轮询方案）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python wechat_bridge_4x_free.py              # 默认轮询所有有新消息的会话\n"
            "  python wechat_bridge_4x_free.py --ping       # 只检查 Java 服务是否在线\n"
            "  python wechat_bridge_4x_free.py --reply-groups  # 群消息也回复\n"
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
    parser.add_argument("--scan-all", action="store_true",
                        help="每轮扫描全部会话（更慢、UI 切换更多，一般不需要）")
    parser.add_argument("--reply-unread", action="store_true",
                        help="启动时也回复尚未读过的消息（最多最近 5 条，防止启动前的消息无人回复）")
    parser.add_argument("--read-messages", action="store_true",
                        help="用旧模式：打开聊天窗口 + 读取完整消息（可能触发 wxauto4 解析卡死）；"
                             "默认使用预览模式（只看会话列表最新消息，更快更稳）")
    parser.add_argument("--debug", action="store_true",
                        help="打印每轮会话/消息的详细字段，方便排查问题")
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
def _session_fields(session):
    """尽量从 SessionElement 里取出 name / new_count / isnew / ismute。

    不同版本字段位置不一样：可能在 session.info 字典里，也可能直接在
    session 对象上（session.name / session.new_count / session.isnew）。
    """
    name, new_count, isnew, ismute = None, 0, False, False

    info = getattr(session, "info", None)
    if isinstance(info, dict):
        name = info.get("name") or name
        nc = info.get("new_count")
        if nc is not None:
            try:
                new_count = int(nc)
            except (TypeError, ValueError):
                pass
        isnew = bool(info.get("isnew", isnew))
        ismute = bool(info.get("ismute", ismute))

    name = getattr(session, "name", None) or name
    nc = getattr(session, "new_count", None)
    if nc is not None:
        try:
            new_count = int(nc)
        except (TypeError, ValueError):
            pass
    sn = getattr(session, "isnew", None)
    if sn is not None:
        isnew = bool(sn)
    sm = getattr(session, "ismute", None)
    if sm is not None:
        ismute = bool(sm)

    return name, new_count, isnew, ismute


def _msg_hash(chat: str, sender: str, content: str) -> str:
    """消息去重/游标 key：优先用 msg.hash，取不到再用内容哈希。"""
    h = hashlib.md5(f"{chat}|{sender}|{content}".encode("utf-8")).hexdigest()
    return f"{chat}|{h}"


def _chat_type_of(msg, chat: str, sender: str) -> str:
    """尽量从消息对象拿到 chat_type（friend/group），拿不到就用启发式。"""
    try:
        info = msg.chat_info() if callable(getattr(msg, "chat_info", None)) else getattr(msg, "chat_info", None)
        if isinstance(info, dict) and info.get("chat_type"):
            return info["chat_type"]
    except Exception:
        pass
    # 启发式：群聊里发送者是群成员名，通常不等于会话名；私聊两者一致
    if sender and chat and sender != chat:
        return "group"
    return "friend"


def handle_message(wx, java_url: str, chat: str, chat_type: str,
                   sender: str, content: str,
                   ai_timeout: int, cfg) -> str:
    """处理单条微信消息：调 Java/AI -> 回复。成功返回回复文本，失败/跳过返回 None。"""
    content = (content or "").strip()
    if not content:
        return None

    own_nick = getattr(wx, "nickname", None)
    if sender and own_nick and sender == own_nick:
        print(f"[SKIP] 自己发的消息（{sender}），忽略避免死循环")
        return None

    if not should_reply(cfg, chat, sender, chat_type):
        if chat_type == "group":
            print(f"[SKIP] 群消息（{chat}），未开启群回复或不在群白名单")
        else:
            print(f"[SKIP] 好友 {sender} 不在白名单，不回复")
        return None

    print(f"[RECV] {chat} <- {sender}：{content}")
    try:
        reply = ask_ai(java_url, sender, content, ai_timeout)
    except Exception as e:
        print(f"[ERROR] 调用 Java/AI 失败：{e}")
        return None

    if not reply:
        print("[ERROR] AI 返回内容为空，不发消息")
        return None

    try:
        wx.SendMsg(msg=reply, who=chat)
        print(f"[SEND] {chat} <- AI：{reply}")
        time.sleep(1.0)  # 发送节流，降低风控风险
        return reply
    except Exception as e:
        print(f"[ERROR] 发送失败：{e}")
        return None


# ---------------------------------------------------------------------------
# 主轮询循环
# ---------------------------------------------------------------------------
def run_loop(wx, args, cfg) -> None:
    # 每个会话记录“上一轮最后一条消息的游标”，首次遇到只建立游标、不回复历史
    last_cursor = {}
    seen_ids = set()

    print(f"[INFO] 开始轮询会话，间隔 {args.interval}s（Ctrl+C 退出）")
    print("[INFO] 默认只检查【有未读角标的会话】+【当前打开的聊天】；--scan-all 可改为全量扫描。")
    print("[INFO] 启动后首轮会建立消息游标：直接发新消息即可收到回复；"
          "启动前的旧消息默认不回，可用 --reply-unread 让它也回。")
    print("[INFO] 请确保微信主窗口已打开、停在【聊天】页面，不要最小化到系统托盘。")
    while True:
        try:
            if hasattr(wx, "SwitchToChat"):
                try:
                    wx.SwitchToChat()
                except Exception as e:
                    if args.debug:
                        print(f"[DEBUG] SwitchToChat 失败：{e}")
            sessions = wx.GetSession()
        except KeyboardInterrupt:
            print("\n[INFO] 已退出")
            break
        except Exception as e:
            print(f"[ERROR] 获取会话列表失败：{e}")
            print("       请检查：微信主窗口是否打开？是否停在聊天页面？是否被最小化到托盘？")
            time.sleep(args.interval)
            continue

        if args.debug:
            print(f"[DEBUG] GetSession 返回 {len(sessions)} 个会话")

        # 当前打开的聊天：即使没有未读角标也要检查
        # （脚本/用户打开过的聊天，新消息不会生成角标，只看角标会漏消息）
        open_chat = ""
        try:
            if hasattr(wx, "ChatInfo"):
                info = wx.ChatInfo()
                if isinstance(info, dict):
                    open_chat = info.get("chat_name") or ""
        except Exception:
            pass

        # 决定本轮要检查哪些会话：有角标 + 当前打开的聊天；--scan-all 则全部
        todo = []
        for session in sessions:
            try:
                chat, new_count, isnew, ismute = _session_fields(session)
                if not chat:
                    continue
                if chat in cfg["skip_chats"]:
                    if args.debug:
                        print(f"[DEBUG] 跳过会话（配置 skip_chats）：{chat}")
                    continue
                if args.scan_all or new_count > 0 or isnew or (open_chat and chat == open_chat):
                    todo.append(chat)
                    if args.debug:
                        print(f"[DEBUG] 检查会话：{chat!r}（new_count={new_count} isnew={isnew} ismute={ismute}）")
            except Exception:
                continue

        if args.debug:
            print(f"[DEBUG] 本轮检查 {len(todo)}/{len(sessions)} 个会话")

        current_chat = open_chat
        for chat in todo:
            try:
                if current_chat != chat:
                    if args.debug:
                        print(f"[DEBUG] 打开会话：{chat}")
                    wx.ChatWith(who=chat)
                    current_chat = chat
                    time.sleep(1.0)  # 等消息 UI 渲染完再读，避免 wxauto4 解析发送者时无限重试
                msgs = wx.GetAllMessage()
            except Exception as e:
                print(f"[ERROR] 打开/读取会话失败 {chat}：{e}")
                continue

            # 过滤出可处理的消息（朋友发的文本），按时间顺序
            candidates = []
            for m in msgs:
                attr = getattr(m, "attr", "")
                if attr == "self":
                    continue
                if getattr(m, "type", "") != "text":
                    continue
                sender = getattr(m, "sender", "") or ""
                content = getattr(m, "content", "") or ""
                mhash = getattr(m, "hash", "") or ""
                mid = getattr(m, "id", "") or ""
                if not content.strip():
                    continue
                key = _msg_hash(chat, sender, content)
                cursor = mhash or mid or key
                candidates.append((cursor, m, sender, content, mhash, mid))

            if not candidates:
                if args.debug:
                    print(f"[DEBUG] {chat}：没有可处理的文本消息（loaded={len(msgs)}）")
                continue

            # 首次遇到该会话：默认只记录游标、不回复历史消息；
            # --reply-unread 时额外处理最近几条未读，避免启动前的消息无人回复
            if chat not in last_cursor:
                last_cursor[chat] = candidates[-1][0]
                new_msgs = []
                if args.reply_unread:
                    print(f"[INFO] 首次监听 {chat}，按 --reply-unread 回复最近未读消息")
                    new_msgs = candidates[-6:-1]
                elif args.debug:
                    print(f"[DEBUG] 首次监听 {chat}，已建立游标，不回复历史消息")
            else:
                # 找出游标之后的新消息（candidates 按时间升序）
                new_msgs = []
                found = False
                for cursor, m, sender, content, mhash, mid in candidates:
                    if cursor == last_cursor[chat]:
                        found = True
                        continue
                    if found:
                        new_msgs.append((m, sender, content, mhash, mid))
                # 游标不在列表里（窗口滚动/消息被顶掉）：本轮保守处理，只补一条最新的
                if not found and candidates:
                    last_c, m, sender, content, mhash, mid = candidates[-1]
                    if last_c != last_cursor[chat] and last_c not in seen_ids:
                        new_msgs = [(m, sender, content, mhash, mid)]

            for m, sender, content, mhash, mid in new_msgs:
                seen_key = mhash or mid or _msg_hash(chat, sender, content)
                if seen_key in seen_ids:
                    continue
                seen_ids.add(seen_key)
                if args.debug:
                    print(f"[DEBUG] 处理消息 {chat} <- {sender}: {content[:20]}")
                chat_type = _chat_type_of(m, chat, sender)
                handle_message(
                    wx, args.java_url, chat, chat_type,
                    sender, content,
                    args.ai_timeout, cfg,
                )

            if candidates:
                last_cursor[chat] = candidates[-1][0]
            if len(seen_ids) > 5000:
                seen_ids.clear()

        time.sleep(args.interval)


# ---------------------------------------------------------------------------
def _session_preview(session):
    """从会话对象取“最后一条消息预览”文本（session.info['content'] 或 session.content）。"""
    info = getattr(session, "info", None)
    if isinstance(info, dict):
        content = info.get("content")
        if content:
            return str(content).strip()
    content = getattr(session, "content", None)
    return str(content).strip() if content else ""


def _looks_non_text(content):
    """会话预览里常见的非文本占位内容（图片/文件/红包等），不当作文本消息。"""
    markers = ("[图片]", "[文件]", "[语音]", "[视频]", "[表情]", "[动画表情]", "[红包]",
               "[位置]", "[名片]", "[链接]", "微信转账", "收到一个红包",
               "拍了拍我", "撤回了一条消息", "加入群聊", "退出群聊")
    return any(m in content for m in markers)


def run_preview_loop(wx, args, cfg) -> None:
    """预览轮询模式（默认）：只看会话列表的最新消息预览，不打开聊天窗口。

    优点：不调用 GetAllMessage，完全绕开 wxauto4 的“解析发送者无限重试”问题；
    速度快、窗口不来回切换。缺点：拿不到发送者（私聊发送者=会话名），
    且无法可靠区分群聊——群消息会按私聊处理，需用 skip_chats/白名单排除。
    """
    last_preview = {}   # chat -> 上次记录的预览哈希
    last_reply = {}     # chat -> 我们最后回复的文本（防止把自己发的回复当新消息）

    print(f"[INFO] 开始预览轮询，间隔 {args.interval}s（Ctrl+C 退出）")
    print("[INFO] 预览模式：只看会话列表最新消息，不打开聊天窗口，不会触发解析卡死。")
    print("[INFO] ⚠️ 预览模式无法区分群聊：群消息会按私聊处理，"
          "如不想回复群，请把群名加入 config.ini 的 skip_chats，或用白名单 friends 限制。")
    print("[INFO] 请确保微信主窗口已打开、停在【聊天】页面，不要最小化到系统托盘。")

    while True:
        try:
            if hasattr(wx, "SwitchToChat"):
                try:
                    wx.SwitchToChat()
                except Exception as e:
                    if args.debug:
                        print(f"[DEBUG] SwitchToChat 失败：{e}")
            sessions = wx.GetSession()
        except KeyboardInterrupt:
            print("\n[INFO] 已退出")
            break
        except Exception as e:
            print(f"[ERROR] 获取会话列表失败：{e}")
            time.sleep(args.interval)
            continue

        open_chat = ""
        try:
            if hasattr(wx, "ChatInfo"):
                info = wx.ChatInfo()
                if isinstance(info, dict):
                    open_chat = info.get("chat_name") or ""
        except Exception:
            pass

        if args.debug:
            print(f"[DEBUG] GetSession 返回 {len(sessions)} 个会话")

        for session in sessions:
            try:
                chat, new_count, isnew, ismute = _session_fields(session)
                if not chat or chat in cfg["skip_chats"]:
                    continue
                content = _session_preview(session)
                h = hashlib.md5(content.encode("utf-8")).hexdigest() if content else ""

                if args.debug:
                    print(f"[DEBUG] 会话 {chat!r} new_count={new_count} isnew={isnew} "
                          f"preview={content[:20]!r} changed={h != last_preview.get(chat)}")

                # 首次见到该会话：只记录基线，不处理历史消息
                if chat not in last_preview:
                    last_preview[chat] = h
                    continue

                # 预览没变：没有新消息
                if h == last_preview.get(chat):
                    continue

                # 预览变了但没有未读角标、也不是当前打开的聊天：
                # 多半是我们自己发送回复导致的预览变化，更新基线即可
                if not (new_count > 0 or isnew or (open_chat and chat == open_chat)):
                    last_preview[chat] = h
                    continue

                # 自己上次回复的内容回显，跳过（防止自回自答死循环）
                if content == last_reply.get(chat):
                    last_preview[chat] = h
                    continue

                if _looks_non_text(content):
                    last_preview[chat] = h
                    continue

                last_preview[chat] = h  # 先标记已处理，防止同一消息重复触发

                if not should_reply(cfg, chat, chat, "friend"):
                    print(f"[SKIP] 会话 {chat} 不在回复范围，不回复")
                    continue

                # 预览模式拿不到发送者：私聊的发送者=会话名
                reply = handle_message(wx, args.java_url, chat, "friend",
                                       chat, content, args.ai_timeout, cfg)
                if reply:
                    last_reply[chat] = reply
            except Exception:
                continue

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
        from wxauto4 import WeChat
    except ImportError:
        print("[FATAL] 未安装免费版 wxauto4，请先执行：")
        print("       pip install wxauto4")
        print("       （注意：只支持 Python 3.9 ~ 3.12）")
        sys.exit(1)

    print("[INFO] 正在连接微信客户端（请确认微信 4.x 已打开并登录）……")
    print("[INFO] 提示：如果卡在这一步，请关闭微信的【设置/登录/升级】窗口，")
    print("       确保微信主窗口已登录并显示聊天列表（不要最小化到托盘）。")
    try:
        wx = WeChat()
    except Exception as e:
        print(f"[FATAL] 初始化 wxauto4 失败：{e}")
        print("       可能原因：微信未登录、微信版本不受支持（免费版支持到 4.1.8.107）。")
        sys.exit(1)

    print(f"[INFO] 当前登录账号昵称：{getattr(wx, 'nickname', '未知')}")

    if not ping_java(args.java_url):
        print("[FATAL] Java 服务未启动，先运行 mvn spring-boot:run")
        sys.exit(1)

    if args.read_messages:
        run_loop(wx, args, cfg)
    else:
        run_preview_loop(wx, args, cfg)


if __name__ == "__main__":
    main()
