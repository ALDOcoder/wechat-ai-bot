# -*- coding: utf-8 -*-
"""
wxauto4（免费版）诊断脚本：只读，不发消息。

用途：排查“脚本在跑但收不到消息”的问题——
  1. 打印 GetSession() 返回的每个会话的字段结构（name / new_count / isnew / info）；
  2. 可选 --chat 参数：打开指定会话，打印 GetAllMessage() 里消息对象的
     type / attr / sender / content / hash / id 字段，确认消息结构；
  3. 可选 --send-test 参数：给 --chat 指定的人发一条测试消息（默认不发）。

用法：
  python diagnose_wxauto4.py                 # 只 dump 会话列表
  python diagnose_wxauto4.py --chat 文件传输助手   # 再打开指定会话 dump 消息结构
  python diagnose_wxauto4.py --chat 文件传输助手 --send-test  # 顺便发条测试消息
"""

import argparse
import json
import sys

# 强制 stdout/stderr 行缓冲
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(line_buffering=True)


def dump_session(session, idx):
    print(f"\n===== Session[{idx}] =====")
    print(f"repr        : {session!r}")
    for attr in ("name", "new_count", "isnew", "ismute", "time", "content"):
        try:
            print(f"{attr:<12}: {getattr(session, attr)!r}")
        except Exception as e:
            print(f"{attr:<12}: <error {e}>")
    info = getattr(session, "info", None)
    print(f"info        : {info!r}")
    if isinstance(info, dict):
        try:
            print(f"info(json)  : {json.dumps(info, ensure_ascii=False, default=str)}")
        except Exception as e:
            print(f"info(json)  : <error {e}>")


def dump_messages(wx, chat):
    print(f"\n===== 打开会话：{chat} =====")
    try:
        wx.ChatWith(who=chat)
        print("ChatWith OK")
    except Exception as e:
        print(f"ChatWith FAILED: {e}")
        return
    try:
        msgs = wx.GetAllMessage()
        print(f"GetAllMessage 返回 {len(msgs)} 条")
    except Exception as e:
        print(f"GetAllMessage FAILED: {e}")
        return
    for i, m in enumerate(msgs[:30]):
        print(f"\n--- msg[{i}] ---")
        print(f"repr    : {m!r}")
        for attr in ("type", "attr", "sender", "content", "id", "hash", "direction"):
            try:
                print(f"{attr:<10}: {getattr(m, attr)!r}")
            except Exception as e:
                print(f"{attr:<10}: <error {e}>")


def main():
    parser = argparse.ArgumentParser(description="wxauto4 免费版诊断（只读为主）")
    parser.add_argument("--chat", help="指定要打开并 dump 消息结构的会话名")
    parser.add_argument("--send-test", action="store_true",
                        help="与 --chat 配合：给该会话发一条测试消息（默认不发）")
    args = parser.parse_args()

    try:
        from wxauto4 import WeChat
    except ImportError:
        print("[FATAL] 未安装 wxauto4，请先：pip install wxauto4")
        sys.exit(1)

    print("[INFO] 初始化 WeChat() ……")
    wx = WeChat()
    print(f"[INFO] nickname = {getattr(wx, 'nickname', '未知')!r}")
    if hasattr(wx, "IsOnline"):
        try:
            print(f"[INFO] IsOnline = {wx.IsOnline()}")
        except Exception as e:
            print(f"[INFO] IsOnline 调用失败：{e}")

    try:
        sessions = wx.GetSession()
    except Exception as e:
        print(f"[FATAL] GetSession() 失败：{e!r}")
        print("       常见原因：微信窗口未打开/被最小化/停在非聊天页面。")
        sys.exit(1)

    print(f"\n[INFO] GetSession() 返回 {len(sessions)} 个会话")
    for i, s in enumerate(sessions):
        dump_session(s, i)

    if args.chat:
        dump_messages(wx, args.chat)

    if args.send_test and args.chat:
        try:
            wx.SendMsg(msg="[诊断测试] wxauto4 链路正常 ✅", who=args.chat)
            print("\n[OK] 测试消息已发送")
        except Exception as e:
            print(f"\n[FAIL] 发送测试消息失败：{e!r}")

    print("\n[INFO] 诊断完成（未做任何自动回复）")


if __name__ == "__main__":
    main()
