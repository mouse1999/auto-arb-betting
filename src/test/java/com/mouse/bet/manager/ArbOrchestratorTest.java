//package com.mouse.bet.manager;
//
//import com.mouse.bet.entity.Arb;
//import com.mouse.bet.entity.BetLeg;
//import com.mouse.bet.enums.BookMaker;
//import com.mouse.bet.enums.Status;
//import com.mouse.bet.model.arb.LegResult;
//import com.mouse.bet.service.ArbService;
//import com.mouse.bet.tasks.LegTask;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.Arguments;
//import org.junit.jupiter.params.provider.MethodSource;
//import org.junit.jupiter.params.provider.ValueSource;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.math.BigDecimal;
//import java.time.Duration;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.stream.Stream;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class ArbOrchestratorTest {
//
//    @Mock
//    private ArbService arbService;
//
//    private ArbOrchestrator orchestrator;
//    private Duration legTimeout;
//    private Duration retryBackoff;
//
//    @BeforeEach
//    void setUp() {
//        legTimeout = Duration.ofSeconds(5);
//        int maxRetries = 3;
//        retryBackoff = Duration.ofMillis(100);
//        orchestrator = new ArbOrchestrator(arbService, legTimeout, maxRetries, retryBackoff);
//    }
//
//    @AfterEach
//    void tearDown() {
//        orchestrator.stop();
//    }
//
//    // ================ Constructor Tests ================
//
//    @Test
//    void constructor_withValidParameters_createsInstance() {
//        ArbOrchestrator newOrchestrator = new ArbOrchestrator(
//                arbService,
//                Duration.ofSeconds(10),
//                5,
//                Duration.ofMillis(200)
//        );
//
//        assertThat(newOrchestrator).isNotNull();
//        assertThat(newOrchestrator.getArbQueue()).hasSize(0);
//        assertThat(newOrchestrator.getWorkerQueues()).isEmpty();
//    }
//
//    @Test
//    void constructor_withNullArbService_throwsNullPointerException() {
//        assertThatThrownBy(() -> new ArbOrchestrator(
//                null,
//                Duration.ofSeconds(5),
//                3,
//                Duration.ofMillis(100)
//        )).isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void constructor_withNullLegTimeout_throwsNullPointerException() {
//        assertThatThrownBy(() -> new ArbOrchestrator(
//                arbService,
//                null,
//                3,
//                Duration.ofMillis(100)
//        )).isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void constructor_withNullRetryBackoff_throwsNullPointerException() {
//        assertThatThrownBy(() -> new ArbOrchestrator(
//                arbService,
//                Duration.ofSeconds(5),
//                3,
//                null
//        )).isInstanceOf(NullPointerException.class);
//    }
//
//    @ParameterizedTest
//    @ValueSource(ints = {0, -1, -10})
//    void constructor_withNonPositiveRetries_createsInstanceWithValue(int retries) {
//        ArbOrchestrator newOrchestrator = new ArbOrchestrator(
//                arbService,
//                Duration.ofSeconds(5),
//                retries,
//                Duration.ofMillis(100)
//        );
//
//        assertThat(newOrchestrator).isNotNull();
//    }
//
//    // ================ registerWorker Tests ================
//
//    @Test
//    void registerWorker_withValidParameters_registersWorkerQueue() {
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.M_SPORT, queue);
//
//        assertThat(orchestrator.getWorkerQueues()).containsKey(BookMaker.M_SPORT);
//        assertThat(orchestrator.getWorkerQueues().get(BookMaker.M_SPORT)).isSameAs(queue);
//    }
//
//    @Test
//    void registerWorker_withNullBookmaker_throwsNullPointerException() {
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        assertThatThrownBy(() -> orchestrator.registerWorker(null, queue))
//                .isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void registerWorker_withNullQueue_throwsNullPointerException() {
//        assertThatThrownBy(() -> orchestrator.registerWorker(BookMaker.M_SPORT, null))
//                .isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void registerWorker_withMultipleWorkers_registersAllWorkers() {
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue3 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.M_SPORT, queue1);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, queue2);
//        orchestrator.registerWorker(BookMaker.BET9JA, queue3);
//
//        assertThat(orchestrator.getWorkerQueues()).hasSize(3);
//        assertThat(orchestrator.getWorkerQueues()).containsKeys(
//                BookMaker.M_SPORT,
//                BookMaker.SPORTY_BET,
//                BookMaker.BET9JA
//        );
//    }
//
//    @Test
//    void registerWorker_replacingExistingWorker_replacesQueue() {
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.BET9JA, queue2);
//
//        assertThat(orchestrator.getWorkerQueues()).hasSize(1);
//        assertThat(orchestrator.getWorkerQueues().get(BookMaker.BET9JA)).isSameAs(queue2);
//    }
//
//    // ================ tryLoadArb Tests ================
//
//    @Test
//    void tryLoadArb_withEmptyQueue_returnsTrue() {
//        Arb arb = createTestArb("arb-1");
//
//        boolean result = orchestrator.tryLoadArb(arb);
//
//        assertThat(result).isTrue();
//        assertThat(orchestrator.getArbQueue()).hasSize(1);
//    }
//
//    @Test
//    void tryLoadArb_withFullQueue_returnsFalse() {
//        Arb arb1 = createTestArb("arb-1");
//        Arb arb2 = createTestArb("arb-2");
//
//        orchestrator.tryLoadArb(arb1);
//        boolean result = orchestrator.tryLoadArb(arb2);
//
//        assertThat(result).isFalse();
//        assertThat(orchestrator.getArbQueue()).hasSize(1);
//    }
//
//    @Test
//    void tryLoadArb_withNullArb_throwsNullPointerException() {
//        assertThatThrownBy(() -> orchestrator.tryLoadArb(null))
//                .isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void tryLoadArb_afterQueueIsProcessed_returnsTrueAgain() throws InterruptedException {
//        Arb arb1 = createTestArb("arb-1");
//        Arb arb2 = createTestArb("arb-2");
//
//        orchestrator.tryLoadArb(arb1);
//        orchestrator.getArbQueue().take(); // Simulate processing
//        boolean result = orchestrator.tryLoadArb(arb2);
//
//        assertThat(result).isTrue();
//    }
//
//    // ================ loadArb Tests ================
//
//    @Test
//    void loadArb_withEmptyQueue_addsArb() throws InterruptedException {
//        Arb arb = createTestArb("arb-1");
//
//        orchestrator.loadArb(arb);
//
//        assertThat(orchestrator.getArbQueue()).hasSize(1);
//        assertThat(orchestrator.getArbQueue().peek()).isEqualTo(arb);
//    }
//
//    @Test
//    void loadArb_withNullArb_throwsNullPointerException() {
//        assertThatThrownBy(() -> orchestrator.loadArb(null))
//                .isInstanceOf(NullPointerException.class);
//    }
//
//    @Test
//    void loadArb_withFullQueue_blocksUntilSpaceAvailable() throws Exception {
//        Arb arb1 = createTestArb("arb-1");
//        Arb arb2 = createTestArb("arb-2");
//
//        orchestrator.loadArb(arb1);
//
//        CountDownLatch latch = new CountDownLatch(1);
//        ExecutorService executor = Executors.newSingleThreadExecutor();
//
//        Future<?> future = executor.submit(() -> {
//            try {
//                latch.countDown();
//                orchestrator.loadArb(arb2);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//        });
//
//        latch.await();
//        Thread.sleep(100); // Give time for blocking
//        assertThat(orchestrator.getArbQueue()).hasSize(1);
//
//        orchestrator.getArbQueue().take(); // Free space
//        Thread.sleep(100);
//
//        assertThat(orchestrator.getArbQueue()).hasSize(1);
//
//        future.cancel(true);
//        executor.shutdownNow();
//    }
//
//    @Test
//    void loadArb_whenInterrupted_throwsInterruptedException() throws Exception {
//        Arb arb1 = createTestArb("arb-1");
//        Arb arb2 = createTestArb("arb-2");
//
//        orchestrator.loadArb(arb1);
//
//        Thread testThread = new Thread(() -> {
//            try {
//                orchestrator.loadArb(arb2);
//                fail("Should have thrown InterruptedException");
//            } catch (InterruptedException e) {
//                // Expected
//            }
//        });
//
//        testThread.start();
//        Thread.sleep(50);
//        testThread.interrupt();
//        testThread.join(1000);
//    }
//
//    // ================ start Tests ================
//
//    @Test
//    void start_whenNotRunning_startsOrchestrator() throws InterruptedException {
//        orchestrator.start();
//        Thread.sleep(100);
//
//        verify(arbService, atLeastOnce()).fetchTopArbsByMetrics(any(BigDecimal.class), anyInt());
//    }
//
//    @Test
//    void start_whenAlreadyRunning_doesNotStartAgain() throws InterruptedException {
//        orchestrator.start();
//        Thread.sleep(100);
//        orchestrator.start();
//        orchestrator.start();
//
//        // Should only start once
//        assertThat(orchestrator).isNotNull();
//    }
//
//    @Test
//    void start_processesArbFromQueue_updatesStatusToInProgress() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.M_SPORT);
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.M_SPORT, queue2);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-1") && a.getStatus() == Status.IN_PROGRESS
//        ));
//    }
//
//    // ================ stop Tests ================
//
//    @Test
//    void stop_whenRunning_stopsOrchestrator() throws InterruptedException {
//        orchestrator.start();
//        Thread.sleep(100);
//
//        orchestrator.stop();
//        Thread.sleep(100);
//
//        assertThat(orchestrator).isNotNull();
//    }
//
//    @Test
//    void stop_whenNotRunning_doesNotThrowException() {
//        assertThatCode(() -> orchestrator.stop()).doesNotThrowAnyException();
//    }
//
//    @Test
//    void stop_interruptsBlockingOperations() throws Exception {
//        orchestrator.start();
//        Thread.sleep(100);
//
//        orchestrator.stop();
//        Thread.sleep(100);
//
//        // Should complete without hanging
//        assertThat(orchestrator).isNotNull();
//    }
//
//    // ================ processOneArb Tests ================
//
//    @Test
//    void processOneArb_withNoRegisteredWorkers_failsArbImmediately() throws Exception {
//        // New behavior: any leg with an unregistered bookmaker => FAIL immediately
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.M_SPORT);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-1") && a.getStatus() == Status.FAILED
//        ));
//    }
//
//    @Test
//    void processOneArb_withNoMatchingLegs_failsArb() throws Exception {
//        // One leg exists but its bookmaker is NOT registered => FAIL
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.M_SPORT);
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue); // doesn't match M_SPORT
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-1") && a.getStatus() == Status.FAILED
//        ));
//    }
//
//    @Test
//    void processOneArb_withMatchingWorkers_dispatchesTasksToWorkerQueues() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.SPORTY_BET);
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, queue2);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        Thread.sleep(500);
//
//        assertThat(queue1).isNotEmpty();
//        assertThat(queue2).isNotEmpty();
//    }
//
//    @Test
//    void processOneArb_whenAllLegsSucceed_completesArbWithCompletedStatus() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.SPORTY_BET);
//        CountDownLatch latch = new CountDownLatch(2);
//
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, queue2);
//
//        // Simulate workers completing tasks successfully
//        startMockWorker(queue1, latch, true);
//        startMockWorker(queue2, latch, true);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-1") && a.getStatus() == Status.COMPLETED
//        ));
//    }
//
//    @Test
//    void processOneArb_whenAnyLegFails_completesArbWithFailedStatus() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.SPORTY_BET);
//        CountDownLatch latch = new CountDownLatch(2);
//
//        List<Status> savedStatuses = Collections.synchronizedList(new ArrayList<>());
//
//        doAnswer(inv -> {
//            Arb a = inv.getArgument(0);
//            savedStatuses.add(a.getStatus());
//            return null; // saveArb is void
//             }).when(arbService).saveArb(any(Arb.class));
//
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, queue2);
//
//        startMockWorker(queue1, latch, true);
//        startMockWorker(queue2, latch, false); // This one fails
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(200);
//
//        ArgumentCaptor<Arb> arbCaptor = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(arbCaptor.capture());
//
////        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
////                a.getArbId().equals("arb-1") && a.getStatus() == Status.FAILED
////        ));
//        assertThat(savedStatuses).contains(Status.IN_PROGRESS);
//        assertThat(savedStatuses).contains(Status.FAILED);
//    }
//
//    @Test
//    void processOneArb_whenWorkerExhaustsTimeBudget_arbitrationFailsButOrchestratorWaits() throws Exception {
//        // One leg (BET9JA) whose worker will run and then fail after its own time budget
//        Arb arb = createTestArbWithLegs("arb-budget", BookMaker.BET9JA);
//
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, queue);
//
//        CountDownLatch latch = new CountDownLatch(1);
//
//        // Worker simulates retries within its own budget, then marks FAILED and arrives on the barrier
//        startBudgetedFailingWorker(queue, latch,
//                /*workMillis=*/ 300, /*budgetMillis=*/ 250);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        // Wait for worker to finish (arriveAndDeregister), orchestrator then sets status FAILED
//        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
//
//        Thread.sleep(200); // allow orchestrator to persist final state
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-budget") && a.getStatus() == Status.FAILED
//        ));
//
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withUnregisteredWorkerForLeg_failsArbImmediately() throws Exception {
//        // New behavior: do NOT delegate; mark FAILED and return.
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.SPORTY_BET);
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        // Only register one worker
//        orchestrator.registerWorker(BookMaker.BET9JA, queue);
//        // SPORTY_BET worker not registered
//
//        CountDownLatch latch = new CountDownLatch(1);
//        startMockWorker(queue, latch, true); // even if this succeeds, overall Arb should FAIL
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-1") && a.getStatus() == Status.FAILED
//        ));
//    }
//
//    @Test
//    void processOneArb_withMultipleLegsFromSameBookmaker_processesFirstLegOnly() throws Exception {
//        Arb arb = createTestArbWithMultipleLegsFromSameBookmaker("arb-1");
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue);
//
//        CountDownLatch latch = new CountDownLatch(1);
//        startMockWorker(queue, latch, true);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(500);
//
//        // Should only dispatch one task per bookmaker
//        assertThat(queue).isEmpty();
//    }
//
//
//
//    // ================ Integration Tests ================
//
//    @Test
//    void fullWorkflow_withSuccessfulExecution_completesArb() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-1", BookMaker.BET9JA, BookMaker.M_SPORT);
//
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(List.of());
//
//        List<Status> savedStatuses = Collections.synchronizedList(new ArrayList<>());
//
//        doAnswer(inv -> {
//            Arb a = inv.getArgument(0);
//            savedStatuses.add(a.getStatus());
//            return null; // saveArb is void
//        }).when(arbService).saveArb(any(Arb.class));
//
//        BlockingQueue<LegTask> queue1 = new LinkedBlockingQueue<>();
//        BlockingQueue<LegTask> queue2 = new LinkedBlockingQueue<>();
//
//        orchestrator.registerWorker(BookMaker.BET9JA, queue1);
//        orchestrator.registerWorker(BookMaker.M_SPORT, queue2);
//
//        CountDownLatch latch = new CountDownLatch(2);
//        startMockWorker(queue1, latch, true);
//        startMockWorker(queue2, latch, true);
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        // Wait for both legs to finish
//        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
//
//        // Give orchestrator time to flip status to COMPLETED and persist
//        Thread.sleep(300);
//
//        ArgumentCaptor<Arb> arbCaptor = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(arbCaptor.capture());
//
//        assertThat(savedStatuses).contains(Status.IN_PROGRESS);
//        assertThat(savedStatuses).contains(Status.COMPLETED);
//
//        orchestrator.stop();
//    }
//
//    @Test
//    void fullWorkflow_withProactiveFetch_fetchesAndProcessesArb() throws Exception {
//        Arb arb = createTestArbWithLegs("arb-fetched", BookMaker.BET9JA);
//
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(List.of(arb))
//                .thenReturn(Collections.emptyList());
//
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, queue);
//
//        CountDownLatch latch = new CountDownLatch(1);
//        startMockWorker(queue, latch, true);
//
//        orchestrator.start();
//
//        latch.await(3, TimeUnit.SECONDS);
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).fetchTopArbsByMetrics(any(BigDecimal.class), anyInt());
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals("arb-fetched")
//        ));
//    }
//
//    @Test
//    void fullWorkflow_withEmptyFetchResults_continuesRunning() throws Exception {
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(Collections.emptyList());
//
//        orchestrator.start();
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).fetchTopArbsByMetrics(any(BigDecimal.class), anyInt());
//        verify(arbService, never()).saveArb(any());
//    }
//
//    @Test
//    void fullWorkflow_withNullFetchResults_continuesRunning() throws Exception {
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(null);
//
//        orchestrator.start();
//        Thread.sleep(500);
//
//        verify(arbService, atLeastOnce()).fetchTopArbsByMetrics(any(BigDecimal.class), anyInt());
//        verify(arbService, never()).saveArb(any());
//    }
//
//    // ================ Orchestration End-to-End Tests ================
//
//    @Test
//    void runLoop_whenQueueEmpty_fetchesFromService_processesOne() throws Exception {
//        // Arrange: mock an Arb with one leg for BET9JA
//        BetLeg leg = makeLeg(BookMaker.BET9JA);
//        Arb arb = mockArb("E-FETCH", List.of(leg));
//
//        // Service fetch returns this arb
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(List.of(arb));
//
//        // Register worker + fake worker thread that succeeds
//        LinkedBlockingQueue<LegTask> q = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, q);
//
//        // Start fake worker
//        ExecutorService worker = Executors.newSingleThreadExecutor();
//        worker.submit(() -> workerSuccessLoop(q));
//
//        // Act
//        orchestrator.start();
//
//        // Let it run a bit and then stop
//        Thread.sleep(500);
//
//        // Assert: saveArb called at least twice (IN_PROGRESS then COMPLETED)
//        verify(arbService, atLeast(2)).saveArb(any());
//
//        worker.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withEligibleWorkers_allSuccess_setsCompleted() throws Exception {
//        // Arrange
//        BetLeg lA = makeLeg(BookMaker.BET9JA);
//        BetLeg lB = makeLeg(BookMaker.SPORTY_BET);
//        Arb arb = mockArb("E-SUCCESS", List.of(lA, lB));
//
//        LinkedBlockingQueue<LegTask> qA = new LinkedBlockingQueue<>();
//        LinkedBlockingQueue<LegTask> qB = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, qA);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, qB);
//
//        // Fake workers succeed and arrive on barrier
//        ExecutorService workers = Executors.newFixedThreadPool(2);
//        workers.submit(() -> workerSuccessLoop(qA));
//        workers.submit(() -> workerSuccessLoop(qB));
//
//        // Act
//        orchestrator.tryLoadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(600); // allow loop to process
//
//        // Assert: final status set to COMPLETED
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//
//        boolean sawCompleted = saved.getAllValues().stream()
//                .anyMatch(a -> a.getStatus() == Status.COMPLETED);
//        assertThat(sawCompleted).as("Arb should end as COMPLETED").isTrue();
//
//        workers.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withNoRegisteredWorkers_setsFailed() throws Exception {
//        // Arrange: legs exist but no registered workers match → FAIL immediately (new behavior)
//        BetLeg leg = makeLeg(BookMaker.BET9JA);
//        Arb arb = mockArb("E-NOWORKER", List.of(leg));
//
//        // NOTE: do NOT register BET9JA; registered set is empty
//
//        orchestrator.tryLoadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(400);
//
//        // Assert FAILED
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//        boolean sawFailed = saved.getAllValues().stream()
//                .anyMatch(a -> a.getStatus() == Status.FAILED);
//        assertThat(sawFailed).as("Arb should end as FAILED when no registered workers").isTrue();
//
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withMixedSuccessAndFailure_setsFailed() throws Exception {
//        // Arrange: two legs, one succeeds, one fails
//        BetLeg lA = makeLeg(BookMaker.BET9JA);
//        BetLeg lB = makeLeg(BookMaker.SPORTY_BET);
//        Arb arb = mockArb("E-MIXED", List.of(lA, lB));
//
//        LinkedBlockingQueue<LegTask> qA = new LinkedBlockingQueue<>();
//        LinkedBlockingQueue<LegTask> qB = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, qA);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, qB);
//
//        // Worker A succeeds, Worker B fails
//        ExecutorService workers = Executors.newFixedThreadPool(2);
//        workers.submit(() -> workerSuccessLoop(qA));
//        workers.submit(() -> workerFailureLoop(qB));
//
//        // Act
//        orchestrator.tryLoadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(600);
//
//        // Assert: FAILED status due to at least one failure
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//        boolean sawFailed = saved.getAllValues().stream()
//                .anyMatch(a -> a.getStatus() == Status.FAILED);
//        assertThat(sawFailed).as("Arb should end as FAILED when any leg fails").isTrue();
//
//        workers.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withSingleLegMultipleRetries_eventuallySucceeds() throws Exception {
//        // Arrange: one leg that requires retries (simulated, but our worker succeeds)
//        BetLeg leg = makeLeg(BookMaker.BET9JA);
//        Arb arb = mockArb("E-RETRY", List.of(leg));
//
//        LinkedBlockingQueue<LegTask> q = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, q);
//
//        // Worker that eventually succeeds
//        ExecutorService worker = Executors.newSingleThreadExecutor();
//        worker.submit(() -> workerSuccessLoop(q));
//
//        // Act
//        orchestrator.tryLoadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(600);
//
//        // Assert: COMPLETED
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//        boolean sawCompleted = saved.getAllValues().stream()
//                .anyMatch(a -> a.getStatus() == Status.COMPLETED);
//        assertThat(sawCompleted).as("Arb should eventually complete").isTrue();
//
//        worker.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withThreeLegs_allSuccess_setsCompleted() throws Exception {
//        // Arrange: three legs from different bookmakers
//        BetLeg lA = makeLeg(BookMaker.BET9JA);
//        BetLeg lB = makeLeg(BookMaker.SPORTY_BET);
//        BetLeg lC = makeLeg(BookMaker.M_SPORT);
//        Arb arb = mockArb("E-THREE", List.of(lA, lB, lC));
//
//        LinkedBlockingQueue<LegTask> qA = new LinkedBlockingQueue<>();
//        LinkedBlockingQueue<LegTask> qB = new LinkedBlockingQueue<>();
//        LinkedBlockingQueue<LegTask> qC = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, qA);
//        orchestrator.registerWorker(BookMaker.SPORTY_BET, qB);
//        orchestrator.registerWorker(BookMaker.M_SPORT, qC);
//
//        // Workers all succeed
//        ExecutorService workers = Executors.newFixedThreadPool(3);
//        workers.submit(() -> workerSuccessLoop(qA));
//        workers.submit(() -> workerSuccessLoop(qB));
//        workers.submit(() -> workerSuccessLoop(qC));
//
//        // Act
//        orchestrator.tryLoadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(600);
//
//        // Assert: COMPLETED
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//        boolean sawCompleted = saved.getAllValues().stream()
//                .anyMatch(a -> a.getStatus() == Status.COMPLETED);
//        assertThat(sawCompleted).as("Arb with three legs should complete").isTrue();
//
//        workers.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void processOneArb_withPartiallyRegisteredWorkers_failsUnregisteredLegs() throws Exception {
//        // Arrange: two legs but only one worker registered
//        BetLeg lA = makeLeg(BookMaker.BET9JA);
//        BetLeg lB = makeLeg(BookMaker.SPORTY_BET);
//        Arb arb = mockArb("E-PARTIAL", List.of(lA, lB));
//
//        List<Status> savedStatuses = Collections.synchronizedList(new ArrayList<>());
//
//        doAnswer(inv -> {
//            Arb a = inv.getArgument(0);
//            savedStatuses.add(a.getStatus());
//            return null; // saveArb is void
//        }).when(arbService).saveArb(any(Arb.class));
//
//        LinkedBlockingQueue<LegTask> qA = new LinkedBlockingQueue<>();
//        orchestrator.registerWorker(BookMaker.BET9JA, qA);
//        // SPORTY_BET not registered
//
//        ExecutorService worker = Executors.newSingleThreadExecutor();
//        worker.submit(() -> workerSuccessLoop(qA));
//
//        // Act
//        orchestrator.loadArb(arb);
//        orchestrator.start();
//
//        Thread.sleep(600);
//
//        // Assert: FAILED due to missing worker (hard-gate)
//        ArgumentCaptor<Arb> saved = ArgumentCaptor.forClass(Arb.class);
//        verify(arbService, atLeast(2)).saveArb(saved.capture());
//
//        assertThat(savedStatuses).contains(Status.IN_PROGRESS);
//        assertThat(savedStatuses).contains(Status.FAILED);
//
//        worker.shutdownNow();
//        orchestrator.stop();
//    }
//
//    @Test
//    void runLoop_whenInterrupted_stopsGracefully() throws Exception {
//        // Arrange
//        when(arbService.fetchTopArbsByMetrics(any(BigDecimal.class), anyInt()))
//                .thenReturn(Collections.emptyList());
//
//        // Act
//        orchestrator.start();
//        Thread.sleep(200);
//        orchestrator.stop();
//        Thread.sleep(200);
//
//        // Assert: no exceptions thrown
//        assertThat(orchestrator).isNotNull();
//    }
//
//    // ================ Helper Methods ================
//
//    private Arb createTestArb(String arbId) {
//        return Arb.builder()
//                .arbId(arbId)
//                .status(Status.ACTIVE)
//                .legs(new ArrayList<>())
//                .build();
//    }
//
//    private Arb createTestArbWithLegs(String arbId, BookMaker... bookmakers) {
//        Arb arb = createTestArb(arbId);
//
//        for (BookMaker bookmaker : bookmakers) {
//            BetLeg leg = BetLeg.builder()
//                    .bookmaker(bookmaker)
//                    .odds(new BigDecimal("2.0"))
//                    .stake(new BigDecimal("100"))
//                    .build();
//            arb.getLegs().add(leg);
//            leg.setArb(arb);
//        }
//
//        return arb;
//    }
//
//    private Arb createTestArbWithMultipleLegsFromSameBookmaker(String arbId) {
//        Arb arb = createTestArb(arbId);
//
//        for (int i = 0; i < 3; i++) {
//            BetLeg leg = BetLeg.builder()
//                    .bookmaker(BookMaker.BET9JA)
//                    .odds(new BigDecimal("2.0"))
//                    .stake(new BigDecimal("100"))
//                    .build();
//            arb.getLegs().add(leg);
//            leg.setArb(arb);
//        }
//
//        return arb;
//    }
//
//    private void startMockWorker(BlockingQueue<LegTask> queue, CountDownLatch latch, boolean success) {
//        new Thread(() -> {
//            try {
//                LegTask task = queue.take();
//                Thread.sleep(50);
//                LegResult result = success
//                        ? LegResult.ok()
//                        : LegResult.failed("NOPE");
//                task.getResults().put(task.getBookmaker(), result);
//                task.getBarrier().arriveAndDeregister(); // important
//                latch.countDown();
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//        }, "test-mock-worker").start();
//    }
//
//    /** Creates a BetLeg for testing */
//    private BetLeg makeLeg(BookMaker bm) {
//        return BetLeg.builder()
//                .bookmaker(bm)
//                .odds(new BigDecimal("2.0"))
//                .stake(new BigDecimal("10.00"))
//                .build();
//    }
//
//    /** Creates a mock Arb with mutable status for testing */
//    private Arb mockArb(String arbId, List<BetLeg> legs) {
//        Arb arb = mock(Arb.class, withSettings().lenient());
//
//        final Status[] statusHolder = new Status[]{ Status.ACTIVE };
//
//        when(arb.getArbId()).thenReturn(arbId);
//        when(arb.getStatus()).thenAnswer(inv -> statusHolder[0]);
//        doAnswer(inv -> {
//            statusHolder[0] = inv.getArgument(0);
//            return null;
//        }).when(arb).setStatus(any(Status.class));
//
//        when(arb.getLegs()).thenReturn(legs);
//
//        legs.forEach(leg -> {
//            try {
//                leg.setArb(arb);
//            } catch (Exception ignored) { }
//        });
//
//        return arb;
//    }
//
//    /** Worker loop that marks success and arrives on the barrier */
//    private void workerSuccessLoop(BlockingQueue<LegTask> q) {
//        try {
//            LegTask task = q.take();
//            Thread.sleep(50);
//            LegResult ok = mock(LegResult.class);
//            when(ok.success()).thenReturn(true);
//            task.getResults().put(task.getBookmaker(), ok);
//            task.getBarrier().arriveAndDeregister();
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }
//
//    /** Worker loop that marks failure and arrives on the barrier */
//    private void workerFailureLoop(BlockingQueue<LegTask> q) {
//        try {
//            LegTask task = q.take();
//            Thread.sleep(50);
//            LegResult failed = mock(LegResult.class);
//            when(failed.success()).thenReturn(false);
//            task.getResults().put(task.getBookmaker(), failed);
//            task.getBarrier().arriveAndDeregister();
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }
//
//    private void startBudgetedFailingWorker(BlockingQueue<LegTask> queue,
//                                            CountDownLatch latch,
//                                            long workMillis,
//                                            long budgetMillis) {
//        new Thread(() -> {
//            try {
//                LegTask task = queue.take();
//                long start = System.nanoTime();
//                while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < budgetMillis) {
//                    Thread.sleep(Math.min(40, Math.max(1, workMillis / 5)));
//                }
//                LegResult failed = mock(LegResult.class);
//                when(failed.success()).thenReturn(false);
//                task.getResults().put(task.getBookmaker(), failed);
//                task.getBarrier().arriveAndDeregister();
//                latch.countDown();
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//        }, "test-budgeted-failing-worker").start();
//    }
//
//    // ================ Parameterized Tests ================
//
//    @ParameterizedTest
//    @MethodSource("provideDifferentArbConfigurations")
//    void processOneArb_withDifferentConfigurations_handlesCorrectly(
//            String arbId,
//            List<BookMaker> bookmakers,
//            boolean shouldComplete
//    ) throws Exception {
//        Arb arb = createTestArbWithLegs(arbId, bookmakers.toArray(new BookMaker[0]));
//
//        for (BookMaker bm : BookMaker.values()) {
//            BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//            orchestrator.registerWorker(bm, queue);
//
//            if (bookmakers.contains(bm)) {
//                CountDownLatch latch = new CountDownLatch(1);
//                startMockWorker(queue, latch, true);
//            }
//        }
//
//        orchestrator.start();
//        orchestrator.loadArb(arb);
//
//        Thread.sleep(800);
//
//        Status expectedStatus = Status.COMPLETED;
//        verify(arbService, atLeastOnce()).saveArb(argThat(a ->
//                a.getArbId().equals(arbId) && a.getStatus() == expectedStatus
//        ));
//    }
//
//    private static Stream<Arguments> provideDifferentArbConfigurations() {
//        return Stream.of(
//                Arguments.of("arb-single", List.of(BookMaker.BET9JA), true),
//                Arguments.of("arb-double", List.of(BookMaker.BET9JA, BookMaker.SPORTY_BET), true),
//                Arguments.of("arb-triple", List.of(BookMaker.BET9JA, BookMaker.SPORTY_BET, BookMaker.M_SPORT), true),
//                Arguments.of("arb-empty", List.of(), true)
//        );
//    }
//
//    @ParameterizedTest
//    @ValueSource(ints = {1, 2, 3, 5, 10})
//    void processOneArb_withDifferentMaxRetries_respectsConfiguration(int retries) throws Exception {
//        ArbOrchestrator customOrchestrator = new ArbOrchestrator(
//                arbService,
//                legTimeout,
//                retries,
//                retryBackoff
//        );
//
//        Arb arb = createTestArbWithLegs("arb-retry", BookMaker.BET9JA);
//        BlockingQueue<LegTask> queue = new LinkedBlockingQueue<>();
//
//        customOrchestrator.registerWorker(BookMaker.BET9JA, queue);
//
//        CountDownLatch latch = new CountDownLatch(1);
//        startMockWorker(queue, latch, true);
//
//        customOrchestrator.start();
//        customOrchestrator.loadArb(arb);
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(300);
//
//        // Verify task was created with correct maxRetries (queue drained by worker)
//        assertThat(queue).isEmpty();
//
//        customOrchestrator.stop();
//    }
//}
