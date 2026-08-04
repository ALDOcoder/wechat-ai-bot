package io.uouo.wechat.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * APP信息
 *
 * @author biezhi
 * @since 2018/1/20
 */
@Data
public class AppInfo {

    @SerializedName("AppID")
    private String appId;

    @SerializedName("Type")
    private Integer type;

}
