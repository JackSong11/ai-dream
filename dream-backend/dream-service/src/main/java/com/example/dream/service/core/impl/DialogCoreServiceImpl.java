package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.DialogMapper;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.service.core.DialogCoreService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * {@link DialogCoreService} 实现。
 */
@Service
public class DialogCoreServiceImpl extends ServiceImpl<DialogMapper, ChatDialogPO>
        implements DialogCoreService {

    @Override
    public ChatDialogPO getOwnedValidDialog(Long dialogId, String userId) {
        if (dialogId == null || StringUtils.isBlank(userId)) {
            return null;
        }
        LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<ChatDialogPO>()
                .eq(ChatDialogPO::getId, dialogId)
                .eq(ChatDialogPO::getUserId, userId)
                .last("limit 1");
        return getOne(wrapper);
    }
}