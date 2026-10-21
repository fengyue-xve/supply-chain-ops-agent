package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.service.DemoResetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import java.util.Map;

@RestController
@Profile("demo")
public class DemoResetController {
    private final DemoResetService reset;
    private final RestClient forms;
    private final String resetKey;
    public DemoResetController(DemoResetService reset,
                              @Value("${app.forms-gateway.base-url:http://127.0.0.1:18183}") String baseUrl,
                              @Value("${FLOW_DEMO_RESET_KEY:}") String resetKey) {
        this.reset=reset; this.resetKey=resetKey;
        var uri=URI.create(baseUrl);
        boolean local="http".equals(uri.getScheme()) && java.util.Set.of("127.0.0.1","localhost","::1").contains(uri.getHost())
                && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        forms=local?RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build():null;
    }
    public record ResetRequest(boolean confirmed) {}
    @PostMapping("/api/demo/reset")
    public ApiResponse<Map<String,Object>> reset(@RequestBody ResetRequest request) {
        var actor=WriteHeaders.authenticatedUser();
        if (!"admin".equals(actor.role())) throw new ApiException(403,"只有admin可以重置合成演示");
        reset.reset(request.confirmed(),actor.username());
        boolean formsReset=false;
        if (forms!=null && !resetKey.isBlank()) {
            try {
                Map<?,?> receipt=forms.post().uri("/api/flow/demo/reset").header("X-Demo-Reset-Key",resetKey)
                        .body(Map.of("confirmed",true)).retrieve().body(Map.class);
                formsReset=receipt != null && Boolean.TRUE.equals(receipt.get("reset"));
            } catch (RuntimeException ignored) { /* MySQL already committed: retain a truthful partial result. */ }
        }
        return ApiResponse.ok(Map.of("operationsReset",true,"formsReset",formsReset,"auditPreserved",true,
                "message",formsReset?"运营和表单演示基线已恢复，审计保留。":"运营基线已恢复；表单重置未确认，请检查表单服务后重试。"));
    }
}
