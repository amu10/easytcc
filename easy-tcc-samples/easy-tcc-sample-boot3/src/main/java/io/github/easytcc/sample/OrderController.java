package io.github.easytcc.sample;

import java.util.Collections;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, String> create(
            @RequestParam(defaultValue = "u-1") String userId,
            @RequestParam(defaultValue = "sku-1") String sku,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(defaultValue = "100") int amount) {
        return Collections.singletonMap(
                "result", orderService.create(userId, sku, quantity, amount));
    }
}
