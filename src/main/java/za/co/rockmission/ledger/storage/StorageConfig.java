package za.co.rockmission.ledger.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Connects to the Railway Storage Bucket (S3-compatible). Skipped when no endpoint is configured. */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${ledger.storage.endpoint:}'.isBlank()")
    S3Client s3Client(@Value("${ledger.storage.endpoint}") String endpoint,
                      @Value("${ledger.storage.region:auto}") String region,
                      @Value("${ledger.storage.access-key-id:}") String accessKeyId,
                      @Value("${ledger.storage.secret-access-key:}") String secretAccessKey) {
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw new IllegalStateException("Set ACCESS_KEY_ID and SECRET_ACCESS_KEY when ENDPOINT is set.");
        }
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .build();
    }
}
