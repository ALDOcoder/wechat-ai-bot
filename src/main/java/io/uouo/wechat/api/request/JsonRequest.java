package io.uouo.wechat.api.request;

import io.uouo.wechat.api.response.JsonResponse;

/**
 * JSON请求
 *
 * @author biezhi
 * @since 2018/1/18
 */
public class JsonRequest extends ApiRequest<JsonRequest, JsonResponse> {

    public JsonRequest(String url) {
        super(url, JsonResponse.class);
    }

}