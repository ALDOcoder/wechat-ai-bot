package com.example.wechataibot.rag;

/**
 * 一条笔记块：来源文件 + 所在标题 + 正文。
 *
 * @param sourcePath 相对库根目录的文件路径
 * @param heading    所在章节标题（无标题时为空）
 * @param text       正文内容
 */
public record NoteChunk(String sourcePath, String heading, String text) {
}
