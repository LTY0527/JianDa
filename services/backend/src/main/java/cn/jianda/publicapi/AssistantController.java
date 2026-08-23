package cn.jianda.publicapi;

import cn.jianda.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/assistant")
public class AssistantController {
    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/suggestions")
    public ApiResponse<List<String>> suggestions() {
        return ApiResponse.ok(assistantService.suggestions());
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(assistantService.status());
    }

    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(assistantService.chat(
                request.message(), request.contextSlug(), request.regionCode()));
    }

    public record ChatRequest(
            @NotBlank(message = "请输入问题")
            @Size(max = 500, message = "问题不能超过500个字符")
            String message,
            String contextSlug,
            String regionCode) {}
}
