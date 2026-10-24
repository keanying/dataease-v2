package io.dataease.ee.webhook.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface XpackWebhookRepository extends JpaRepository<XpackWebhook, Long>, JpaSpecificationExecutor<XpackWebhook> {
}
