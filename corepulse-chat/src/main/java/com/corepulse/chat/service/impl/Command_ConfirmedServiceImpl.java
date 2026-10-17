package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import com.corepulse.chat.service.ChatService;
import com.corepulse.chat.service.Command_ConfirmedService;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.enums.CommandStatus;
import com.corepulse.domain.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class Command_ConfirmedServiceImpl implements Command_ConfirmedService {

    private final ChatMessageMapper chatMessageMapper;

    private final ChatService chatService;

    /**
     * 用户确认命令 -> 置为 CONFIRMED -> 发起新一轮对话，由模型再次调用工具执行
     * <p>
     * 这里刻意不加 @Transactional：确认状态必须先独立提交（用户点了确认就是确认了），
     * 而后面要同步等待一轮完整的 LLM 调用（几十秒）。如果两者在同一个事务里，
     * 一旦模型调用失败回滚，用户的确认动作也会被一起回滚；同时长事务会长时间占用数据库连接。
     */
    @Override
    public String Command_Confirmed(String messageid) {
        Long id = parseId(messageid);
        if (id == null) {
            return "确认失败：消息 ID 非法";
        }

        ChatMessage message = chatMessageMapper.selectById(id);
        if (message == null) {
            return "确认失败：命令记录不存在";
        }
        if (!CommandStatus.PENDING.getValue().equals(message.getStatus())) {
            return "确认失败：该命令已处理过，请勿重复确认";
        }

        // 原子更新：只有仍处于 PENDING 的记录才能被确认，防止用户双击触发两轮执行
        int affected = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getConfirmed, 1)
                .set(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue())
                .eq(ChatMessage::getId, id)
                .eq(ChatMessage::getConfirmed, 0)
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue()));
        if (affected == 0) {
            return "确认失败：该命令已被处理";
        }
        log.info("命令确认成功: messageId={}, command={}", id, message.getContent());

        // 触发新一轮对话：模型拿到"用户已确认"这条用户消息，会原样再次调用 runShellCommand；
        // 工具侧会按 sessionId + 命令原文查数据库确认状态，匹配到才真正执行
        String confirmMessage = "用户已在界面上确认执行下面的命令，请立即调用 runShellCommand 执行它，"
                + "command 参数必须与下面完全一致，confirmed=true。命令：" + message.getContent();
        try {
            ChatSendVO vo = chatService.send(new ChatSendRequest(message.getSessionId(), confirmMessage, "command-confirm"));

            // 校验这一轮是否真的执行了：仍是 CONFIRMED 说明模型没有调用工具
            ChatMessage after = chatMessageMapper.selectById(id);
            if (after != null && CommandStatus.CONFIRMED.getValue().equals(after.getStatus())) {
                log.warn("命令确认后模型未调用工具执行: messageId={}", id);
                return "确认成功，但模型未执行该命令，请重新发送指令";
            }
            return vo != null && vo.getReply() != null ? vo.getReply() : "确认成功，命令已执行";
        } catch (Exception e) {
            log.error("命令确认后触发执行失败: messageId={}", id, e);
            return "确认成功，但执行流程异常，请查看会话记录";
        }
    }

    /** 消息 ID 是自增 Long，前端传字符串，先转换避免非法 ID 直接打到 SQL */
    private Long parseId(String messageid) {
        if (messageid == null || messageid.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(messageid.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
