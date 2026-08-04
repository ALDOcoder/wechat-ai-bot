
package io.uouo.wechat.api.request;

import io.uouo.wechat.api.constant.Constant;
import io.uouo.wechat.api.response.ApiResponse;
import lombok.Getter;
import okhttp3.Headers;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 基础请求
 *
 * @author biezhi
 */
@Getter
public abstract class ApiRequest<T extends ApiRequest, R extends ApiResponse> {

    protected int timeout = 10;
    protected boolean noRedirect;
    protected boolean jsonBody;
    protected boolean multipart;

    protected String url;
    protected String method = "GET";
    protected String fileName;
    protected String contentType = "application/x-www-form-urlencoded";
    protected Headers headers;
    @SuppressWarnings("unchecked")
    protected final T thisAsT = (T) this;
    private final Class<? extends R>  responseClass;
    private final Map<String, Object> parameters;
    private final String UOS_PATCH_EXTSPAM = "Gr8GCLkGErkGCggwMDAwMDAwMRAGGqAGzGkdmsypKcqwZ6eZkyquH8GMqK4TQ0wtkHFH1iVJhLeKKb5HLu+E25f/ivHzxf6H56j0as31NM0DmpH9hPxFvAIbtTdHmNj3J0QN4+bAQDn8PJmOoHHdfQrqGgbx8WA+Mt4JIop+q0dAiS7GOkFFMkOEeEXXgNkuAWMvX4N489/xiiO30il7RVwtINU4rgpyH7cVcf6KW1EB7yiDpHW18Q/wSyMPU7NATmSDePKDbvuMv8yVeXZEfb/K6QsYB+JvUdNl/99ODXXC20I+T34HHmW25aPK+XZ5nFlLorhN63zgoLyE3JWYZb0iDWjo7iYf2rsV5H6+SolqMDTy8AIzPju/gdGp4d9imRZS3m+Y09OfO8HCHso2dugPaqNiKatxU5w53AP857VVAEOZR0sEE+S9b6YZhE0zM01MGk7MZaNoEjEovoPA4VbvGSVM/g7t4ZbF07prmkNUi8K0IFlPktm9jj2eimC5rai8E4mfCBBHexzB5Dri19y+EUhQwYcOStBoWKmS8mNgWGKL+mFoyK/lw5+X3NLoO/0RoJjRFKxp3y2g4Ji7+vg0J7OhNlxbdxHwz76xXlT0WOpPFvP/yBN8cY7ohlo9UrBxsTx8D4ogYUud0/vvkHWOnJOoM1oe+PcwYVdopT1UpwyzS3BkS956ZZQTVfiYWSJ8N/x0JjymzAAm6tR2d0+ceH4Jy0GFoXXemWiZXMHylTnvLzF0Emw03NMnyJQ9tfAdjWogJ0eBK++1zMSrYR/9Qe9Sie+ENcESNvgEEdg/A1nH3pN7Aq8UPNzkG5ol0sUfQcck+zqgTcCAEVzeAwvcNv1PQSgpppjWQHBzwy8RwOzI5/I+9Tb17Os78yG6rrAN0utbsH8za8RMCSL2wChgZHth31Cdgi/5V++VTl/ikO3JMyaQYpox8EYoF94+CgkXjgoa2mz5sq4rQQFGpBg7tvR1ZhM30KACXl3QaydiIEKCXDLqarjNixqDwIwrEwB77yvz9CNA9JWr7U1tKlh94bbZUvDtFHTf03myUygi/UEODTZ3BQ28cnqN+pPMqTymGhzrvmwgrdjWhQYoBTAA";
    public ApiRequest(String url, Class<? extends R> responseClass) {
        this.url = url;
        this.responseClass = responseClass;
        this.parameters = new HashMap<>();
        this.headers = Headers.of(
                "User-Agent", Constant.USER_AGENT,
                "Content-Type", this.contentType,
                "client-version", "2.0.0",
                "extspam", UOS_PATCH_EXTSPAM,
                "referer", "https://wx.qq.com/?&lang=zh_CN&target=t"
        );
    }

    public T header(String name, String value) {
        this.headers = this.headers.newBuilder().set(name, value).build();
        return thisAsT;
    }

    public T add(String name, Object val) {
        parameters.put(name, val);
        return thisAsT;
    }

    public T noRedirect() {
        this.noRedirect = true;
        return thisAsT;
    }

    public T multipart() {
        this.multipart = true;
        return thisAsT;
    }

    public Type getResponseType() {
        return responseClass;
    }

    public T url(String url) {
        this.url = url;
        return thisAsT;
    }

    public T timeout(int seconds) {
        this.timeout = seconds;
        return thisAsT;
    }

    public T fileName(String fileName) {
        this.fileName = fileName;
        return thisAsT;
    }

    public T post() {
        this.method = "POST";
        return thisAsT;
    }

    public T jsonBody() {
        this.jsonBody = true;
        this.contentType = "application/json; charset=UTF-8";
        this.header("Content-Type", this.contentType);
        return thisAsT;
    }

}