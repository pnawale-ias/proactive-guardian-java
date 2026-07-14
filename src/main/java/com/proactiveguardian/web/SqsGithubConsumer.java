package com.proactiveguardian.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import com.proactiveguardian.web.dto.SqsGithubEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.Set;

/**
 * Consumes GitHub PR events from an Amazon SQS queue pushed by an upstream
 * webhook relay service. Enable with {@code guardian.sqs-enabled=true}.
 *
 * <p>Uses SQS long-polling (20 s) so the {@code fixedDelay=100} scheduler
 * effectively blocks for up to 20 s per cycle when the queue is empty.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.sqs-enabled", havingValue = "true")
public class SqsGithubConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsGithubConsumer.class);
    private static final Set<String> PROCESSED_EVENT_TYPES = Set.of(
            "PULL_REQUEST_OPENED", "PULL_REQUEST_SYNCHRONIZE", "PULL_REQUEST_REOPENED");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GuardianProperties props;
    private final SqsClient sqsClient;
    private final PullRequestPipelineService pipeline;

    public SqsGithubConsumer(GuardianProperties props,
                             SqsClient sqsClient,
                             PullRequestPipelineService pipeline) {
        this.props = props;
        this.sqsClient = sqsClient;
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelay = 100)
    public void poll() {
        ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                .queueUrl(props.sqsQueueUrl())
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .visibilityTimeout(30)
                .build();

        List<Message> messages = sqsClient.receiveMessage(req).messages();
        for (Message msg : messages) {
            try {
                SqsGithubEventEnvelope env = MAPPER.readValue(msg.body(), SqsGithubEventEnvelope.class);

                if (!"GITHUB".equals(env.source())) {
                    log.debug("SQS message {} skipped: source={}", msg.messageId(), env.source());
                    deleteMessage(msg);
                    continue;
                }

                if (!PROCESSED_EVENT_TYPES.contains(env.eventType())) {
                    log.debug("SQS message {} skipped: eventType={}", msg.messageId(), env.eventType());
                    deleteMessage(msg);
                    continue;
                }

                GithubPullRequestEvent pr = env.rawPayload();
                if (pr == null) {
                    log.warn("SQS message {} has null rawPayload for eventType={} — skipping",
                            msg.messageId(), env.eventType());
                    deleteMessage(msg);
                    continue;
                }

                log.info("SQS PR event {} action={} repo={} pr#{}",
                        msg.messageId(), pr.action(),
                        pr.repository() != null ? pr.repository().fullName() : "?",
                        pr.pullRequest() != null ? pr.pullRequest().number() : "?");

                pipeline.process(pr);
                deleteMessage(msg);

            } catch (Exception e) {
                log.warn("Failed to process SQS message {}: {} — leaving on queue",
                        msg.messageId(), e.getMessage());
            }
        }
    }

    private void deleteMessage(Message msg) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(props.sqsQueueUrl())
                .receiptHandle(msg.receiptHandle())
                .build());
    }
}
