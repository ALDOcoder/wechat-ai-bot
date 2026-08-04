package io.uouo.wechat.api.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * SyncKey
 *
 * @author biezhi
 * @since 2018/1/19
 */
@Data
public class SyncKey implements Serializable {

    @SerializedName("Count")
    private Integer count;

    @SerializedName("List")
    private List<KeyItem> list;

}
