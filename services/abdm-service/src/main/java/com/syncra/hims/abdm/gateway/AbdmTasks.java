package com.syncra.hims.abdm.gateway;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs ABDM's asynchronous replies (on-notify, on-request, data push, on-share, linking)
 * once the transaction that recorded the triggering callback has committed, so ABDM
 * gets its 202 immediately and never hears about data that was rolled back.
 *
 * <p>Work that must survive a restart is also recorded in the database and picked up
 * again by the scheduled sweeps; this executor is only the fast path.
 */
@Component
public class AbdmTasks {

    private static final Logger log = LoggerFactory.getLogger(AbdmTasks.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs {@code task} after the current transaction commits (immediately if there is none). */
    public void afterCommit(String name, Runnable task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Runnable wrapped = () -> run(name, task, mdc);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(wrapped);
                }
            });
        } else {
            executor.execute(wrapped);
        }
    }

    private static void run(String name, Runnable task, Map<String, String> mdc) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            task.run();
        } catch (RuntimeException e) {
            // Messages here are ours or redacted ABDM text; never log payloads.
            log.warn("ABDM task '{}' failed: {}: {}", name, e.getClass().getSimpleName(), Redact.text(e.getMessage()));
        } finally {
            MDC.clear();
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
