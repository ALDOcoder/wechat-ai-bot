package io.uouo.wechat.api.model;

import io.uouo.wechat.api.enums.RetCode;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 心跳检查返回
 *
 * @author biezhi
 * @since 2018/1/20
 */
@Data
@AllArgsConstructor
public class SyncCheckRet {

    private RetCode retCode;
    private int     selector;

}
