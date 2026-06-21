package com.flag.admin.service;

import com.flag.admin.entity.AppEntity;
import com.flag.admin.publisher.FlagChangePublisher;
import com.flag.admin.repository.AppRepository;
import com.flag.admin.repository.FeatureFlagRepository;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.exception.BusinessException;
import com.flag.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppService {

    private static final Logger log = LoggerFactory.getLogger(AppService.class);

    private final AppRepository appRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final FlagChangePublisher publisher;

    public AppService(AppRepository appRepository,
                      FeatureFlagRepository featureFlagRepository,
                      FlagChangePublisher publisher) {
        this.appRepository = appRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.publisher = publisher;
    }

    public List<AppEntity> listAll() {
        return appRepository.findAll();
    }

    public AppEntity getByAppId(String appId) {
        return appRepository.findByAppId(appId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_NOT_FOUND));
    }

    public AppEntity getById(Long id) {
        return appRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_NOT_FOUND));
    }

    @Transactional
    public AppEntity create(AppEntity app) {
        if (appRepository.existsByAppId(app.getAppId())) {
            throw new BusinessException(ErrorCode.APP_ID_CONFLICT);
        }
        AppEntity saved = appRepository.save(app);
        publisher.publishAppChange(saved.getAppId(), ChangeType.CREATE);
        log.info("App created: appId={}", saved.getAppId());
        return saved;
    }

    @Transactional
    public AppEntity update(String appId, AppEntity update) {
        AppEntity existing = getByAppId(appId);
        existing.setAppName(update.getAppName());
        existing.setDescription(update.getDescription());
        existing.setAppType(update.getAppType());
        existing.setEnabled(update.getEnabled());
        AppEntity saved = appRepository.save(existing);
        publisher.publishAppChange(appId, ChangeType.UPDATE);
        return saved;
    }

    @Transactional
    public void deleteByAppId(String appId) {
        AppEntity app = getByAppId(appId);
        // Manually cascade-delete all flags under this app before deleting the app
        // (no JPA cascade defined in entity)
        featureFlagRepository.deleteByAppId(appId);
        appRepository.delete(app);
        publisher.publishAppChange(appId, ChangeType.DELETE);
        log.info("App deleted (with cascade flags): appId={}", appId);
    }
}