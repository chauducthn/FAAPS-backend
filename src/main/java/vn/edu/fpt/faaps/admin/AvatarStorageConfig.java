package vn.edu.fpt.faaps.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name = "faaps.avatar.enabled", havingValue = "true")
public class AvatarStorageConfig {
    @Bean
    public S3Client avatarS3Client(@Value("${faaps.avatar.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
