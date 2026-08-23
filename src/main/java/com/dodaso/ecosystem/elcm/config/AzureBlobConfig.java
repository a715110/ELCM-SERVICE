package com.dodaso.ecosystem.elcm.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureBlobConfig {
  @Bean
  public BlobServiceClient blobServiceClient(
      @Value("${azure.storage.account-name}") String accountName,
      @Value("${azure.storage.account-key}") String accountKey
  ) {
    StorageSharedKeyCredential credential =
        new StorageSharedKeyCredential(accountName, accountKey);

    String endpoint = "https://" + accountName + ".blob.core.windows.net";

    return new BlobServiceClientBuilder()
        .endpoint(endpoint)
        .credential(credential)
        .httpClient(new com.azure.core.http.jdk.httpclient.JdkHttpClientProvider()
            .createInstance())
        .buildClient();
  }
}