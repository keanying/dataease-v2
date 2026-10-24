package io.dataease.ee.setting.apikey.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PerApiKeyRepository extends JpaRepository<PerApiKey, Long>, JpaSpecificationExecutor<PerApiKey> {

    List<PerApiKey> findByUidOrderByCreateTimeAsc(Long uid);

    long countByUid(Long uid);

    Optional<PerApiKey> findByAccessKey(String accessKey);
}
