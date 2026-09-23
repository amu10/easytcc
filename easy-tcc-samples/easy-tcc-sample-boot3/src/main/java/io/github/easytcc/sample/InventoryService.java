package io.github.easytcc.sample;

import io.github.easytcc.annotation.EasyTccAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    @EasyTccAction(name = "reserve-inventory", confirm = "confirm", cancel = "cancel")
    public void reserve(String sku, int quantity) {
        log.info("TRY reserve inventory, sku={}, quantity={}", sku, quantity);
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    }

    public void confirm(String sku, int quantity) {
        log.info("CONFIRM inventory, sku={}, quantity={}", sku, quantity);
    }

    public void cancel(String sku, int quantity) {
        log.info("CANCEL inventory, sku={}, quantity={}", sku, quantity);
    }
}
