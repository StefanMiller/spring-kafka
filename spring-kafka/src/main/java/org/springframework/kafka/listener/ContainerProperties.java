/*
 * Copyright 2016-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.aopalliance.aop.Advice;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.task.AsyncListenableTaskExecutor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * Contains runtime properties for a listener container.
 *
 * @author Gary Russell
 * @author Artem Bilan
 * @author Artem Yakshin
 * @author Johnny Lim
 * @author Lukasz Kaminski
 */
public class ContainerProperties extends ConsumerProperties {

	/**
	 * The offset commit behavior enumeration.
	 */
	public enum AckMode {

		/**
		 * Commit after each record is processed by the listener.
		 */
		RECORD,

		/**
		 * Commit whatever has already been processed before the next poll.
		 */
		BATCH,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckTime(long) ackTime} has elapsed.
		 */
		TIME,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckCount(int) ackCount} has been
		 * exceeded.
		 */
		COUNT,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckCount(int) ackCount} has been
		 * exceeded or after {@link ContainerProperties#setAckTime(long)
		 * ackTime} has elapsed.
		 */
		COUNT_TIME,

		/**
		 * User takes responsibility for acks using an
		 * {@link AcknowledgingMessageListener}.
		 */
		MANUAL,

		/**
		 * User takes responsibility for acks using an
		 * {@link AcknowledgingMessageListener}. The consumer
		 * immediately processes the commit.
		 */
		MANUAL_IMMEDIATE,

	}

	/**
	 * Offset commit behavior during assignment.
	 * @since 2.3.6
	 */
	public enum AssignmentCommitOption {

		/**
		 * Always commit the current offset during partition assignment.
		 */
		ALWAYS,

		/**
		 * Never commit the current offset during partition assignment.
		 */
		NEVER,

		/**
		 * Commit the current offset during partition assignment when auto.offset.reset is
		 * 'latest'; transactional if so configured.
		 */
		LATEST_ONLY,

		/**
		 * Commit the current offset during partition assignment when auto.offset.reset is
		 * 'latest'; use consumer commit even when transactions are being used.
		 */
		LATEST_ONLY_NO_TX

	}

	/**
	 * Mode for exactly once semantics.
	 *
	 * @since 2.5
	 */
	public enum EOSMode {

		/**
		 * 'transactional.id' fencing (0.11 - 2.4 brokers).
		 */
		ALPHA,

		/**
		 *  fetch-offset-request fencing (2.5+ brokers).
		 */
		BETA,

	}

	/**
	 * The default {@link #setShutdownTimeout(long) shutDownTimeout} (ms).
	 */
	public static final long DEFAULT_SHUTDOWN_TIMEOUT = 10_000L;

	/**
	 * The default {@link #setMonitorInterval(int) monitorInterval} (s).
	 */
	public static final int DEFAULT_MONITOR_INTERVAL = 30;

	/**
	 * The default {@link #setNoPollThreshold(float) noPollThreshold}.
	 */
	public static final float DEFAULT_NO_POLL_THRESHOLD = 3f;

	private static final Duration DEFAULT_CONSUMER_START_TIMEOUT = Duration.ofSeconds(30);

	private static final int DEFAULT_ACK_TIME = 5000;

	private final Map<String, String> micrometerTags = new HashMap<>();

	private final List<Advice> adviceChain = new ArrayList<>();

	/**
	 * The ack mode to use when auto ack (in the configuration properties) is false.
	 * <ul>
	 * <li>RECORD: Ack after each record has been passed to the listener.</li>
	 * <li>BATCH: Ack after each batch of records received from the consumer has been
	 * passed to the listener</li>
	 * <li>TIME: Ack after this number of milliseconds; (should be greater than
	 * {@code #setPollTimeout(long) pollTimeout}.</li>
	 * <li>COUNT: Ack after at least this number of records have been received</li>
	 * <li>MANUAL: Listener is responsible for acking - use a
	 * {@link org.springframework.kafka.listener.AcknowledgingMessageListener}.
	 * </ul>
	 */
	private AckMode ackMode = AckMode.BATCH;

	/**
	 * The number of outstanding record count after which offsets should be
	 * committed when {@link AckMode#COUNT} or {@link AckMode#COUNT_TIME} is being
	 * used.
	 */
	private int ackCount = 1;

	/**
	 * The time (ms) after which outstanding offsets should be committed when
	 * {@link AckMode#TIME} or {@link AckMode#COUNT_TIME} is being used. Should be
	 * larger than
	 */
	private long ackTime = DEFAULT_ACK_TIME;

	/**
	 * The message listener; must be a {@link org.springframework.kafka.listener.MessageListener}
	 * or {@link org.springframework.kafka.listener.AcknowledgingMessageListener}.
	 */
	private Object messageListener;

	/**
	 * The executor for threads that poll the consumer.
	 */
	private AsyncListenableTaskExecutor consumerTaskExecutor;

	/**
	 * The timeout for shutting down the container. This is the maximum amount of
	 * time that the invocation to {@code #stop(Runnable)} will block for, before
	 * returning.
	 */
	private long shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

	private boolean ackOnError = false;

	private Long idleEventInterval;

	private PlatformTransactionManager transactionManager;

	private int monitorInterval = DEFAULT_MONITOR_INTERVAL;

	private TaskScheduler scheduler;

	private float noPollThreshold = DEFAULT_NO_POLL_THRESHOLD;

	private boolean logContainerConfig;

	private boolean missingTopicsFatal = false;

	private long idleBetweenPolls;

	private boolean micrometerEnabled = true;

	private Duration consumerStartTimout = DEFAULT_CONSUMER_START_TIMEOUT;

	private Boolean subBatchPerPartition;

	private AssignmentCommitOption assignmentCommitOption = AssignmentCommitOption.LATEST_ONLY_NO_TX;

	private boolean deliveryAttemptHeader;

	private EOSMode eosMode = EOSMode.BETA;

	private TransactionDefinition transactionDefinition;

	private boolean stopContainerWhenFenced;

	/**
	 * Create properties for a container that will subscribe to the specified topics.
	 * @param topics the topics.
	 */
	public ContainerProperties(String... topics) {
		super(topics);
	}

	/**
	 * Create properties for a container that will subscribe to topics matching the
	 * specified pattern. The framework will create a container that subscribes to all
	 * topics matching the specified pattern to get dynamically assigned partitions. The
	 * pattern matching will be performed periodically against topics existing at the time
	 * of check.
	 * @param topicPattern the pattern.
	 * @see org.apache.kafka.clients.CommonClientConfigs#METADATA_MAX_AGE_CONFIG
	 */
	public ContainerProperties(Pattern topicPattern) {
		super(topicPattern);
	}

	/**
	 * Create properties for a container that will assign itself the provided topic
	 * partitions.
	 * @param topicPartitions the topic partitions.
	 */
	public ContainerProperties(TopicPartitionOffset... topicPartitions) {
		super(topicPartitions);
	}

	/**
	 * Set the message listener; must be a {@link org.springframework.kafka.listener.MessageListener}
	 * or {@link org.springframework.kafka.listener.AcknowledgingMessageListener}.
	 * @param messageListener the listener.
	 */
	public void setMessageListener(Object messageListener) {
		this.messageListener = messageListener;
		adviseListenerIfNeeded();
	}

	/**
	 * Set the ack mode to use when auto ack (in the configuration properties) is false.
	 * <ul>
	 * <li>RECORD: Ack after each record has been passed to the listener.</li>
	 * <li>BATCH: Ack after each batch of records received from the consumer has been
	 * passed to the listener</li>
	 * <li>TIME: Ack after this number of milliseconds; (should be greater than
	 * {@code #setPollTimeout(long) pollTimeout}.</li>
	 * <li>COUNT: Ack after at least this number of records have been received</li>
	 * <li>MANUAL: Listener is responsible for acking - use a
	 * {@link org.springframework.kafka.listener.AcknowledgingMessageListener}.
	 * </ul>
	 * Ignored when transactions are being used. Transactional consumers commit offsets
	 * with semantics equivalent to {@code RECORD} or {@code BATCH}, depending on
	 * the listener type.
	 * @param ackMode the {@link AckMode}; default BATCH.
	 * @see #setTransactionManager(PlatformTransactionManager)
	 */
	public void setAckMode(AckMode ackMode) {
		Assert.notNull(ackMode, "'ackMode' cannot be null");
		this.ackMode = ackMode;
	}

	/**
	 * Set the number of outstanding record count after which offsets should be
	 * committed when {@link AckMode#COUNT} or {@link AckMode#COUNT_TIME} is being used.
	 * @param count the count
	 */
	public void setAckCount(int count) {
		Assert.state(count > 0, "'ackCount' must be > 0");
		this.ackCount = count;
	}

	/**
	 * Set the time (ms) after which outstanding offsets should be committed when
	 * {@link AckMode#TIME} or {@link AckMode#COUNT_TIME} is being used. Should be
	 * larger than
	 * @param ackTime the time
	 */
	public void setAckTime(long ackTime) {
		Assert.state(ackTime > 0, "'ackTime' must be > 0");
		this.ackTime = ackTime;
	}

	/**
	 * Set the executor for threads that poll the consumer.
	 * @param consumerTaskExecutor the executor
	 */
	public void setConsumerTaskExecutor(AsyncListenableTaskExecutor consumerTaskExecutor) {
		this.consumerTaskExecutor = consumerTaskExecutor;
	}

	/**
	 * Set the timeout for shutting down the container. This is the maximum amount of
	 * time that the invocation to {@code #stop(Runnable)} will block for, before
	 * returning; default {@value #DEFAULT_SHUTDOWN_TIMEOUT}.
	 * @param shutdownTimeout the shutdown timeout.
	 */
	public void setShutdownTimeout(long shutdownTimeout) {
		this.shutdownTimeout = shutdownTimeout;
	}

	/**
	 * Set the timeout for commitSync operations (if {@link #isSyncCommits()}. Overrides
	 * the default api timeout property. In order of precedence:
	 * <ul>
	 * <li>this property</li>
	 * <li>{@code ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG} in
	 * {@link #setKafkaConsumerProperties(Properties)}</li>
	 * <li>{@code ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG} in the consumer factory
	 * properties</li>
	 * <li>60 seconds</li>
	 * </ul>
	 * @param syncCommitTimeout the timeout.
	 * @see #setSyncCommits(boolean)
	 */
	@Override
	public void setSyncCommitTimeout(@Nullable Duration syncCommitTimeout) { // NOSONAR - not useless; enhanced javadoc
		super.setSyncCommitTimeout(syncCommitTimeout);
	}

	/**
	 * Set the idle event interval; when set, an event is emitted if a poll returns
	 * no records and this interval has elapsed since a record was returned.
	 * @param idleEventInterval the interval.
	 */
	public void setIdleEventInterval(Long idleEventInterval) {
		this.idleEventInterval = idleEventInterval;
	}

	/**
	 * Set whether or not the container should commit offsets (ack messages) where the
	 * listener throws exceptions. This works in conjunction with {@link #ackMode} and is
	 * effective only when the kafka property {@code enable.auto.commit} is {@code false};
	 * it is not applicable to manual ack modes. When this property is set to
	 * {@code true}, all messages handled will have their offset committed. When set to
	 * {@code false} (the default), offsets will be committed only for successfully
	 * handled messages. Manual acks will always be applied. Bear in mind that, if the
	 * next message is successfully handled, its offset will be committed, effectively
	 * committing the offset of the failed message anyway, so this option has limited
	 * applicability, unless you are using a {@code SeekToCurrentBatchErrorHandler} which
	 * will seek the current record so that it is reprocessed.
	 * <p>
	 * Does not apply when transactions are used - in that case, whether or not the
	 * offsets are sent to the transaction depends on whether the transaction is committed
	 * or rolled back. If a listener throws an exception, the transaction will normally be
	 * rolled back unless an error handler is provided that handles the error and exits
	 * normally; in which case the offsets are sent to the transaction and the transaction
	 * is committed.
	 * @deprecated in favor of {@code GenericErrorHandler.isAckAfterHandle()}.
	 * @param ackOnError whether the container should acknowledge messages that throw
	 * exceptions.
	 */
	@Deprecated
	public void setAckOnError(boolean ackOnError) {
		this.ackOnError = ackOnError;
	}

	public AckMode getAckMode() {
		return this.ackMode;
	}

	public int getAckCount() {
		return this.ackCount;
	}

	public long getAckTime() {
		return this.ackTime;
	}

	public Object getMessageListener() {
		return this.messageListener;
	}

	public AsyncListenableTaskExecutor getConsumerTaskExecutor() {
		return this.consumerTaskExecutor;
	}

	public long getShutdownTimeout() {
		return this.shutdownTimeout;
	}

	public Long getIdleEventInterval() {
		return this.idleEventInterval;
	}

	public boolean isAckOnError() {
		return this.ackOnError &&
				!(AckMode.MANUAL_IMMEDIATE.equals(this.ackMode) || AckMode.MANUAL.equals(this.ackMode));
	}

	public PlatformTransactionManager getTransactionManager() {
		return this.transactionManager;
	}

	/**
	 * Set the transaction manager to start a transaction; offsets are committed with
	 * semantics equivalent to {@link AckMode#RECORD} and {@link AckMode#BATCH} depending
	 * on the listener type (record or batch).
	 * @param transactionManager the transaction manager.
	 * @since 1.3
	 * @see #setAckMode(AckMode)
	 */
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public int getMonitorInterval() {
		return this.monitorInterval;
	}

	/**
	 * The interval between checks for a non-responsive consumer in
	 * seconds; default {@value #DEFAULT_MONITOR_INTERVAL}.
	 * @param monitorInterval the interval.
	 * @since 1.3.1
	 */
	public void setMonitorInterval(int monitorInterval) {
		this.monitorInterval = monitorInterval;
	}

	public TaskScheduler getScheduler() {
		return this.scheduler;
	}

	/**
	 * A scheduler used with the monitor interval.
	 * @param scheduler the scheduler.
	 * @since 1.3.1
	 * @see #setMonitorInterval(int)
	 */
	public void setScheduler(TaskScheduler scheduler) {
		this.scheduler = scheduler;
	}

	public float getNoPollThreshold() {
		return this.noPollThreshold;
	}

	/**
	 * If the time since the last poll / {@link #getPollTimeout() poll timeout}
	 * exceeds this value, a NonResponsiveConsumerEvent is published.
	 * This value should be more than 1.0 to avoid a race condition that can cause
	 * spurious events to be published.
	 * Default {@value #DEFAULT_NO_POLL_THRESHOLD}.
	 * @param noPollThreshold the threshold
	 * @since 1.3.1
	 */
	public void setNoPollThreshold(float noPollThreshold) {
		this.noPollThreshold = noPollThreshold;
	}

	/**
	 * Log the container configuration if true (INFO).
	 * @return true to log.
	 * @since 2.1.1
	 */
	public boolean isLogContainerConfig() {
		return this.logContainerConfig;
	}

	/**
	 * Set to true to instruct each container to log this configuration.
	 * @param logContainerConfig true to log.
	 * @since 2.1.1
	 */
	public void setLogContainerConfig(boolean logContainerConfig) {
		this.logContainerConfig = logContainerConfig;
	}

	/**
	 * If true, the container won't start if any of the configured topics are not present
	 * on the broker. Does not apply when topic patterns are configured. Default false.
	 * @return the missingTopicsFatal.
	 * @since 2.2
	 */
	public boolean isMissingTopicsFatal() {
		return this.missingTopicsFatal;
	}

	/**
	 * Set to false to allow the container to start even if any of the configured topics
	 * are not present on the broker. Does not apply when topic patterns are configured.
	 * Default true;
	 * @param missingTopicsFatal the missingTopicsFatal.
	 * @since 2.2
	 */
	public void setMissingTopicsFatal(boolean missingTopicsFatal) {
		this.missingTopicsFatal = missingTopicsFatal;
	}

	/**
	 * The sleep interval in milliseconds used in the main loop between
	 * {@link org.apache.kafka.clients.consumer.Consumer#poll(Duration)} calls.
	 * Defaults to {@code 0} - no idling.
	 * @param idleBetweenPolls the interval to sleep between polling cycles.
	 * @since 2.3
	 */
	public void setIdleBetweenPolls(long idleBetweenPolls) {
		this.idleBetweenPolls = idleBetweenPolls;
	}

	public long getIdleBetweenPolls() {
		return this.idleBetweenPolls;
	}

	public boolean isMicrometerEnabled() {
		return this.micrometerEnabled;
	}

	/**
	 * Set to false to disable the Micrometer listener timers. Default true.
	 * @param micrometerEnabled false to disable.
	 * @since 2.3
	 */
	public void setMicrometerEnabled(boolean micrometerEnabled) {
		this.micrometerEnabled = micrometerEnabled;
	}

	/**
	 * Set additional tags for the Micrometer listener timers.
	 * @param tags the tags.
	 * @since 2.3
	 */
	public void setMicrometerTags(Map<String, String> tags) {
		if (tags != null) {
			this.micrometerTags.putAll(tags);
		}
	}

	public Map<String, String> getMicrometerTags() {
		return Collections.unmodifiableMap(this.micrometerTags);
	}

	public Duration getConsumerStartTimout() {
		return this.consumerStartTimout;
	}

	/**
	 * Set the timeout to wait for a consumer thread to start before logging
	 * an error. Default 30 seconds.
	 * @param consumerStartTimout the consumer start timeout.
	 */
	public void setConsumerStartTimout(Duration consumerStartTimout) {
		Assert.notNull(consumerStartTimout, "'consumerStartTimout' cannot be null");
		this.consumerStartTimout = consumerStartTimout;
	}

	/**
	 * Return whether to split batches by partition.
	 * @return subBatchPerPartition.
	 * @since 2.3.2
	 */
	public boolean isSubBatchPerPartition() {
		return this.subBatchPerPartition == null ? false : this.subBatchPerPartition;
	}

	/**
	 * Return whether to split batches by partition; null if not set.
	 * @return subBatchPerPartition.
	 * @since 2.5
	 */
	@Nullable
	public Boolean getSubBatchPerPartition() {
		return this.subBatchPerPartition;
	}

	/**
	 * When using a batch message listener whether to dispatch records by partition (with
	 * a transaction for each sub batch if transactions are in use) or the complete batch
	 * received by the {@code poll()}. Useful when using transactions to enable zombie
	 * fencing, by using a {@code transactional.id} that is unique for each
	 * group/topic/partition. Defaults to true when using transactions with
	 * {@link #setEosMode(EOSMode) EOSMode.ALPHA} and false when not using transactions or
	 * with {@link #setEosMode(EOSMode) EOSMode.BETA}.
	 * @param subBatchPerPartition true for a separate transaction for each partition.
	 * @since 2.3.2
	 */
	public void setSubBatchPerPartition(boolean subBatchPerPartition) {
		this.subBatchPerPartition = subBatchPerPartition;
	}

	public AssignmentCommitOption getAssignmentCommitOption() {
		return this.assignmentCommitOption;
	}

	/**
	 * Set the assignment commit option. Default
	 * {@link AssignmentCommitOption#LATEST_ONLY_NO_TX}.
	 * @param assignmentCommitOption the option.
	 * @since 2.3.6
	 */
	public void setAssignmentCommitOption(AssignmentCommitOption assignmentCommitOption) {
		Assert.notNull(assignmentCommitOption, "'assignmentCommitOption' cannot be null");
		this.assignmentCommitOption = assignmentCommitOption;
	}

	public boolean isDeliveryAttemptHeader() {
		return this.deliveryAttemptHeader;
	}

	/**
	 * Set to true to populate the {@link KafkaHeaders#DELIVERY_ATTEMPT} header when the
	 * error handler or after rollback processor implements {@code DeliveryAttemptAware}.
	 * There is a small overhead so this is false by default.
	 * @param deliveryAttemptHeader true to populate
	 * @since 2.5
	 */
	public void setDeliveryAttemptHeader(boolean deliveryAttemptHeader) {
		this.deliveryAttemptHeader = deliveryAttemptHeader;
	}

	/**
	 * Get the exactly once semantics mode.
	 * @return the mode.
	 * @since 2.5
	 * @see #setEosMode(EOSMode)
	 */
	public EOSMode getEosMode() {
		return this.eosMode;
	}

	/**
	 * Set the exactly once semantics mode. When {@link EOSMode#ALPHA} a producer per
	 * group/topic/partition is used (enabling 'transactional.id fencing`).
	 * {@link EOSMode#BETA} enables fetch-offset-request fencing, and requires brokers 2.5
	 * or later. In the 2.6 client, the default will be BETA because the 2.6 client can
	 * automatically fall back to ALPHA.
	 * @param eosMode the mode; default ALPHA.
	 * @since 2.5
	 */
	public void setEosMode(EOSMode eosMode) {
		if (eosMode == null) {
			this.eosMode = EOSMode.ALPHA;
		}
		else {
			this.eosMode = eosMode;
		}
		if (this.eosMode.equals(EOSMode.BETA) && this.subBatchPerPartition == null) {
			this.subBatchPerPartition = false;
		}
	}

	/**
	 * Get the transaction definition.
	 * @return the definition.
	 * @since 2.5.4
	 */
	@Nullable
	public TransactionDefinition getTransactionDefinition() {
		return this.transactionDefinition;
	}

	/**
	 * Set a transaction definition with properties (e.g. timeout) that will be copied to
	 * the container's transaction template. Note that this is only generally useful when
	 * used with a
	 * {@link org.springframework.kafka.transaction.ChainedKafkaTransactionManager}
	 * configured with a non-Kafka transaction manager. Kafka has no concept of
	 * transaction timeout, for example.
	 * @param transactionDefinition the definition.
	 * @since 2.5.4
	 */
	public void setTransactionDefinition(TransactionDefinition transactionDefinition) {
		this.transactionDefinition = transactionDefinition;
	}

	/**
	 * A chain of listener {@link Advice}s.
	 * @return the adviceChain.
	 * @since 2.5.6
	 */
	public Advice[] getAdviceChain() {
		return this.adviceChain.toArray(new Advice[0]);
	}

	/**
	 * Set a chain of listener {@link Advice}s; must not be null or have null elements.
	 * @param adviceChain the adviceChain to set.
	 * @since 2.5.6
	 */
	public void setAdviceChain(Advice... adviceChain) {
		Assert.notNull(adviceChain, "'adviceChain' cannot be null");
		Assert.noNullElements(adviceChain, "'adviceChain' cannot have null elements");
		this.adviceChain.clear();
		this.adviceChain.addAll(Arrays.asList(adviceChain));
		if (this.messageListener != null) {
			adviseListenerIfNeeded();
		}
	}

	/**
	 * When true, the container will stop after a
	 * {@link org.apache.kafka.common.errors.ProducerFencedException}.
	 * @return the stopContainerWhenFenced
	 * @since 2.5.8
	 */
	public boolean isStopContainerWhenFenced() {
		return this.stopContainerWhenFenced;
	}

	/**
	 * Set to true to stop the container when a
	 * {@link org.apache.kafka.common.errors.ProducerFencedException} is thrown.
	 * Currently, there is no way to determine if such an exception is thrown due to a
	 * rebalance Vs. a timeout. We therefore cannot call the after rollback processor. The
	 * best solution is to ensure that the {@code transaction.timeout.ms} is large enough
	 * so that transactions don't time out.
	 * @param stopContainerWhenFenced true to stop the container.
	 * @since 2.5.8
	 */
	public void setStopContainerWhenFenced(boolean stopContainerWhenFenced) {
		this.stopContainerWhenFenced = stopContainerWhenFenced;
	}

	private void adviseListenerIfNeeded() {
		if (!CollectionUtils.isEmpty(this.adviceChain)) {
			if (AopUtils.isAopProxy(this.messageListener)) {
				Advised advised = (Advised) this.messageListener;
				this.adviceChain.forEach(advised::removeAdvice);
				this.adviceChain.forEach(advised::addAdvice);
			}
			else {
				ProxyFactory pf = new ProxyFactory(this.messageListener);
				this.adviceChain.forEach(pf::addAdvice);
				this.messageListener = pf.getProxy();
			}
		}
	}

	@Override
	public String toString() {
		return "ContainerProperties ["
				+ renderProperties()
				+ ", ackMode=" + this.ackMode
				+ ", ackCount=" + this.ackCount
				+ ", ackTime=" + this.ackTime
				+ ", messageListener=" + this.messageListener
				+ (this.consumerTaskExecutor != null
						? ", consumerTaskExecutor=" + this.consumerTaskExecutor
						: "")
				+ ", shutdownTimeout=" + this.shutdownTimeout
				+ ", ackOnError=" + this.ackOnError
				+ ", idleEventInterval="
				+ (this.idleEventInterval == null ? "not enabled" : this.idleEventInterval)
				+ (this.transactionManager != null
						? ", transactionManager=" + this.transactionManager
						: "")
				+ ", monitorInterval=" + this.monitorInterval
				+ (this.scheduler != null ? ", scheduler=" + this.scheduler : "")
				+ ", noPollThreshold=" + this.noPollThreshold
				+ ", subBatchPerPartition=" + this.subBatchPerPartition
				+ ", assignmentCommitOption=" + this.assignmentCommitOption
				+ ", deliveryAttemptHeader=" + this.deliveryAttemptHeader
				+ ", eosMode=" + this.eosMode
				+ "]";
	}

}
