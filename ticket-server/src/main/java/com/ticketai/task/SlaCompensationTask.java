package com.ticketai.task;

import com.ticketai.service.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLA 补偿扫描（DEV_DOC §5.2.1）：每 5 分钟兜底超时未升级的工单（消息丢失场景）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaCompensationTask {

    private final SlaService slaService;

    @Scheduled(cron = "${app.sla.compensation-cron}")
    public void run() {
        log.info("SLA 补偿扫描开始");
        slaService.compensate();
        log.info("SLA 补偿扫描结束");
    }
}
