package com.flag.admin.repository;

import com.flag.admin.entity.AppEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppRepository extends JpaRepository<AppEntity, Long> {

    Optional<AppEntity> findByAppId(String appId);

    boolean existsByAppId(String appId);
}