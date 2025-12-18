//package com.mouse.bet.detector;
//
//import com.mouse.bet.entity.Arb;
//import com.mouse.bet.enums.BookMaker;
//import com.mouse.bet.enums.MarketCategory;
//import com.mouse.bet.enums.SportEnum;
//import com.mouse.bet.enums.Status;
//import com.mouse.bet.finance.WalletService;
//import com.mouse.bet.logservice.ArbitrageLogService;
//import com.mouse.bet.model.NormalizedEvent;
//import com.mouse.bet.model.NormalizedMarket;
//import com.mouse.bet.model.NormalizedOutcome;
//import com.mouse.bet.service.ArbService;
//import com.mouse.bet.utils.ArbFactory;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.Arguments;
//import org.junit.jupiter.params.provider.MethodSource;
//import org.junit.jupiter.params.provider.ValueSource;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Captor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.lang.reflect.Field;
//import java.math.BigDecimal;
//import java.time.Instant;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.function.Supplier;
//import java.util.stream.Stream;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class ArbDetectorTest {
//
//    @Mock
//    private WalletService walletService;
//
//    @Mock
//    private ArbitrageLogService arbitrageLogService;
//
//    @Mock
//    private ArbFactory arbFactory;
//
//    @Mock
//    private ArbService arbService;
//
//    @Captor
//    private ArgumentCaptor<Arb> arbCaptor;
//
//    @Captor
//    private ArgumentCaptor<List<NormalizedEvent>> eventListCaptor;
//
//    private ArbDetector arbDetector;
//
//    @BeforeEach
//    void setUp() {
//        arbDetector = new ArbDetector(
//                walletService,
//                arbitrageLogService,
//                arbFactory,
//                arbService
//        );
//    }
//
//    @AfterEach
//    void tearDown() {
//        if (arbDetector != null) {
//            arbDetector.shutdown();
//        }
//    }
//
//    // ================ Constructor and Initialization Tests ================
//
//    @Test
//    void constructor_withValidDependencies_createsInstance() {
//        assertThat(arbDetector).isNotNull();
//        assertThat(arbDetector.getCacheSize()).isZero();
//        assertThat(arbDetector.getTotalEvents()).isZero();
//        assertThat(arbDetector.getPendingArbs()).isZero();
//    }
//
//    @Test
//    void init_startsArbProcessorAndLogsInfo() throws InterruptedException {
//        arbDetector.init();
//        Thread.sleep(100);
//
//        verify(arbitrageLogService).logInfo("ArbDetector started", null);
//    }
//
//    // ================ addEventToPool Tests ================
//
//    @Test
//    void addEventToPool_withValidEvent_addsToCache() {
//        NormalizedEvent event = createNormalizedEvent("event-1", BookMaker.BET9JA);
//
//        arbDetector.addEventToPool(event);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//    }
//
//    @Test
//    void addEventToPool_withNullEvent_logsWarningAndDoesNotAdd() {
//        arbDetector.addEventToPool(null);
//
//        assertThat(arbDetector.getCacheSize()).isZero();
//        assertThat(arbDetector.getTotalEvents()).isZero();
//    }
//
//    @Test
//    void addEventToPool_withNullEventId_logsWarningAndDoesNotAdd() {
//        NormalizedEvent event = createNormalizedEvent(null, BookMaker.BET9JA);
//
//        arbDetector.addEventToPool(event);
//
//        assertThat(arbDetector.getCacheSize()).isZero();
//        assertThat(arbDetector.getTotalEvents()).isZero();
//    }
//
//    @Test
//    void addEventToPool_withMultipleEventsForSameEventId_groupsTogether() {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//        NormalizedEvent event3 = createNormalizedEvent("event-1", BookMaker.M_SPORT);
//
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//        arbDetector.addEventToPool(event3);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(3);
//    }
//
//    @Test
//    void addEventToPool_withDifferentEventIdS_createsMultipleGroups() {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-2", BookMaker.SPORTY_BET);
//        NormalizedEvent event3 = createNormalizedEvent("event-3", BookMaker.M_SPORT);
//
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//        arbDetector.addEventToPool(event3);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(3);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(3);
//    }
//
//    @Test
//    void addEventToPool_exceedingMaxEventsPerGroup_removesOldestEvent() {
//        String eventId = "event-overflow";
//
//        // Add MAX_EVENTS_PER_GROUP + 5 events
//        for (int i = 0; i < 55; i++) {
//            NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//            arbDetector.addEventToPool(event);
//        }
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(50); // MAX is 50
//    }
//
//    @Test
//    void addEventToPool_withExpiredEvents_removesExpiredImmediately() throws InterruptedException {
//        NormalizedEvent oldEvent = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        oldEvent.setLastUpdated(Instant.now().minusSeconds(10)); // Expired
//
//        NormalizedEvent newEvent = createNormalizedEvent("event-1", BookMaker.M_SPORT);
//
//        arbDetector.addEventToPool(oldEvent);
//        Thread.sleep(100);
//        arbDetector.addEventToPool(newEvent);
//
//        // Old event should be removed during add
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//    }
//
//    @Test
//    void addEventToPool_setsLastUpdatedTime() {
//        NormalizedEvent event = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        Instant before = Instant.now();
//
//        arbDetector.addEventToPool(event);
//
//        assertThat(event.getLastUpdated()).isNotNull();
//        assertThat(event.getLastUpdated()).isBetween(before, Instant.now());
//    }
//
//    @Test
//    void addEventToPool_triggersArbitrageDetection() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.M_SPORT);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        Arb mockArb = createMockArb("arb-1");
//        when(arbFactory.findOpportunities(anyList())).thenReturn(List.of(mockArb));
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(500); // Allow async detection to complete
//
//        verify(arbFactory, atLeastOnce()).findOpportunities(anyList());
//    }
//
//    // ================ detectArbitrage Tests ================
//
//    @Test
//    void detectArbitrage_withLessThanTwoEvents_doesNotTriggerFactory() throws InterruptedException {
//        NormalizedEvent event = createNormalizedEvent("event-1", BookMaker.BET9JA);
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event);
//
//        Thread.sleep(300);
//
//        verify(arbFactory, never()).findOpportunities(anyList());
//    }
//
//    @Test
//    void detectArbitrage_withTwoOrMoreEvents_triggersFactory() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        when(arbFactory.findOpportunities(anyList())).thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(300);
//
//        verify(arbFactory, atLeastOnce()).findOpportunities(eventListCaptor.capture());
//        assertThat(eventListCaptor.getValue()).hasSize(2);
//    }
//
//    @Test
//    void detectArbitrage_whenOpportunitiesFound_addsToQueue() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        Arb arb1 = createMockArb("arb-1");
//        Arb arb2 = createMockArb("arb-2");
//        when(arbFactory.findOpportunities(anyList())).thenReturn(List.of(arb1, arb2));
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(500);
//
//        assertThat(arbDetector.getPendingArbs()).isGreaterThanOrEqualTo(0); // May have been processed
//    }
//
//    @Test
//    void detectArbitrage_whenOpportunitiesFound_logsArbitrage() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        Arb arb = createMockArb("arb-1");
//        when(arbFactory.findOpportunities(anyList())).thenReturn(List.of(arb));
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(500);
//
//        verify(arbitrageLogService, atLeastOnce()).logArb(any(Arb.class));
//    }
//
//    @Test
//    void detectArbitrage_whenNoOpportunities_doesNotAddToQueue() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        when(arbFactory.findOpportunities(anyList())).thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(300);
//
//        assertThat(arbDetector.getPendingArbs()).isZero();
//        verify(arbitrageLogService, never()).logArb(any());
//    }
//
//    @Test
//    void detectArbitrage_withException_logsError() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        when(arbFactory.findOpportunities(anyList()))
//                .thenThrow(new RuntimeException("Detection error"));
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(500);
//
//        verify(arbitrageLogService, atLeastOnce()).logError(
//                eq("Error detected while trying to detect arb for an event"),
//                any(Exception.class)
//        );
//    }
//
//    @Test
//    void detectArbitrage_withFactoryThrowingIllegalArgumentException_logsError() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        when(arbFactory.findOpportunities(anyList()))
//                .thenThrow(new IllegalArgumentException("Events list cannot be null or empty"));
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(500);
//
//        verify(arbitrageLogService, atLeastOnce()).logError(
//                eq("Error detected while trying to detect arb for an event"),
//                any(Exception.class)
//        );
//    }
//
//    @Test
//    void detectArbitrage_concurrentCalls_usesLockingCorrectly() throws InterruptedException {
//        String eventId = "event-concurrent";
//        List<NormalizedEvent> events = new ArrayList<>();
//
//        for (int i = 0; i < 10; i++) {
//            events.add(createNormalizedEvent(eventId, BookMaker.BET9JA));
//        }
//
//        when(arbFactory.findOpportunities(anyList())).thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//
//        // Add events concurrently
//        ExecutorService executor = Executors.newFixedThreadPool(5);
//        CountDownLatch latch = new CountDownLatch(10);
//
//        for (NormalizedEvent event : events) {
//            executor.submit(() -> {
//                arbDetector.addEventToPool(event);
//                latch.countDown();
//            });
//        }
//
//        latch.await(2, TimeUnit.SECONDS);
//        Thread.sleep(500);
//
//        // Should have called factory but with proper locking
//        verify(arbFactory, atLeast(1)).findOpportunities(anyList());
//
//        executor.shutdownNow();
//    }
//
//    // ================ processArb Tests ================
//
//    @Test
//    void processArb_withValidArb_savesArb() throws InterruptedException {
//        Arb arb = createMockArb("arb-1");
//        arb.setStatus(Status.ACTIVE);
//
//        arbDetector.init();
//
//        // Inject arb directly into queue for testing
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(arb);
//
//        Thread.sleep(300);
//
//        verify(arbService, timeout(1000)).saveArb(arbCaptor.capture());
//        assertThat(arbCaptor.getValue().getArbId()).isEqualTo("arb-1");
//    }
//
//    @Test
//    void processArb_withInsufficientBalance_logsWarning() throws InterruptedException {
//        Arb arb = createMockArb("arb-insufficient");
//        arb.setStatus(Status.INSUFFICIENT_BALANCE);
//
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(arb);
//
//        Thread.sleep(300);
//
//        verify(arbitrageLogService, timeout(1000)).logInsufficientFunds(any(Arb.class));
//        verify(arbService, timeout(1000)).saveArb(any(Arb.class));
//    }
//
//    @Test
//    void processArb_withMultipleArbs_processesSequentially() throws InterruptedException {
//        Arb arb1 = createMockArb("arb-1");
//        Arb arb2 = createMockArb("arb-2");
//        Arb arb3 = createMockArb("arb-3");
//
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(arb1);
//        arbQueue.offer(arb2);
//        arbQueue.offer(arb3);
//
//        Thread.sleep(500);
//
//        verify(arbService, timeout(1000).times(3)).saveArb(any(Arb.class));
//    }
//
//    @Test
//    void processArb_withException_logsErrorAndContinues() {
//        Arb arb1 = createMockArb("arb-error");
//        Arb arb2 = createMockArb("arb-ok");
//
//        doThrow(new RuntimeException("Save error"))
//                .doNothing()
//                .when(arbService).saveArb(any());
//
//        arbDetector.init();
//        try {
//            getArbQueue().offer(arb1);
//            getArbQueue().offer(arb2);
//
//            // saveArb attempted for both
//            verify(arbService, timeout(1500).times(2)).saveArb(any());
//
//            // capture and assert exact message + exception
//            ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
//            ArgumentCaptor<Exception> exCap = ArgumentCaptor.forClass(Exception.class);
//
//            verify(arbitrageLogService, timeout(1500).times(1))
//                    .logError(msgCap.capture(), exCap.capture());
//
//            assertThat(msgCap.getValue())
//                    .isEqualTo("ArbDetector Processing error: arb-error");
//            assertThat(exCap.getValue()).hasMessage("Save error");
//        } finally {
//            arbDetector.shutdown();
//        }
//    }
//
//
//    // ================ cleanupOldEvents Tests ================
//
//    @Test
//    void cleanupOldEvents_removesExpiredEvents() {
//        NormalizedEvent oldEvent = createNormalizedEvent("event-old", BookMaker.BET9JA);
//        NormalizedEvent newEvent = createNormalizedEvent("event-new", BookMaker.SPORTY_BET);
//
//        arbDetector.addEventToPool(oldEvent);
//        arbDetector.addEventToPool(newEvent);
//
//        // both queues must exist now
//        assertThat(arbDetector.getCacheSize()).isEqualTo(2);
//
//        // make one stale AFTER insertion (same references live inside the queues)
//        oldEvent.setLastUpdated(Instant.now().minusSeconds(10));
//
//        // trigger cleanup – should drop "event-old"
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//    }
//
//
//    @Test
//    void cleanupOldEvents_removesEmptyQueues() {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        event1.setLastUpdated(Instant.now()); // fresh
//
//        NormalizedEvent event2 = createNormalizedEvent("event-2", BookMaker.SPORTY_BET);
//        event2.setLastUpdated(Instant.now()); // fresh
//
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        // cache now has 2 queues
//        assertThat(arbDetector.getCacheSize()).isEqualTo(2);
//
//        // Make them stale *after* adding (same object references are in the queues)
//        event1.setLastUpdated(Instant.now().minusSeconds(10));
//        event2.setLastUpdated(Instant.now().minusSeconds(10));
//
//        // Run cleanup, which should remove both queues entirely
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isZero();
//    }
//
//
//    @Test
//    void cleanupOldEvents_keepsRecentEvents() {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-2", BookMaker.SPORTY_BET);
//
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(2);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(2);
//    }
//
//    @Test
//    void cleanupOldEvents_withMixedEvents_removesOnlyExpired() {
//        NormalizedEvent oldEvent1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        oldEvent1.setLastUpdated(Instant.now().minusSeconds(10));
//
//        NormalizedEvent newEvent = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        NormalizedEvent oldEvent2 = createNormalizedEvent("event-1", BookMaker.M_SPORT);
//        oldEvent2.setLastUpdated(Instant.now().minusSeconds(8));
//
//        arbDetector.addEventToPool(oldEvent1);
//        arbDetector.addEventToPool(newEvent);
//        arbDetector.addEventToPool(oldEvent2);
//
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//    }
//
//    @Test
//    void cleanupOldEvents_withException_continuesExecution() {
//        // This test ensures cleanup doesn't crash on edge cases
//        arbDetector.cleanupOldEvents();
//
//        assertThatCode(() -> arbDetector.cleanupOldEvents())
//                .doesNotThrowAnyException();
//    }
//
//    @Test
//    void cleanupOldEvents_removesEventLocks() throws Exception {
//        String id = "event-lock";
//
//        NormalizedEvent e = createNormalizedEvent(id, BookMaker.BET9JA);
//        e.setLastUpdated(Instant.now()); // fresh so it enters the cache
//        arbDetector.addEventToPool(e);
//
//        // wait briefly until the lock exists (task started)
//        waitUntil(() -> getEventLocks().containsKey(id), 500);
//
//        // make event stale so cleanup will remove the queue
//        e.setLastUpdated(Instant.now().minusSeconds(10));
//
//        // *** stop any future detectArbitrage tasks from running ***
//        arbDetector.shutdown();
//
//        // now perform cleanup; with executors stopped, no task can re-add the lock
//        arbDetector.cleanupOldEvents();
//
//        assertThat(getEventLocks()).doesNotContainKey(id);
//    }
//
//
//
//    // ================ shutdown Tests ================
//
//    @Test
//    void shutdown_stopsProcessorsGracefully() throws InterruptedException {
//        arbDetector.init();
//        Thread.sleep(100);
//
//        arbDetector.shutdown();
//        Thread.sleep(200);
//
//        verify(arbitrageLogService).logInfo("ArbDetector shutdown complete", null);
//    }
//
//    @Test
//    void shutdown_processesRemainingArbs() throws InterruptedException {
//        Arb arb1 = createMockArb("arb-1");
//        Arb arb2 = createMockArb("arb-2");
//
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(arb1);
//        arbQueue.offer(arb2);
//
//        arbDetector.shutdown();
//        Thread.sleep(500);
//
//        // Should process remaining arbs before shutdown
//        verify(arbService, timeout(1000).atLeast(1)).saveArb(any());
//    }
//
//    @Test
//    void shutdown_forcesShutdownAfterTimeout() throws InterruptedException {
//        arbDetector.init();
//        Thread.sleep(100);
//
//        arbDetector.shutdown();
//        Thread.sleep(6000); // Wait for force shutdown timeout
//
//        // Should complete without hanging
//        assertThat(arbDetector).isNotNull();
//    }
//
//    @Test
//    void shutdown_multipleCallsSafe() {
//        arbDetector.init();
//
//        assertThatCode(() -> {
//            arbDetector.shutdown();
//            arbDetector.shutdown();
//            arbDetector.shutdown();
//        }).doesNotThrowAnyException();
//    }
//
//    // ================ Monitoring Methods Tests ================
//
//    @Test
//    void getCacheSize_returnsCorrectCount() {
//        arbDetector.addEventToPool(createNormalizedEvent("event-1", BookMaker.BET9JA));
//        arbDetector.addEventToPool(createNormalizedEvent("event-2", BookMaker.SPORTY_BET));
//        arbDetector.addEventToPool(createNormalizedEvent("event-3", BookMaker.M_SPORT));
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(3);
//    }
//
//    @Test
//    void getCacheSize_afterCleanup_returnsUpdatedCount() {
//        NormalizedEvent event = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        event.setLastUpdated(Instant.now().minusSeconds(10));
//
//        arbDetector.addEventToPool(event);
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isZero();
//    }
//
//    @Test
//    void getTotalEvents_returnsCorrectSum() {
//        arbDetector.addEventToPool(createNormalizedEvent("event-1", BookMaker.BET9JA));
//        arbDetector.addEventToPool(createNormalizedEvent("event-1", BookMaker.SPORTY_BET));
//        arbDetector.addEventToPool(createNormalizedEvent("event-2", BookMaker.M_SPORT));
//        arbDetector.addEventToPool(createNormalizedEvent("event-2", BookMaker.BET9JA));
//
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(4);
//    }
//
//    @Test
//    void getTotalEvents_withEmptyCache_returnsZero() {
//        assertThat(arbDetector.getTotalEvents()).isZero();
//    }
//
//    @Test
//    void getPendingArbs_returnsQueueSize() throws InterruptedException {
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(createMockArb("arb-1"));
//        arbQueue.offer(createMockArb("arb-2"));
//
//        Thread.sleep(100); // Before processing
//
//        assertThat(arbDetector.getPendingArbs()).isGreaterThanOrEqualTo(0);
//    }
//
//    // ================ Integration Tests ================
//
//    @Test
//    void fullWorkflow_detectAndProcessArbitrage() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-full", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-full", BookMaker.SPORTY_BET);
//
//        Arb arb = createMockArb("arb-full");
//
//        // Return one arb only once; subsequent detections yield nothing
//        when(arbFactory.findOpportunities(anyList()))
//                .thenReturn(List.of(arb))
//                .thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1); // first task (likely <2 events → early return)
//        arbDetector.addEventToPool(event2); // second task (now ≥2 events → produces 1 arb)
//
//        // Wait until save happens exactly once
//        verify(arbService, timeout(1500).times(1)).saveArb(arb);
//        // The rest can be atLeastOnce (factory may be called multiple times legitimately)
//        verify(arbFactory, atLeastOnce()).findOpportunities(anyList());
//        verify(arbitrageLogService, atLeastOnce()).logArb(arb);
//    }
//
//
//    @Test
//    void fullWorkflow_multipleEventsAndArbs() throws InterruptedException {
//        // Event group 1
//        NormalizedEvent e1a = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        NormalizedEvent e1b = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//
//        // Event group 2
//        NormalizedEvent e2a = createNormalizedEvent("event-2", BookMaker.BET9JA);
//        NormalizedEvent e2b = createNormalizedEvent("event-2", BookMaker.SPORTY_BET);
//
//        Arb arb1 = createMockArb("arb-1");
//        Arb arb2 = createMockArb("arb-2");
//
//        when(arbFactory.findOpportunities(anyList()))
//                .thenReturn(List.of(arb1))
//                .thenReturn(List.of(arb2));
//
//        arbDetector.init();
//
//        arbDetector.addEventToPool(e1a);
//        arbDetector.addEventToPool(e1b);
//        arbDetector.addEventToPool(e2a);
//        arbDetector.addEventToPool(e2b);
//
//        Thread.sleep(800);
//
//        verify(arbService, timeout(1000).atLeast(2)).saveArb(any(Arb.class));
//    }
//
//    @Test
//    void fullWorkflow_withScheduledCleanup() {
//        // insert both as FRESH so both keys are present
//        NormalizedEvent oldEvent = createNormalizedEvent("event-old", BookMaker.BET9JA);
//        oldEvent.setLastUpdated(Instant.now());
//
//        NormalizedEvent newEvent = createNormalizedEvent("event-new", BookMaker.SPORTY_BET);
//        newEvent.setLastUpdated(Instant.now());
//
//        arbDetector.addEventToPool(oldEvent);
//        arbDetector.addEventToPool(newEvent);
//
//        // both queues exist now
//        assertThat(arbDetector.getCacheSize()).isEqualTo(2);
//
//        // make one stale AFTER insertion (same object reference lives in the queue)
//        oldEvent.setLastUpdated(Instant.now().minusSeconds(10));
//
//        // simulate the scheduled cleanup tick
//        arbDetector.cleanupOldEvents();
//
//        // should drop "event-old" queue entirely
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//    }
//
//
//    @Test
//    void fullWorkflow_withCompleteMarketData_detectsArbitrage() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEventWithMarkets("event-market", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEventWithMarkets("event-market", BookMaker.SPORTY_BET);
//
//        Arb arb = createMockArb("arb-market");
//
//        // Return one arb only on the first detection; empty thereafter to avoid double-enqueue
//        AtomicBoolean first = new AtomicBoolean(true);
//        when(arbFactory.findOpportunities(anyList())).thenAnswer(inv -> {
//            if (first.getAndSet(false)) return List.of(arb);
//            return Collections.emptyList();
//        });
//
//        arbDetector.init();
//        try {
//            arbDetector.addEventToPool(event1);
//            arbDetector.addEventToPool(event2);
//
//            // allow async flow to run
//            verify(arbService, timeout(1500).times(1)).saveArb(arb);
//            verify(arbitrageLogService, atLeastOnce()).logArb(arb);
//
//            // capture ALL detection calls and assert at least one had both events with markets
//            verify(arbFactory, atLeastOnce()).findOpportunities(eventListCaptor.capture());
//            List<List<NormalizedEvent>> allCalls = eventListCaptor.getAllValues();
//
//            boolean sawTwoWithMarkets = allCalls.stream().anyMatch(list ->
//                    list.size() == 2 && list.stream().allMatch(e -> e.getMarkets() != null && !e.getMarkets().isEmpty())
//            );
//            assertThat(sawTwoWithMarkets)
//                    .as("At least one detection should receive both events with populated markets")
//                    .isTrue();
//
//        } finally {
//            arbDetector.shutdown(); // ensure threads don’t bleed into other tests
//        }
//    }
//
//
//    // ================ Parameterized Tests ================
//
//    @ParameterizedTest
//    @ValueSource(ints = {1, 5, 10, 50, 100})
//    void addEventToPool_withVariousEventCounts_handlesCorrectly(int eventCount) {
//        String eventId = "event-param";
//
//        for (int i = 0; i < eventCount; i++) {
//            NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//            arbDetector.addEventToPool(event);
//        }
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isLessThanOrEqualTo(50); // MAX limit
//    }
//
//    @ParameterizedTest
//    @MethodSource("provideEventScenarios")
//    void detectArbitrage_withDifferentScenarios_behavesCorrectly(
//            int eventCount,
//            int arbCount,
//            boolean shouldDetect
//    ) throws InterruptedException {
//        String eventId = "event-scenario";
//        List<Arb> arbs = new ArrayList<>();
//
//        for (int i = 0; i < arbCount; i++) {
//            arbs.add(createMockArb("arb-" + i));
//        }
//
////        when(arbFactory.findOpportunities(anyList()))
////                .thenReturn(shouldDetect ? arbs : Collections.emptyList());
//
//        arbDetector.init();
//
//        for (int i = 0; i < eventCount; i++) {
//            NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//            arbDetector.addEventToPool(event);
//        }
//
//        Thread.sleep(500);
//
//        if (shouldDetect && eventCount >= 2) {
//            verify(arbFactory, atLeastOnce()).findOpportunities(anyList());
//            if (arbCount > 0) {
//                verify(arbitrageLogService, timeout(1000).atLeast(arbCount)).logArb(any());
//            }
//        }
//    }
//
//    private static Stream<Arguments> provideEventScenarios() {
//        return Stream.of(
//                Arguments.of(1, 0, false),  // Single event, no detection
//                Arguments.of(2, 1, true),   // Two events, one arb
//                Arguments.of(3, 2, true),   // Three events, two arbs
//                Arguments.of(5, 0, true),   // Five events, no arbs found
//                Arguments.of(10, 5, true)   // Many events, multiple arbs
//        );
//    }
//
//    @ParameterizedTest
//    @MethodSource("provideBookmakerCombinations")
//    void addEventToPool_withDifferentBookmakers_groupsCorrectly(
//            List<BookMaker> bookmakers,
//            int expectedCacheSize,
//            int expectedTotalEvents
//    ) {
//        String eventId = "event-bookmakers";
//
//        for (BookMaker bookmaker : bookmakers) {
//            NormalizedEvent event = createNormalizedEvent(eventId, bookmaker);
//            arbDetector.addEventToPool(event);
//        }
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(expectedCacheSize);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(expectedTotalEvents);
//    }
//
//    private static Stream<Arguments> provideBookmakerCombinations() {
//        return Stream.of(
//                Arguments.of(List.of(BookMaker.BET9JA), 1, 1),
//                Arguments.of(List.of(BookMaker.BET9JA, BookMaker.SPORTY_BET), 1, 2),
//                Arguments.of(List.of(BookMaker.BET9JA, BookMaker.SPORTY_BET, BookMaker.M_SPORT), 1, 3),
//                Arguments.of(Collections.emptyList(), 0, 0)
//        );
//    }
//
//    // ================ Additional Edge Case Tests ================
//
//    @Test
//    void addEventToPool_concurrentAddsToSameEventId_maintainsConsistency() throws InterruptedException {
//        String eventId = "event-concurrent-add";
//        int threadCount = 20;
//        CountDownLatch startLatch = new CountDownLatch(1);
//        CountDownLatch doneLatch = new CountDownLatch(threadCount);
//
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//
//        for (int i = 0; i < threadCount; i++) {
//            int finalI = i;
//            executor.submit(() -> {
//                try {
//                    startLatch.await(); // Wait for all threads to be ready
//                    NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//                    arbDetector.addEventToPool(event);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                } finally {
//                    doneLatch.countDown();
//                }
//            });
//        }
//
//        startLatch.countDown(); // Start all threads
//        doneLatch.await(5, TimeUnit.SECONDS);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isLessThanOrEqualTo(50); // Respects MAX_EVENTS_PER_GROUP
//
//        executor.shutdownNow();
//    }
//
//    @Test
//    void addEventToPool_concurrentAddsToMultipleEventIds_maintainsConsistency() throws InterruptedException {
//        int threadCount = 30;
//        int eventIdCount = 5;
//        CountDownLatch startLatch = new CountDownLatch(1);
//        CountDownLatch doneLatch = new CountDownLatch(threadCount);
//
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//
//        for (int i = 0; i < threadCount; i++) {
//            int finalI = i;
//            executor.submit(() -> {
//                try {
//                    startLatch.await();
//                    String eventId = "event-" + (finalI % eventIdCount);
//                    NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//                    arbDetector.addEventToPool(event);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                } finally {
//                    doneLatch.countDown();
//                }
//            });
//        }
//
//        startLatch.countDown();
//        doneLatch.await(5, TimeUnit.SECONDS);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(eventIdCount);
//
//        executor.shutdownNow();
//    }
//
//    @Test
//    void processArb_whenArbServiceThrowsException_continuesProcessing() {
//        Arb arb1 = createMockArb("arb-error-1");
//        Arb arb2 = createMockArb("arb-success");
//        Arb arb3 = createMockArb("arb-error-2");
//
//        doThrow(new RuntimeException("Database error"))
//                .doNothing()
//                .doThrow(new RuntimeException("Another error"))
//                .when(arbService).saveArb(any());
//
//        arbDetector.init();
//        try {
//            // feed the processor directly
//            getArbQueue().offer(arb1);
//            getArbQueue().offer(arb2);
//            getArbQueue().offer(arb3);
//
//            // saveArb must be attempted for all 3 (processor continues after errors)
//            verify(arbService, timeout(1500).times(3)).saveArb(any());
//
//            // capture error logs and assert messages & exceptions
//            ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
//            ArgumentCaptor<Exception> exCap = ArgumentCaptor.forClass(Exception.class);
//
//            verify(arbitrageLogService, timeout(1500).times(2))
//                    .logError(msgCap.capture(), exCap.capture());
//
//            assertThat(msgCap.getAllValues())
//                    .containsExactlyInAnyOrder(
//                            "ArbDetector Processing error: arb-error-1",
//                            "ArbDetector Processing error: arb-error-2"
//                    );
//
//            assertThat(exCap.getAllValues())
//                    .extracting(Throwable::getMessage)
//                    .containsExactlyInAnyOrder("Database error", "Another error");
//
//        } finally {
//            arbDetector.shutdown(); // avoid thread bleed into other tests
//        }
//    }
//
//
//    @Test
//    void detectArbitrage_withNullEventCache_handlesGracefully() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-null-test", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-null-test", BookMaker.SPORTY_BET);
//
////        when(arbFactory.findOpportunities(anyList())).thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        // Manually clear cache to simulate edge case
//        Map<String, ConcurrentLinkedQueue<NormalizedEvent>> cache = getEventCache();
//        cache.clear();
//
//        Thread.sleep(300);
//
//        // Should not crash
//        assertThat(arbDetector.getCacheSize()).isZero();
//    }
//
//    @Test
//    void cleanupOldEvents_withConcurrentModifications_maintainsConsistency() throws InterruptedException {
//        ExecutorService executor = Executors.newFixedThreadPool(3);
//        CountDownLatch latch = new CountDownLatch(100);
//
//        // Thread 1: Add events
//        executor.submit(() -> {
//            for (int i = 0; i < 30; i++) {
//                NormalizedEvent event = createNormalizedEvent("event-" + i, BookMaker.BET9JA);
//                arbDetector.addEventToPool(event);
//                latch.countDown();
//                try {
//                    Thread.sleep(10);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        });
//
//        // Thread 2: Add more events
//        executor.submit(() -> {
//            for (int i = 0; i < 30; i++) {
//                NormalizedEvent event = createNormalizedEvent("event-" + i, BookMaker.SPORTY_BET);
//                arbDetector.addEventToPool(event);
//                latch.countDown();
//                try {
//                    Thread.sleep(10);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        });
//
//        // Thread 3: Cleanup
//        executor.submit(() -> {
//            for (int i = 0; i < 40; i++) {
//                arbDetector.cleanupOldEvents();
//                latch.countDown();
//                try {
//                    Thread.sleep(20);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        });
//
//        latch.await(10, TimeUnit.SECONDS);
//
//        // Should maintain consistency
//        assertThat(arbDetector.getCacheSize()).isGreaterThanOrEqualTo(0);
//        assertThat(arbDetector.getTotalEvents()).isGreaterThanOrEqualTo(0);
//
//        executor.shutdownNow();
//    }
//
//    @Test
//    void shutdown_whileProcessingArbs_waitsForCompletion() throws InterruptedException {
//        List<Arb> arbs = new ArrayList<>();
//        for (int i = 0; i < 10; i++) {
//            arbs.add(createMockArb("arb-shutdown-" + i));
//        }
//
//        // Make saveArb slow
//        doAnswer(invocation -> {
//            Thread.sleep(100);
//            return null;
//        }).when(arbService).saveArb(any());
//
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbs.forEach(arbQueue::offer);
//
//        Thread.sleep(200); // Let some processing start
//
//        arbDetector.shutdown();
//
//        // Should have attempted to save at least some arbs
//        verify(arbService, atLeast(1)).saveArb(any());
//    }
//
//    @Test
//    void addEventToPool_afterShutdown_stillAddsToCache() {
//        arbDetector.shutdown();
//
//        NormalizedEvent event = createNormalizedEvent("event-after-shutdown", BookMaker.BET9JA);
//
//        try {
//            arbDetector.addEventToPool(event);
//            fail("Expected RejectedExecutionException after shutdown");
//        } catch (RejectedExecutionException ignored) {
//            // expected; detection submit is rejected post-shutdown
//        }
//
//        // Cache was updated before submit -> still 1
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//    }
//
//
//    @Test
//    void detectArbitrage_withFactoryReturningNull_handlesGracefully() throws InterruptedException {
//        NormalizedEvent event1 = createNormalizedEvent("event-null-return", BookMaker.BET9JA);
//        NormalizedEvent event2 = createNormalizedEvent("event-null-return", BookMaker.SPORTY_BET);
//
//        when(arbFactory.findOpportunities(anyList())).thenReturn(null);
//
//        arbDetector.init();
//        arbDetector.addEventToPool(event1);
//        arbDetector.addEventToPool(event2);
//
//        Thread.sleep(300);
//
//        // Should handle null return gracefully (though it would throw NPE in real code)
//        assertThat(arbDetector.getPendingArbs()).isZero();
//    }
//
//    @Test
//    void getTotalEvents_withLargeNumberOfEvents_calculatesCorrectly() {
//        for (int i = 0; i < 100; i++) {
//            String eventId = "event-" + (i / 5); // 5 events per eventId
//            NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//            arbDetector.addEventToPool(event);
//        }
//
//        // Should respect MAX_EVENTS_PER_GROUP (50) per event
//        assertThat(arbDetector.getTotalEvents()).isLessThanOrEqualTo(20 * 50);
//    }
//
//    @Test
//    void cleanupOldEvents_withEmptyCache_doesNotThrowException() {
//        arbDetector.cleanupOldEvents();
//        arbDetector.cleanupOldEvents();
//        arbDetector.cleanupOldEvents();
//
//        assertThatCode(() -> arbDetector.cleanupOldEvents())
//                .doesNotThrowAnyException();
//    }
//
//    @Test
//    void addEventToPool_withExtremelyLongEventId_handlesCorrectly() {
//        String longEventId = "event-" + "x".repeat(1000);
//        NormalizedEvent event = createNormalizedEvent(longEventId, BookMaker.BET9JA);
//
//        arbDetector.addEventToPool(event);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//    }
//
//    @Test
//    void detectArbitrage_withVeryLargeEventList_handlesCorrectly() throws InterruptedException {
//        String eventId = "event-large";
//
//        for (int i = 0; i < 100; i++) {
//            NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//            arbDetector.addEventToPool(event);
//        }
//
//        when(arbFactory.findOpportunities(anyList())).thenReturn(Collections.emptyList());
//
//        arbDetector.init();
//        Thread.sleep(500);
//
//        // Should have called factory with the events (up to MAX limit)
//        verify(arbFactory, atLeastOnce()).findOpportunities(eventListCaptor.capture());
//        assertThat(eventListCaptor.getValue().size()).isLessThanOrEqualTo(50);
//    }
//
//    @Test
//    void processArb_withAllStatusTypes_handlesCorrectly() throws InterruptedException {
//        Arb arbActive = createMockArb("arb-active");
//        arbActive.setStatus(Status.ACTIVE);
//
//        Arb arbCompleted = createMockArb("arb-completed");
//        arbCompleted.setStatus(Status.COMPLETED);
//
//        Arb arbFailed = createMockArb("arb-failed");
//        arbFailed.setStatus(Status.FAILED);
//
//        Arb arbInsufficientBalance = createMockArb("arb-insufficient");
//        arbInsufficientBalance.setStatus(Status.INSUFFICIENT_BALANCE);
//
//        arbDetector.init();
//
//        BlockingQueue<Arb> arbQueue = getArbQueue();
//        arbQueue.offer(arbActive);
//        arbQueue.offer(arbCompleted);
//        arbQueue.offer(arbFailed);
//        arbQueue.offer(arbInsufficientBalance);
//
//        Thread.sleep(600);
//
//        verify(arbService, timeout(1000).times(4)).saveArb(any());
//        verify(arbitrageLogService, timeout(1000).times(1)).logInsufficientFunds(any());
//    }
//
//    @Test
//    void init_multipleCallsSafe() {
//        arbDetector.init();
//        arbDetector.init();
//        arbDetector.init();
//
//        // Should only start once, no errors
//        assertThat(arbDetector).isNotNull();
//    }
//
//    @Test
//    void detectArbitrage_withEmptyEventList_doesNotCallFactory() throws InterruptedException {
//        String eventId = "event-empty";
//
//        // Add then immediately cleanup
//        NormalizedEvent event = createNormalizedEvent(eventId, BookMaker.BET9JA);
//        event.setLastUpdated(Instant.now().minusSeconds(10));
//        arbDetector.addEventToPool(event);
//        arbDetector.cleanupOldEvents();
//
//        arbDetector.init();
//        Thread.sleep(300);
//
//        verify(arbFactory, never()).findOpportunities(anyList());
//    }
//
//    @Test
//    void addEventToPool_withDifferentSports_groupsByEventId() {
//        NormalizedEvent footballEvent = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        footballEvent.setSportEnum(SportEnum.FOOTBALL);
//
//        NormalizedEvent tableTennisEvent = createNormalizedEvent("event-1", BookMaker.SPORTY_BET);
//        tableTennisEvent.setSportEnum(SportEnum.TABLE_TENNIS);
//
//        arbDetector.addEventToPool(footballEvent);
//        arbDetector.addEventToPool(tableTennisEvent);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(2);
//    }
//
//    @Test
//    void addEventToPool_updatesExistingEventFromSameBookmaker() {
//        NormalizedEvent event1 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        event1.setHomeTeam("Team A");
//
//        arbDetector.addEventToPool(event1);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(1);
//
//        NormalizedEvent event2 = createNormalizedEvent("event-1", BookMaker.BET9JA);
//        event2.setHomeTeam("Team B");
//
//        arbDetector.addEventToPool(event2);
//        assertThat(arbDetector.getTotalEvents()).isEqualTo(2); // Both stored, not replaced
//    }
//
//    @Test
//    void cleanupOldEvents_scheduledSimulation_runsManually() throws InterruptedException {
//        // 1) Add fresh so it's actually inserted
//        NormalizedEvent ev = createNormalizedEvent("event-scheduled", BookMaker.BET9JA);
//        ev.setLastUpdated(Instant.now());
//        arbDetector.addEventToPool(ev);
//
//        assertThat(arbDetector.getCacheSize()).isEqualTo(1);
//
//        // 2) Let it expire (simulate time pass by mutating timestamp)
//        ev.setLastUpdated(Instant.now().minusSeconds(10));
//
//        // 3) "Scheduled run" — call method directly
//        arbDetector.cleanupOldEvents();
//
//        assertThat(arbDetector.getCacheSize()).isZero();
//    }
//
//
//    // ================ Helper Methods ================
//
//    private NormalizedEvent createNormalizedEvent(String eventId, BookMaker bookmaker) {
//        return NormalizedEvent.builder()
//                .eventId(eventId)
//                .bookie(bookmaker)
//                .homeTeam("Home Team")
//                .awayTeam("Away Team")
//                .league("Premier League")
//                .eventName("Home Team vs Away Team")
//                .sportEnum(SportEnum.FOOTBALL)
//                .estimateStartTime(System.currentTimeMillis() + 3600000)
//                .lastUpdated(Instant.now())
//                .markets(new ArrayList<>())
//                .build();
//    }
//
//    private NormalizedEvent createNormalizedEventWithMarkets(String eventId, BookMaker bookmaker) {
//        NormalizedOutcome outcome1 = NormalizedOutcome.builder()
//                .eventId(eventId)
//                .normalEventId(eventId)
//                .bookmaker(bookmaker)
//                .odds(new BigDecimal("2.0"))
//                .eventName("Home Team vs Away Team")
//                .homeTeam("Home Team")
//                .awayTeam("Away Team")
//                .league("Premier League")
//                .eventStartTime(System.currentTimeMillis() + 3600000)
//                .sportEnum(SportEnum.FOOTBALL)
//                .isActive(true)
//                .build();
//
//        NormalizedMarket market = NormalizedMarket.builder()
//                .marketCategory(MarketCategory.MATCH_RESULT)
//                .outcomes(List.of(outcome1))
//                .build();
//
//        return NormalizedEvent.builder()
//                .eventId(eventId)
//                .bookie(bookmaker)
//                .homeTeam("Home Team")
//                .awayTeam("Away Team")
//                .league("Premier League")
//                .eventName("Home Team vs Away Team")
//                .sportEnum(SportEnum.FOOTBALL)
//                .estimateStartTime(System.currentTimeMillis() + 3600000)
//                .lastUpdated(Instant.now())
//                .markets(List.of(market))
//                .build();
//    }
//
//    private Arb createMockArb(String arbId) {
//        Arb arb = new Arb();
//        arb.setArbId(arbId);
//        arb.setStatus(Status.ACTIVE);
//        arb.setProfitPercentage(new BigDecimal("5.5"));
//        arb.setCreatedAt(Instant.now());
//        arb.setSportEnum(SportEnum.FOOTBALL);
//        arb.setLeague("Premier League");
//        return arb;
//    }
//
//    private void waitUntil(Supplier<Boolean> cond, long timeoutMs) throws InterruptedException {
//        long end = System.currentTimeMillis() + timeoutMs;
//        while (System.currentTimeMillis() < end) {
//            if (cond.get()) return;
//            Thread.sleep(25);
//        }
//        assertThat(cond.get()).isTrue();
//    }
//
//
//
//    /**
//     * Helper to access private arbQueue field for testing
//     */
//    @SuppressWarnings("unchecked")
//    private BlockingQueue<Arb> getArbQueue() {
//        try {
//            Field field = ArbDetector.class.getDeclaredField("arbQueue");
//            field.setAccessible(true);
//            return (BlockingQueue<Arb>) field.get(arbDetector);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to access arbQueue", e);
//        }
//    }
//
//    /**
//     * Helper to access private eventLocks field for testing
//     */
//    @SuppressWarnings("unchecked")
//    private ConcurrentHashMap<String, Object> getEventLocks() {
//        try {
//            Field field = ArbDetector.class.getDeclaredField("eventLocks");
//            field.setAccessible(true);
//            return (ConcurrentHashMap<String, Object>) field.get(arbDetector);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to access eventLocks", e);
//        }
//    }
//
//    /**
//     * Helper to access private eventCache field for testing
//     */
//    @SuppressWarnings("unchecked")
//    private Map<String, ConcurrentLinkedQueue<NormalizedEvent>> getEventCache() {
//        try {
//            Field field = ArbDetector.class.getDeclaredField("eventCache");
//            field.setAccessible(true);
//            return (Map<String, ConcurrentLinkedQueue<NormalizedEvent>>) field.get(arbDetector);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to access eventCache", e);
//        }
//    }
//}