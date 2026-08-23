package com.dodaso.ecosystem.elcm.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

  @Bean
  public CaffeineCacheManager cacheManager() {
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
        .initialCapacity(100)
        .maximumSize(500)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .recordStats();
    CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager("eclm");
    caffeineCacheManager.setCaffeine(caffeine);
    return caffeineCacheManager;
  }
}