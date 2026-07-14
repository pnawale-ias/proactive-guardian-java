package com.proactiveguardian.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

@Configuration
@ConditionalOnProperty(name = "guardian.sqs-enabled", havingValue = "true")
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(GuardianProperties props) {
        Region region = Region.of(props.sqsRegion());
        AwsCredentialsProvider creds;
        if (props.sqsRoleArn() != null && !props.sqsRoleArn().isBlank()) {
            StsClient sts = StsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            creds = StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(sts)
                    .refreshRequest(r -> r
                            .roleArn(props.sqsRoleArn())
                            .roleSessionName(props.sqsRoleSessionName()))
                    .build();
        } else {
            creds = DefaultCredentialsProvider.create();
        }
        return SqsClient.builder()
                .region(region)
                .credentialsProvider(creds)
                .build();
    }
}
