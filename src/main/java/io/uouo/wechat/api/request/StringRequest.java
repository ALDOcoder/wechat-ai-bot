package io.uouo.wechat.api.request;

import io.uouo.wechat.api.response.ApiResponse;

/**
 * @author biezhi
 * @since 2018/1/18
 */
public class StringRequest extends ApiRequest<StringRequest, ApiResponse> {

    public StringRequest(String url) {
        super(url, ApiResponse.class);
    }

}