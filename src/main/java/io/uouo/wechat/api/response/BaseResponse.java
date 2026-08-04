package io.uouo.wechat.api.response;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 微信基础响应
 *
 * @author biezhi
 * @since 2018/1/19
 */
@Data
public class BaseResponse {

    @SerializedName("Ret")
    private Integer ret;

    @SerializedName("ErrMsg")
    private String msg;

}
