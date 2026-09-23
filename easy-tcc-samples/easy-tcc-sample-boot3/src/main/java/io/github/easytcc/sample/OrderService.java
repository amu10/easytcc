package io.github.easytcc.sample;

import io.github.easytcc.annotation.EasyTccTransactional;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final InventoryService inventoryService;
    private final AccountService accountService;

    public OrderService(InventoryService inventoryService, AccountService accountService) {
        this.inventoryService = inventoryService;
        this.accountService = accountService;
    }

    @EasyTccTransactional(name = "create-order", timeout = 30000)
    public String create(String userId, String sku, int quantity, int amount) {
        inventoryService.reserve(sku, quantity);
        accountService.freeze(userId, amount);
        return "order-created";
    }
}
