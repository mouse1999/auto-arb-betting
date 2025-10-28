package com.mouse.bet.interceptor;

import com.mouse.bet.model.profile.UserAgentProfile;
import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Request;

import java.io.IOException;
import java.util.Map;

@RequiredArgsConstructor
public class HeadersInterceptor implements Interceptor {

    private final UserAgentProfile userAgentProfile;
    private final Map<String, String> headers;
    private final String sportPage;
    private final String cookieHeader;

    @Override
    public okhttp3.Response intercept(Interceptor.Chain chain) throws IOException {
        Request original = chain.request();

        Request.Builder builder = original.newBuilder()
                .header("User-Agent", userAgentProfile != null ? userAgentProfile.getUserAgent() : "Mozilla/5.0")
                .header("Referer", sportPage)
                .header("Cookie", cookieHeader)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Encoding", "gzip")
                .header("Content-Type", "application/json")
                .header("Connection", "keep-alive")
                .header("X-Requested-With", "XMLHttpRequest");

        // Add harvested headers
        headers.forEach((key, value) -> {
            if (!key.equals("cookie") && !key.equals("user-agent")) {
                builder.header(key, value);
            }
        });

        return chain.proceed(builder.build());
    }



}
