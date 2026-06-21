package com.flag.admin.repository;

import com.flag.admin.entity.FeatureFlagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlagEntity, Long> {

    List<FeatureFlagEntity> findByAppId(String appId);

    Optional<FeatureFlagEntity> findByAppIdAndFlagKey(String appId, String flagKey);

    List<FeatureFlagEntity> findByAppIdAndEnabledTrue(String appId);

    boolean existsByAppIdAndFlagKey(String appId, String flagKey);

    long deleteByAppId(String appId);
}