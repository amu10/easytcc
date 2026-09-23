package io.github.easytcc.sample;

import io.github.easytcc.annotation.EasyTccAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    @EasyTccAction(name = "freeze-account", confirm = "confirm", cancel = "cancel")
    public void freeze(String userId, int amount) {
        log.info("TRY freeze account, userId={}, amount={}", userId, amount);
        if (amount > 10000) throw new IllegalStateException("insufficient balance");
    }

    public void confirm(String userId, int amount) {
        log.info("CONFIRM account, userId={}, amount={}", userId, amount);
    }

    public void cancel(String userId, int amount) {
        log.info("CANCEL account, userId={}, amount={}", userId, amount);
    }
}
