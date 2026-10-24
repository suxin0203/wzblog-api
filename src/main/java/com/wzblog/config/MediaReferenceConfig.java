package com.wzblog.config;

import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M5 未实现前的文件引用降级实现：不维护 ref_count，只记录 WARN。
 */
@Configuration
public class MediaReferenceConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaReferenceConfig.class);

    @Bean
    @ConditionalOnMissingBean(FileReferencePort.class)
    public FileReferencePort noopFileReferencePort() {
        return new FileReferencePort() {
            @Override
            public void claimByKey(String objectKey, Long ownerId, FileBizType bizType) {
                log.debug("file reference claim skipped (M5 not ready), key={}, ownerId={}, bizType={}",
                        objectKey, ownerId, bizType);
            }

            @Override
            public void releaseByKey(String objectKey) {
                log.debug("file reference release skipped (M5 not ready), key={}", objectKey);
            }
        };
    }
}