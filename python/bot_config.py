# -*- coding: utf-8 -*-
"""
回复范围配置：读取 python/config.ini，决定机器人回复谁。

配置项（[reply] 段）：
    friends     好友白名单，逗号分隔；留空 = 回复所有私聊好友
    groups      是否回复群消息：true/false（默认 false）
    group_names 群白名单，逗号分隔；留空 = 回复所有群（仅 groups=true 时生效）
    skip_chats  跳过这些会话（不读取也不回复），逗号分隔；
                用于绕过“一读就卡死”的会话（如文件传输助手）

命令行参数优先级高于配置文件：
    --reply-friends 姐姐,张三   只回复这些好友
    --reply-groups              开启群消息回复
    --group-names 工作群,项目群  只回复这些群
"""

import configparser
from pathlib import Path

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent / "config.ini"


def default_config():
    return {
        "reply_friends": [],       # 好友白名单，空 = 全部私聊
        "reply_groups": False,     # 是否回复群消息
        "reply_group_names": [],   # 群白名单，空 = 全部群
        "skip_chats": [],          # 完全跳过的会话（读取/回复都不做）
    }


def _split_list(raw):
    return [x.strip() for x in (raw or "").split(",") if x.strip()]


def load_config(config_path=None):
    """读取 config.ini；文件不存在时返回默认配置（回复所有私聊、不回复群）。"""
    cfg = default_config()
    path = Path(config_path) if config_path else DEFAULT_CONFIG_PATH
    if path.exists():
        parser = configparser.ConfigParser()
        parser.read(path, encoding="utf-8")
        if parser.has_section("reply"):
            cfg["reply_friends"] = _split_list(parser.get("reply", "friends", fallback=""))
            cfg["reply_groups"] = parser.getboolean("reply", "groups", fallback=False)
            cfg["reply_group_names"] = _split_list(parser.get("reply", "group_names", fallback=""))
            cfg["skip_chats"] = _split_list(parser.get("reply", "skip_chats", fallback=""))
    return cfg


def apply_cli_overrides(cfg, args):
    """命令行参数覆盖配置文件（只覆盖显式给出的项）。"""
    if getattr(args, "reply_friends", None):
        cfg["reply_friends"] = _split_list(args.reply_friends)
    if getattr(args, "reply_groups", False):
        cfg["reply_groups"] = True
    if getattr(args, "group_names", None):
        cfg["reply_group_names"] = _split_list(args.group_names)
    if getattr(args, "skip_chats", None):
        cfg["skip_chats"] = _split_list(args.skip_chats)
    return cfg


def should_reply(cfg, chat, sender, chat_type):
    """根据配置判断是否对该消息自动回复。

    Args:
        chat:      会话名（私聊=好友名，群聊=群名）
        sender:    发送者名（群聊里是群成员名）
        chat_type: 'friend' 或 'group'
    """
    if chat_type == "group":
        if not cfg["reply_groups"]:
            return False
        if cfg["reply_group_names"] and chat not in cfg["reply_group_names"]:
            return False
        return True
    # 私聊：白名单非空时，只回复白名单里的好友
    if cfg["reply_friends"]:
        return sender in cfg["reply_friends"] or chat in cfg["reply_friends"]
    return True
