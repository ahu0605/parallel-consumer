package io.confluent.csid.asyncconsumer;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.confluent.csid.asyncconsumer.AsyncConsumer.Tuple.pairOf;
import static io.confluent.csid.utils.KafkaUtils.toTP;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.waitAtMost;

/**
 * @param <K> key
 * @param <V> value
 */
@Slf4j
public class AsyncConsumer<K, V> implements Closeable { // TODO generics for produce vs consume, different produce topics may need different generics

    final private org.apache.kafka.clients.consumer.Consumer<K, V> consumer;

    final private org.apache.kafka.clients.producer.Producer<K, V> producer;

    protected final Duration defaultTimeout = Duration.ofSeconds(10); // increase if debugging

    private final int numberOfThreads = 3;

    private final AsyncConsumerOptions asyncConsumerOptions;

    private boolean shouldPoll = true;

    // TODO configurable number of threads
    final private ExecutorService workerPool = Executors.newFixedThreadPool(numberOfThreads);

    final private ExecutorService internalPool = Executors.newSingleThreadExecutor();

    private Future<?> pollThread;

    final private Map<TopicPartition, TreeMap<Long, WorkContainer<K, V>>> inFlightPerPartition = new HashMap<>();

    private final Map<TopicPartition, OffsetAndMetadata> offsetsToSend = new HashMap<>();

    protected WorkManager<K, V> wm;

    @Setter
    @Getter
    private Duration longPollTimeout = Duration.ofMillis(2000);

    @Setter
    @Getter
    private Duration timeBetweenCommits = ofSeconds(1);

    private Instant lastCommit = Instant.now();

    private final Queue<WorkContainer<K, V>> mailBox = new ConcurrentLinkedQueue<>(); // Thread safe, highly performant, non blocking

    public AsyncConsumer(org.apache.kafka.clients.consumer.Consumer<K, V> consumer,
                         org.apache.kafka.clients.producer.Producer<K, V> producer,
                         AsyncConsumerOptions asyncConsumerOptions) {
        this.consumer = consumer;
        this.producer = producer;
        this.asyncConsumerOptions = asyncConsumerOptions;
        wm = new WorkManager<>(1000, asyncConsumerOptions);
    }

    /**
     * todo
     *
     * @param usersVoidConsumptionFunction
     */
    public void asyncPoll(Consumer<ConsumerRecord<K, V>> usersVoidConsumptionFunction) {
        Function<ConsumerRecord<K, V>, List<Object>> wrappedUserFunc = (record) -> {
            log.info("asyncPoll - Consumed a record ({}), executing void function...", record.value());
            usersVoidConsumptionFunction.accept(record);
            List<Object> userFunctionReturnsNothing = List.of();
            return userFunctionReturnsNothing;
        };
        Consumer<Object> voidCallBack = (ignore) -> log.trace("Void callback applied.");
        asyncPollInternal(wrappedUserFunc, voidCallBack);
    }

    /**
     * todo
     *
     * @param userFunction
     * @param callback     applied after message ack'd by kafka
     * @return a stream of results for monitoring, as messages are produced into the output topic
     */
    @SneakyThrows
    public <R> void asyncPollAndProduce(Function<ConsumerRecord<K, V>, List<ProducerRecord<K, V>>> userFunction,
                                        Consumer<ConsumeProduceResult<K, V, K, V>> callback) {
        // wrap user func to add produce function
        Function<ConsumerRecord<K, V>, List<ConsumeProduceResult<K, V, K, V>>> wrappedUserFunc = (consumedRecord) -> {
            List<ProducerRecord<K, V>> recordListToProduce = userFunction.apply(consumedRecord);
            if (recordListToProduce.isEmpty()) {
                log.warn("No result returned from function to send.");
            }
            log.info("asyncPoll and Stream - Consumed and a record ({}), and returning a derivative result record to be produced: {}", consumedRecord, recordListToProduce);

            List<ConsumeProduceResult<K, V, K, V>> results = new ArrayList<>();
            for (ProducerRecord<K, V> toProduce : recordListToProduce) {
                RecordMetadata produceResultMeta = produceMessage(toProduce);
                var result = new ConsumeProduceResult<>(consumedRecord, toProduce, produceResultMeta);
                results.add(result);
            }
            return results;
        };

        asyncPollInternal(wrappedUserFunc, callback);
    }

    RecordMetadata produceMessage(ProducerRecord<K, V> outMsg) {
        Future<RecordMetadata> send = producer.send(outMsg);
        try {
            var recordMetadata = send.get(); // TODO don't block
            return recordMetadata;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        close(defaultTimeout);
    }

    @SneakyThrows
    public void close(Duration timeout) {
        log.info("Closing...");
        waitForNoInFlight(timeout);

        shutdownPollLoop();

        List<Runnable> unfinished = workerPool.shutdownNow();
        if (!unfinished.isEmpty()) {
            log.warn("Threads not done: {}", unfinished);
        }
        log.debug("Awaiting worker termination...");
        boolean terminationFinished = workerPool.awaitTermination(10L, SECONDS); // todo make smarter
        if (!terminationFinished) {
            log.warn("workerPool termination interrupted!");
            boolean shutdown = workerPool.isShutdown();
            boolean terminated = workerPool.isTerminated();
        }
        internalPool.shutdown();
        log.debug("Awaiting controller termination...");
        boolean internalShutdown = internalPool.awaitTermination(10L, SECONDS);

        log.debug("Closing producer and consumer...");
        this.consumer.close(defaultTimeout);
        this.producer.close(defaultTimeout);

        log.trace("Checking for control thread exception...");
        pollThread.get(timeout.toSeconds(), SECONDS); // throws exception if supervisor saw one

        log.debug("Close complete.");
    }

    public void waitForNoInFlight(Duration timeout) {
        log.debug("Waiting for no in flight...");
        waitAtMost(timeout).until(this::noInflight);
        log.debug("No longer anything in flight.");
    }

    private boolean noInflight() {
        return !hasWorkLeft() || areMyThreadsDone();
    }

    private void shutdownPollLoop() {
        this.shouldPoll = false;
    }

    private boolean hasWorkLeft() {
        return hasInFlightRemaining() || hasOffsetsToCommit();
    }

    private boolean areMyThreadsDone() {
        if (pollThread == null) {
            // not constructed yet, will become alive, unless #poll is never called // TODO fix
            return false;
        }
        return pollThread.isDone();
    }

    private boolean hasInFlightRemaining() {
        for (var entry : inFlightPerPartition.entrySet()) {
            TopicPartition x = entry.getKey();
            TreeMap<Long, WorkContainer<K, V>> workInPartitionInFlight = entry.getValue();
            if (!workInPartitionInFlight.isEmpty())
                return true;
        }
        return false;
    }

    private boolean hasOffsetsToCommit() {
        return !offsetsToSend.isEmpty();
    }

//    private <R> void asyncPollInternal(Function<ConsumerRecord<K, V>, List<R>> userFunction,
//                                       Consumer<Tuple<ConsumerRecord<K, V>, R>> callback) {

    protected <R> void asyncPollInternal(Function<ConsumerRecord<K, V>, List<R>> userFunction,
                                       Consumer<R> callback) {

        producer.initTransactions();
        producer.beginTransaction();

        // run main pool loop in thread
        pollThread = internalPool.submit(() -> {
            while (shouldPoll) {
                try {
                    controlLoop(userFunction, callback);
                } catch (Exception e) {
                    log.error("Error from poll control thread ({}), throwing...", e.getMessage(), e);
                    throw new RuntimeException("Error from poll control thread: " + e.getMessage(), e);
                }
            }
            log.trace("Poll loop ended.");
            return true;
        });
    }

    private <R> void controlLoop(Function<ConsumerRecord<K, V>, List<R>> userFunction,
                                 Consumer<R> callback) {
        // TODO doesn't this forever expand if no future ever completes? eventually we'll run out of memory as we consume more and mor records without committing any
        ConsumerRecords<K, V> polledRecords = pollBrokerForRecords();

        wm.registerWork(polledRecords);

        var records = wm.<R>getWork();

        submitWorkToPool(userFunction, callback, records);

        processMailBox();

        findCompletedFutureOffsets();

        commitOffsetsMaybe(offsetsToSend);

        if (!shouldPoll) {
            waitForNoInFlight(defaultTimeout);
            commitOffsetsThatAreReady(offsetsToSend);
        }

        // end of loop
        Thread.yield();
    }

    private void processMailBox() {
        log.debug("Processing mailbox...");
        while (!mailBox.isEmpty()) {
            log.debug("Mail received...");
            var work = mailBox.poll();
            handleFutureResult(work);
        }
    }

    // todo keep alive with broker while processing messages when too many in flight
    private ConsumerRecords<K, V> pollBrokerForRecords() {
        ConsumerRecords<K, V> records = this.consumer.poll(longPollTimeout);
        if (records.isEmpty()) {
            try {
                log.debug("No records returned, simulating long poll with sleep for {}...", longPollTimeout); // TODO remove to mock producer
                Thread.sleep(longPollTimeout.toMillis()); // todo remove - used for testing where mock consumer poll instantly (doesn't simulate waiting for new data)
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            log.debug("Polled and found {} records...", records.count());
        }
        return records;
    }

    private void commitOffsetsMaybe(Map<TopicPartition, OffsetAndMetadata> offsetsToSend) {
        Instant now = Instant.now();
        Duration between = Duration.between(lastCommit, now);
        if (between.toSeconds() >= timeBetweenCommits.toSeconds()) {
            commitOffsetsThatAreReady(offsetsToSend);
            lastCommit = Instant.now();
        } else {
            if (hasOffsetsToCommit()) {
                log.debug("Have offsets to commit, but not enough time elapsed ({}), waiting for at least {}...", between, timeBetweenCommits);
            }
        }
    }

    private void commitOffsetsThatAreReady(Map<TopicPartition, OffsetAndMetadata> offsetsToSend) {
        if (!offsetsToSend.isEmpty()) {
            log.info("Committing offsets for {} partition(s): {}", offsetsToSend.size(), offsetsToSend);
            ConsumerGroupMetadata groupMetadata = consumer.groupMetadata();
            producer.sendOffsetsToTransaction(offsetsToSend, groupMetadata);
            producer.commitTransaction();
            this.offsetsToSend.clear();// = new HashMap<>();// todo remove? smelly?
            producer.beginTransaction();
        } else {
            log.debug("No offsets ready");
        }
    }

    protected void handleFutureResult(WorkContainer<K, V> wc) {
        if (wc.getUserFunctionSucceeded().get()) {
            onSuccess(wc);
        } else {
            onFailure(wc);
        }
    }

    private void onFailure(WorkContainer<K, V> wc) {
        // error occurred, put it back in the queue if it can be retried
        // if not explicitly retriable, put it back in with an try counter so it can be later given up on
        wm.failed(wc);
    }

    protected void onSuccess(WorkContainer<K, V> wc) {
        log.debug("Processing success...");
        wm.success(wc);
    }

    protected void foundOffsetToSend(WorkContainer<K, V> wc) {
        log.debug("Found offset candidate ({}) to add to offset commit map", wc.getCr().offset());
        ConsumerRecord<?, ?> cr = wc.getCr();
        long offset = cr.offset();
        OffsetAndMetadata offsetData = new OffsetAndMetadata(offset, ""); // TODO blank string?
        TopicPartition topicPartitionKey = toTP(cr);
        // as inflights are processed in order, this will keep getting overwritten with the highest offset available
        offsetsToSend.put(topicPartitionKey, offsetData);
    }

    /**
     * todo
     *
     * @param usersFunction
     * @param callback
     * @param workToProcess the polled records to process
     * @param <R>
     */
    private <R> void submitWorkToPool(Function<ConsumerRecord<K, V>, List<R>> usersFunction,
                                      Consumer<R> callback,
                                      List<WorkContainer<K, V>> workToProcess) {

        for (var work : workToProcess) {
            // for each record, construct dispatch to the executor and capture a Future
            ConsumerRecord<K, V> cr = work.getCr();

            Future outputRecordFuture = workerPool.submit(() -> {
                return userFunctionRunner(usersFunction, callback, work);
            });
            work.setFuture(outputRecordFuture);

            final TopicPartition inputTopicPartition = work.getTopicPartition();
            long offset = cr.offset();

            // ensure we have a TreeMap (ordered map by key) for the topic partition we're reading from
            var offsetToFuture = inFlightPerPartition
                    .computeIfAbsent(inputTopicPartition, (ignore) -> new TreeMap<>());
            // store the future reference, against it's the offset as key
            offsetToFuture.put(offset, work);
        }
    }

    protected <R> ArrayList<Tuple<ConsumerRecord<K, V>, R>>
    userFunctionRunner(Function<ConsumerRecord<K, V>, List<R>> usersFunction,
                       Consumer<R> callback,
                       WorkContainer<K, V> wc) {

        // call the user's function
        List<R> resultsFromUserFunction;
        try {
            ConsumerRecord<K, V> rec = wc.getCr();
            resultsFromUserFunction = usersFunction.apply(rec);
            log.info("User function success");

            onUserFunctionSuccess(wc, resultsFromUserFunction);

            // capture each result, against the input record
            var intermediateResults = new ArrayList<Tuple<ConsumerRecord<K, V>, R>>();
            for (R result : resultsFromUserFunction) {
                log.trace("Running call back...");
                callback.accept(result);
            }
            log.info("User function future registered");
            // fail or succeed, either way we're done
            addToMailBoxOnUserFunctionSuccess(wc, resultsFromUserFunction);
            return intermediateResults;
        } catch (Exception e) {
            // handle fail
            log.debug("Error in user function", e);
            wc.onUserFunctionFailure();
            addToMailbox(wc); // always add on error
            throw e; // trow again to make the future failed
        }
    }

    @SneakyThrows
    private <R> void findCompletedFutureOffsets() {
        int count = 0;
        int removed = 0;
        log.debug("Scanning for in order in-flight work that has completed...");
        for (final var inFlightInPartition : inFlightPerPartition.entrySet()) {
            count += inFlightInPartition.getValue().size();
            var offsetsToRemoveFromInFlight = new LinkedList<Long>();
            TreeMap<Long, WorkContainer<K, V>> inFlightFutures = inFlightInPartition.getValue();
            for (final var offsetAndItsWorkContainer : inFlightFutures.entrySet()) {
                // ordered iteration via offset keys thanks to the treemap
                WorkContainer<K, V> container = offsetAndItsWorkContainer.getValue();
                boolean complete = isWorkComplete(container);
                if (complete) {
                    long offset = container.getCr().offset();
                    offsetsToRemoveFromInFlight.add(offset);
                    if (container.getUserFunctionSucceeded().get()) {
                        log.debug("Work completed successful, so marking to commit");
                        foundOffsetToSend(container);
                    } else {
                        log.debug("Offset {} is complete, but failed and is holding up the queue. Ending partition scan.", container.getCr().offset());
                        // can't scan any further
                        break;
                    }
                } else {
                    // can't commit this offset or beyond, as this is the latest offset that is incomplete
                    // i.e. only commit offsets that come before the current one, and stop looking for more
                    log.debug("Offset {} is incomplete, holding up the queue ({}). Ending partition scan.",
                            container.getCr().offset(),
                            inFlightInPartition.getKey());
                    break;
                }
            }

            removed += offsetsToRemoveFromInFlight.size();
            for (Long offset : offsetsToRemoveFromInFlight) {
                inFlightFutures.remove(offset);
            }
        }
        log.debug("Scan finished, {} remaining in flight, {} completed offsets removed, {} offsets be committed",
                count, removed, this.offsetsToSend.size());
    }

    protected boolean isWorkComplete(WorkContainer<K, V> container) {
        return container.isComplete();
    }

    protected void addToMailBoxOnUserFunctionSuccess(WorkContainer<K, V> wc, List<?> resultsFromUserFunction) {
        addToMailbox(wc);
    }

    protected void onUserFunctionSuccess(WorkContainer<K, V> wc, List<?> resultsFromUserFunction) {
        log.info("User function success");
        wc.onUserFunctionSuccess(); // todo incorrectly succeeds vertx functions
    }

    protected void addToMailbox(WorkContainer<K, V> wc) {
        log.debug("Adding to mailbox...");
        mailBox.add(wc);
    }

    public int workRemaining() {
        return wm.workRemainingCount();
    }

    @Data
    public static class Tuple<L, R> {
        final private L left;
        final private R right;

        public static <LL, RR> Tuple<LL, RR> pairOf(LL l, RR r) {
            return new Tuple<>(l, r);
        }
    }

    /**
     * @param <K>  in key
     * @param <V>  in value
     * @param <KK> out key
     * @param <VV> out value
     */
    @Data
    public static class ConsumeProduceResult<K, V, KK, VV> {
        final private ConsumerRecord<K, V> in;
        final private ProducerRecord<KK, VV> out;
        final private RecordMetadata meta;
    }

}