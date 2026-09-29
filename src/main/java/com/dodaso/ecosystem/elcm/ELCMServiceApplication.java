package com.dodaso.ecosystem.elcm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = {"com.dodaso.ecosystem"})
@EnableCaching
public class ELCMServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ELCMServiceApplication.class, args);
  }

  // @Bean
  // SpringLiquibase liquibase(DataSource dataSource) {
  //   SpringLiquibase liquibase = new SpringLiquibase();
  //   liquibase.setDataSource(dataSource);
  //   liquibase.setChangeLog("classpath:liquibase-changelog.xml");
  //   liquibase.setContexts("development, production");
  //   liquibase.setShouldRun(false);
  //   return liquibase;
  // }

//	@PostConstruct
//	public void init() {
//		TimeZone.setDefault(TimeZone.getTimeZone("GMT")); // It will set UTC timezone
//	}
}