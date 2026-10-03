package com.fungus.farm.controller;

import com.fungus.farm.common.ApiResponse;
import com.fungus.farm.domain.EnvironmentDisposalOrder;
import com.fungus.farm.dto.DisposalActionRequest;
import com.fungus.farm.service.EnvironmentDisposalService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/environment-disposals")
public class EnvironmentDisposalController {
    private final EnvironmentDisposalService service;

    public EnvironmentDisposalController(EnvironmentDisposalService service) {
        this.service = service;
    }

    /** 处置单列表，可按状态（待接单/处理中/恢复待确认/已解除）与菇房过滤。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long roomId) {
        return ApiResponse.success(service.orders(status, roomId));
    }

    /** 活动处置单简报：菇房卡片/批次列表据此保持状态一致。 */
    @GetMapping("/active")
    public ApiResponse<List<Map<String, Object>>> active() {
        return ApiResponse.success(service.activeSummaries());
    }

    @GetMapping("/{orderId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long orderId) {
        return ApiResponse.success(service.detail(orderId));
    }

    /** 值班人员接单（原子抢占，两人并发只有一人成功），可同时填预计完成时间与处理记录。 */
    @PostMapping("/{orderId}/accept")
    public ApiResponse<EnvironmentDisposalOrder> accept(@PathVariable Long orderId,
            @RequestBody DisposalActionRequest request) {
        return ApiResponse.success("接单成功", service.accept(orderId, request));
    }

    /** 处置进展：追加处理记录、调整预计完成时间（只增不改，全部进入时间线）。 */
    @PostMapping("/{orderId}/progress")
    public ApiResponse<EnvironmentDisposalOrder> progress(@PathVariable Long orderId,
            @RequestBody DisposalActionRequest request) {
        return ApiResponse.success("处理记录已保存", service.progress(orderId, request));
    }

    /** 恢复待确认后由值班人员确认解除，仅恢复本单暂停且当前仍为环境暂停的批次。 */
    @PostMapping("/{orderId}/resolve")
    public ApiResponse<EnvironmentDisposalOrder> resolve(@PathVariable Long orderId,
            @RequestBody DisposalActionRequest request) {
        return ApiResponse.success("处置单已解除，生产恢复", service.resolve(orderId, request));
    }
}
