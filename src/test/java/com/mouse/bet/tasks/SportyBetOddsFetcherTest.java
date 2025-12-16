//package com.mouse.bet.tasks;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.microsoft.playwright.*;
//import com.microsoft.playwright.options.Cookie;
//import com.mouse.bet.config.ScraperConfig;
//import com.mouse.bet.detector.ArbDetector;
//import com.mouse.bet.enums.BookMaker;
//import com.mouse.bet.manager.ProfileManager;
//import com.mouse.bet.model.NormalizedEvent;
//import com.mouse.bet.model.profile.UserAgentProfile;
//import com.mouse.bet.model.sporty.SportyEvent;
//import com.mouse.bet.service.BetLegRetryService;
//import com.mouse.bet.service.SportyBetService;
//import com.mouse.bet.utils.JsonParser;
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
//import org.mockito.MockedStatic;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.lang.reflect.Field;
//import java.lang.reflect.InvocationTargetException;
//import java.lang.reflect.Method;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.function.Consumer;
//import java.util.stream.Stream;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//public class SportyBetOddsFetcherTest {
//
//    @Mock
//    private ScraperConfig scraperConfig;
//
//    @Mock
//    private ProfileManager profileManager;
//
//    @Mock
//    private SportyBetService sportyBetService;
//
//    @Mock
//    private BetLegRetryService betLegRetryService;
//
//    @Mock
//    private ArbDetector arbDetector;
//
//    @Mock
//    private ObjectMapper objectMapper;
//
//    @Mock
//    private Playwright playwright;
//
//    @Mock
//    private Browser browser;
//
//    @Mock
//    private BrowserContext browserContext;
//
//    @Mock
//    private Page page;
//
//    @Mock
//    private APIRequestContext apiRequestContext;
//
//    @Mock
//    private APIResponse apiResponse;
//
//    @Captor
//    private ArgumentCaptor<NormalizedEvent> normalizedEventCaptor;
//
//    @Captor
//    private ArgumentCaptor<Map<String, String>> headersCaptor;
//
//    private SportyBetOddsFetcher fetcher;
//    private UserAgentProfile mockProfile;
//
//    @BeforeEach
//    void setUp() {
//        mockProfile = createMockUserAgentProfile();
//
////        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
//
//        fetcher = new SportyBetOddsFetcher(
//                scraperConfig,
//                profileManager,
//                sportyBetService,
//                betLegRetryService,
//                arbDetector,
//                objectMapper
//        );
//    }
//
//    @AfterEach
//    void tearDown() {
//        // Cleanup any running threads
//    }
//
//    // ================ Constructor Tests ================
//
//    @Test
//    void constructor_withValidDependencies_createsInstance() {
//        assertThat(fetcher).isNotNull();
//    }
//
////    @Test
////    void constructor_withNullScraperConfig_throwsNullPointerException() {
////        assertThatThrownBy(() -> new SportyBetOddsFetcher(
////                null,
////                profileManager,
////                sportyBetService,
////                betLegRetryService,
////                arbDetector,
////                objectMapper
////        )).isInstanceOf(NullPointerException.class);
////    }
//
//    // ================ launchBrowser Tests ================
//
//    @Test
//    void launchBrowser_withValidPlaywright_launchesBrowser() throws Exception {
//        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
//        when(playwright.chromium()).thenReturn(mock(BrowserType.class));
//        when(playwright.chromium().launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("launchBrowser", Playwright.class);
//        method.setAccessible(true);
//        Browser result = (Browser) method.invoke(fetcher, playwright);
//
//        assertThat(result).isNotNull();
//        verify(playwright.chromium()).launch(any(BrowserType.LaunchOptions.class));
//    }
//
//    @Test
//    void launchBrowser_usesConfiguredBrowserFlags_customFlagsPassed_timesTwoCallsOK() throws Exception {
//        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
////        List<String> customFlags = List.of("--flag1", "--flag2");
////        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(customFlags);
//        when(playwright.chromium()).thenReturn(mock(BrowserType.class));
//        when(playwright.chromium().launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("launchBrowser", Playwright.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, playwright);
//
//        verify(scraperConfig, times(2)).getBROWSER_FlAGS();
//    }
//
//    // ================ newContext Tests ================
//
//    @Test
//    void newContext_withValidProfile_createsBrowserContext() throws Exception {
////        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
//        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(browserContext);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "newContext", Browser.class, UserAgentProfile.class);
//        method.setAccessible(true);
//        BrowserContext result = (BrowserContext) method.invoke(fetcher, browser, mockProfile);
//
//        assertThat(result).isNotNull();
//        verify(browser).newContext(any(Browser.NewContextOptions.class));
//    }
//
//    @Test
//    void newContext_setsUserAgentFromProfile() throws Exception {
////        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
//
//        ArgumentCaptor<Browser.NewContextOptions> optionsCaptor =
//                ArgumentCaptor.forClass(Browser.NewContextOptions.class);
//        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(browserContext);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "newContext", Browser.class, UserAgentProfile.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, browser, mockProfile);
//
//        verify(browser).newContext(optionsCaptor.capture());
//        // Verify options were set (indirectly through invocation)
//        assertThat(optionsCaptor.getValue()).isNotNull();
//    }
//
//    // ================ getAllHeaders Tests ================
//
//    @Test
//    void getAllHeaders_withStandardAndClientHints_mergesAllHeaders() throws Exception {
////        when(scraperConfig.getBROWSER_FlAGS()).thenReturn(List.of("--no-sandbox", "--disable-setuid-sandbox"));
////        when(profileManager.getNextProfile()).thenReturn(mockProfile);
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "getAllHeaders", UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(fetcher, mockProfile);
//
//        assertThat(result).isNotEmpty();
//        assertThat(result).containsKeys("Accept", "Accept-Language");
//    }
//
//    @Test
//    void getAllHeaders_withNullStandardHeaders_handlesGracefully() throws Exception {
//        UserAgentProfile profileWithNullHeaders = createMockUserAgentProfile();
//        profileWithNullHeaders.getHeaders().setStandardHeaders(null);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "getAllHeaders", UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(fetcher, profileWithNullHeaders);
//
//        assertThat(result).isNotNull();
//    }
//
//    @Test
//    void getAllHeaders_withNullClientHints_handlesGracefully() throws Exception {
//        UserAgentProfile profileWithNullHints = createMockUserAgentProfile();
//        profileWithNullHints.getHeaders().setClientHintsHeaders(null);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "getAllHeaders", UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(fetcher, profileWithNullHints);
//
//        assertThat(result).isNotNull();
//    }
//
//    // ================ attachAntiDetection Tests ================
//
//    @Test
//    void attachAntiDetection_addsInitScriptToContext() throws Exception {
//        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachAntiDetection", BrowserContext.class, UserAgentProfile.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, browserContext, mockProfile);
//
//        verify(browserContext).addInitScript(scriptCaptor.capture());
//        String script = scriptCaptor.getValue();
//
//        assertThat(script).contains("navigator");
//        assertThat(script).contains("webdriver");
//        assertThat(script).contains("userAgentData");
//    }
//
//    @Test
//    void attachAntiDetection_scriptContainsCanvasProtection() throws Exception {
//        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachAntiDetection", BrowserContext.class, UserAgentProfile.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, browserContext, mockProfile);
//
//        verify(browserContext).addInitScript(scriptCaptor.capture());
//        assertThat(scriptCaptor.getValue()).contains("HTMLCanvasElement");
//        assertThat(scriptCaptor.getValue()).contains("getContext");
//    }
//
//    @Test
//    void attachAntiDetection_scriptContainsWebGLProtection() throws Exception {
//        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachAntiDetection", BrowserContext.class, UserAgentProfile.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, browserContext, mockProfile);
//
//        verify(browserContext).addInitScript(scriptCaptor.capture());
//        assertThat(scriptCaptor.getValue()).contains("WebGLRenderingContext");
//        assertThat(scriptCaptor.getValue()).contains("getParameter");
//    }
//
//    // ================ attachNetworkTaps Tests ================
//
//    @Test
//    void attachNetworkTaps_capturesAuthorizationHeaders() throws Exception {
//        Map<String, String> store = new ConcurrentHashMap<>();
//        Map<String, String> responseHeaders = Map.of(
//                "authorization", "Bearer token123",
//                "content-type", "application/json"
//        );
//
////        when(apiResponse.url()).thenReturn("https://api.sportybet.com/prematch");
////        when(apiResponse.status()).thenReturn(200);
////        when(apiResponse.headers()).thenReturn(responseHeaders);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachNetworkTaps", Page.class, Map.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, page, store);
//
//        // Simulate response callback
//        ArgumentCaptor<Consumer<Response>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);
//        verify(page).onResponse(callbackCaptor.capture());
//
//        // Mock Response
//        Response mockResponse = mock(Response.class);
//        when(mockResponse.url()).thenReturn("https://api.sportybet.com/prematch");
//        when(mockResponse.status()).thenReturn(200);
//        when(mockResponse.headers()).thenReturn(responseHeaders);
//
//        callbackCaptor.getValue().accept(mockResponse);
//
//        assertThat(store).containsKey("authorization");
//        assertThat(store.get("authorization")).isEqualTo("Bearer token123");
//    }
//
//    @Test
//    void attachNetworkTaps_capturesCsrfToken() throws Exception {
//        Map<String, String> store = new ConcurrentHashMap<>();
//        Map<String, String> responseHeaders = Map.of(
//                "x-csrf-token", "csrf-token-value",
//                "content-type", "application/json"
//        );
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachNetworkTaps", Page.class, Map.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, page, store);
//
//        ArgumentCaptor<Consumer<Response>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);
//        verify(page).onResponse(callbackCaptor.capture());
//
//        Response mockResponse = mock(Response.class);
//        when(mockResponse.url()).thenReturn("https://api.sportybet.com/odds");
//        when(mockResponse.status()).thenReturn(200);
//        when(mockResponse.headers()).thenReturn(responseHeaders);
//
//        callbackCaptor.getValue().accept(mockResponse);
//
//        assertThat(store).containsKey("x-csrf-token");
//    }
//
//    @Test
//    void attachNetworkTaps_ignoresNonPrematchUrls() throws Exception {
//        Map<String, String> store = new ConcurrentHashMap<>();
//        Map<String, String> responseHeaders = Map.of(
//                "authorization", "Bearer token123"
//        );
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachNetworkTaps", Page.class, Map.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, page, store);
//
//        ArgumentCaptor<Consumer<Response>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);
//        verify(page).onResponse(callbackCaptor.capture());
//
//        Response mockResponse = mock(Response.class);
//        when(mockResponse.url()).thenReturn("https://api.other.com/data");
//        when(mockResponse.status()).thenReturn(200);
////        when(mockResponse.headers()).thenReturn(responseHeaders);
//
//        callbackCaptor.getValue().accept(mockResponse);
//
//        assertThat(store).isEmpty();
//    }
//
//    @Test
//    void attachNetworkTaps_ignoresErrorResponses() throws Exception {
//        Map<String, String> store = new ConcurrentHashMap<>();
//        Map<String, String> responseHeaders = Map.of(
//                "authorization", "Bearer token123"
//        );
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "attachNetworkTaps", Page.class, Map.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, page, store);
//
//        ArgumentCaptor<Consumer<Response>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);
//        verify(page).onResponse(callbackCaptor.capture());
//
//        Response mockResponse = mock(Response.class);
//        when(mockResponse.url()).thenReturn("https://api.sportybet.com/prematch");
//        when(mockResponse.status()).thenReturn(500);
////        when(mockResponse.headers()).thenReturn(responseHeaders);
//
//        callbackCaptor.getValue().accept(mockResponse);
//
//        assertThat(store).isEmpty();
//    }
//
//    // ================ performInitialNavigationWithRetry Tests ================
//
//    @Test
//    void performInitialNavigationWithRetry_successfulNavigation_completesImmediately() throws Exception {
//        when(page.navigate(anyString(), any(Page.NavigateOptions.class))).thenReturn(null);
//        when(page.waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class))).thenReturn(null);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "performInitialNavigationWithRetry", Page.class);
//        method.setAccessible(true);
//
//        assertThatCode(() -> method.invoke(fetcher, page)).doesNotThrowAnyException();
//        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
//    }
//
//    @Test
//    void performInitialNavigationWithRetry_failsOnce_retriesAndSucceeds() throws Exception {
//        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
//                .thenThrow(new PlaywrightException("Timeout"))
//                .thenReturn(null);
//        when(page.waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class))).thenReturn(null);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "performInitialNavigationWithRetry", Page.class);
//        method.setAccessible(true);
//
//        assertThatCode(() -> method.invoke(fetcher, page)).doesNotThrowAnyException();
//        verify(page, times(2)).navigate(anyString(), any(Page.NavigateOptions.class));
//    }
//
//    @Test
//    void performInitialNavigationWithRetry_failsThreeTimes_throwsException() throws Exception {
//        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
//                .thenThrow(new PlaywrightException("Timeout"));
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "performInitialNavigationWithRetry", Page.class);
//        method.setAccessible(true);
//
//        assertThatThrownBy(() -> method.invoke(fetcher, page))
//                .hasCauseInstanceOf(PlaywrightException.class);
//        verify(page, times(3)).navigate(anyString(), any(Page.NavigateOptions.class));
//    }
//
//    // ================ formatCookies Tests ================
//
//    @Test
//    void formatCookies_withMultipleCookies_formatsCorrectly() throws Exception {
//        Cookie cookie1 = new Cookie("session", "abc123");
//        Cookie cookie2 = new Cookie("user", "john");
//        Cookie cookie3 = new Cookie("token", "xyz789");
//        List<Cookie> cookies = List.of(cookie1, cookie2, cookie3);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("formatCookies", List.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, cookies);
//
//        assertThat(result).contains("session=abc123");
//        assertThat(result).contains("user=john");
//        assertThat(result).contains("token=xyz789");
//        assertThat(result).contains(";");
//    }
//
//    @Test
//    void formatCookies_withEmptyCookies_returnsEmptyString() throws Exception {
//        List<Cookie> cookies = Collections.emptyList();
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("formatCookies", List.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, cookies);
//
//        assertThat(result).isEmpty();
//    }
//
//    @Test
//    void formatCookies_withSingleCookie_formatsWithoutSemicolon() throws Exception {
//        Cookie cookie = new Cookie("session", "abc123");
//        List<Cookie> cookies = List.of(cookie);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("formatCookies", List.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, cookies);
//
//        assertThat(result).isEqualTo("session=abc123");
//    }
//
//    // ================ buildHeaders Tests ================
//
//    @Test
//    void buildHeaders_includesAllRequiredHeaders() throws Exception {
//        String cookieHeader = "session=abc123";
//        Map<String, String> harvested = Map.of("authorization", "Bearer token");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "buildHeaders", String.class, Map.class, UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(
//                fetcher, cookieHeader, harvested, mockProfile);
//
//        assertThat(result).containsKeys(
//                "User-Agent", "Referer", "Cookie", "Accept",
//                "Content-Type", "X-Requested-With", "authorization"
//        );
//    }
//
//    @Test
//    void buildHeaders_cookieHeaderSetCorrectly() throws Exception {
//        String cookieHeader = "session=abc123; user=john";
//        Map<String, String> harvested = new HashMap<>();
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "buildHeaders", String.class, Map.class, UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(
//                fetcher, cookieHeader, harvested, mockProfile);
//
//        assertThat(result.get("Cookie")).isEqualTo(cookieHeader);
//    }
//
//    @Test
//    void buildHeaders_harvestedHeadersIncluded() throws Exception {
//        String cookieHeader = "session=abc123";
//        Map<String, String> harvested = new HashMap<>();
//        harvested.put("x-custom-header", "custom-value");
//        harvested.put("x-api-key", "key123");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "buildHeaders", String.class, Map.class, UserAgentProfile.class);
//        method.setAccessible(true);
//
//        @SuppressWarnings("unchecked")
//        Map<String, String> result = (Map<String, String>) method.invoke(
//                fetcher, cookieHeader, harvested, mockProfile);
//
//        assertThat(result).containsEntry("x-custom-header", "custom-value");
//        assertThat(result).containsEntry("x-api-key", "key123");
//    }
//
//    // ================ extractEventIds Tests ================
//
//    @Test
//    void extractEventIds_withValidJson_returnsEventIds() throws Exception {
//        String jsonBody = "{\"events\":[{\"id\":\"event1\"},{\"id\":\"event2\"}]}";
//        List<String> expectedIds = List.of("event1", "event2");
//
//        try (MockedStatic<JsonParser> mockedParser = mockStatic(JsonParser.class)) {
//            mockedParser.when(() -> JsonParser.extractEventIds(jsonBody)).thenReturn(expectedIds);
//
//            Method method = SportyBetOddsFetcher.class.getDeclaredMethod("extractEventIds", String.class);
//            method.setAccessible(true);
//
//            @SuppressWarnings("unchecked")
//            List<String> result = (List<String>) method.invoke(fetcher, jsonBody);
//
//            assertThat(result).containsExactly("event1", "event2");
//        }
//    }
//
//    @Test
//    void extractEventIds_withEmptyJson_returnsEmptyList() throws Exception {
//        String jsonBody = "{\"events\":[]}";
//
//        try (MockedStatic<JsonParser> mockedParser = mockStatic(JsonParser.class)) {
//            mockedParser.when(() -> JsonParser.extractEventIds(jsonBody)).thenReturn(Collections.emptyList());
//
//            Method method = SportyBetOddsFetcher.class.getDeclaredMethod("extractEventIds", String.class);
//            method.setAccessible(true);
//
//            @SuppressWarnings("unchecked")
//            List<String> result = (List<String>) method.invoke(fetcher, jsonBody);
//
//            assertThat(result).isEmpty();
//        }
//    }
//
//    @Test
//    void extractEventIds_withNullJson_returnsNull() throws Exception {
//        try (MockedStatic<JsonParser> mockedParser = mockStatic(JsonParser.class)) {
//            mockedParser.when(() -> JsonParser.extractEventIds(null)).thenReturn(null);
//
//            Method method = SportyBetOddsFetcher.class.getDeclaredMethod("extractEventIds", String.class);
//            method.setAccessible(true);
//
//            Object result = method.invoke(fetcher, (String) null);
//
//            assertThat(result).isNull();
//        }
//    }
//
//    // ================ parseEventDetail Tests ================
//
//    @Test
//    void parseEventDetail_withValidJson_returnsSportyEvent() throws Exception {
//        String detailJson = "{\"eventId\":\"event1\",\"name\":\"Match 1\"}";
//        SportyEvent expectedEvent = new SportyEvent();
//        expectedEvent.setEventId("event1");
//
//        try (MockedStatic<JsonParser> mockedParser = mockStatic(JsonParser.class)) {
//            mockedParser.when(() -> JsonParser.deserializeSportyEvent(detailJson, objectMapper))
//                    .thenReturn(expectedEvent);
//
//            Method method = SportyBetOddsFetcher.class.getDeclaredMethod("parseEventDetail", String.class);
//            method.setAccessible(true);
//            SportyEvent result = (SportyEvent) method.invoke(fetcher, detailJson);
//
//            assertThat(result).isNotNull();
//            assertThat(result.getEventId()).isEqualTo("event1");
//        }
//    }
//
//    @Test
//    void parseEventDetail_withInvalidJson_throwsException() throws Exception {
//        String invalidJson = "{invalid json}";
//
//        try (MockedStatic<JsonParser> mockedParser = mockStatic(JsonParser.class)) {
//            mockedParser.when(() -> JsonParser.deserializeSportyEvent(invalidJson, objectMapper))
//                    .thenThrow(new RuntimeException("Parse error"));
//
//            Method method = SportyBetOddsFetcher.class.getDeclaredMethod("parseEventDetail", String.class);
//            method.setAccessible(true);
//
//            assertThatThrownBy(() -> method.invoke(fetcher, invalidJson))
//                    .hasCauseInstanceOf(RuntimeException.class);
//        }
//    }
//
//    // ================ buildEventDetailUrl Tests ================
//
//    @Test
//    void buildEventDetailUrl_withEventId_buildsCorrectUrl() throws Exception {
//        String eventId = "sr:match:12345";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildEventDetailUrl", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, eventId);
//
//        assertThat(result).contains("https://www.sportybet.com/api/ng/factsCenter/event");
//        assertThat(result).contains("eventId=sr%3Amatch%3A12345");
//        assertThat(result).contains("productId=1");
//        assertThat(result).contains("_t=");
//    }
//
//    @Test
//    void buildEventDetailUrl_encodesSpecialCharacters() throws Exception {
//        String eventId = "event with spaces&special=chars";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildEventDetailUrl", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, eventId);
//
//        assertThat(result).doesNotContain(" ");
//        assertThat(result).contains("%");
//    }
//
//    // ================ buildUrl Tests ================
//
//    @Test
//    void buildUrl_withNoParams_returnsBaseUrl() throws Exception {
//        String baseUrl = "https://api.example.com/endpoint";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildUrl", String.class, Map.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, baseUrl, null);
//
//        assertThat(result).isEqualTo(baseUrl);
//    }
//
//    @Test
//    void buildUrl_withParams_appendsQueryString() throws Exception {
//        String baseUrl = "https://api.example.com/endpoint";
//        Map<String, String> params = Map.of("key1", "value1", "key2", "value2");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildUrl", String.class, Map.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, baseUrl, params);
//
//        assertThat(result).startsWith(baseUrl + "?");
//        assertThat(result).contains("key1=value1");
//        assertThat(result).contains("key2=value2");
//        assertThat(result).contains("&");
//    }
//
//    @Test
//    void buildUrl_withEmptyParams_returnsBaseUrl() throws Exception {
//        String baseUrl = "https://api.example.com/endpoint";
//        Map<String, String> params = Collections.emptyMap();
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildUrl", String.class, Map.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, baseUrl, params);
//
//        assertThat(result).isEqualTo(baseUrl);
//    }
//
//    @Test
//    void buildUrl_encodesSpecialCharacters() throws Exception {
//        String baseUrl = "https://api.example.com/endpoint";
//        Map<String, String> params = Map.of("key", "value with spaces&special=chars");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("buildUrl", String.class, Map.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, baseUrl, params);
//
//        assertThat(result).contains("%26");
//        assertThat(result).doesNotContain(" ");
//    }
//
//    // ================ encode Tests ================
//
//    @Test
//    void encode_withNormalString_encodesCorrectly() throws Exception {
//        String input = "hello world";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("encode", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        assertThat(result).isEqualTo("hello+world");
//    }
//
//    @Test
//    void encode_withSpecialCharacters_encodesCorrectly() throws Exception {
//        String input = "key=value&another=value";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("encode", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        assertThat(result).contains("%");
//        assertThat(result).doesNotContain("=");
//        assertThat(result).doesNotContain("&");
//    }
//
//    @ParameterizedTest
//    @ValueSource(strings = {"test", "test+value", "test@example.com", "test/path"})
//    void encode_withVariousInputs_encodesCorrectly(String input) throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("encode", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        assertThat(result).isNotNull();
//    }
//
//    // ================ doGetString Tests ================
//
//    @Test
//    void doGetString_withSuccessfulResponse_returnsBody() throws Exception {
//        String url = "https://api.example.com/data";
//        String responseBody = "{\"data\":\"value\"}";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(200);
//        when(apiResponse.text()).thenReturn(responseBody);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "doGetString", String.class, APIRequestContext.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, url, apiRequestContext);
//
//        assertThat(result).isEqualTo(responseBody);
//    }
//
//    @Test
//    void doGetString_with401Response_throwsPlaywrightException() throws Exception {
//        String url = "https://api.example.com/data";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(401);
//        when(apiResponse.text()).thenReturn("");
//
//        assertThatThrownBy(() ->
//                invokeUnwrapped(fetcher, "doGetString",
//                        new Class[]{String.class, APIRequestContext.class},
//                        url, apiRequestContext)
//        )
//                .isInstanceOf(PlaywrightException.class)
//                .hasMessageContaining("Auth/rate error")
//                .hasMessageContaining("401");
//    }
//
//
//    @Test
//    void doGetString_with403Response_throwsPlaywrightException() throws Exception {
//        String url = "https://api.example.com/data";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(403);
//        when(apiResponse.text()).thenReturn("");
//
//        assertThatThrownBy(() ->
//                invokeUnwrapped(fetcher, "doGetString",
//                        new Class[]{String.class, APIRequestContext.class},
//                        url, apiRequestContext)
//        )
//                .isInstanceOf(PlaywrightException.class)
//                .hasMessageContaining("Auth/rate error")
//                .hasMessageContaining("403");
//    }
//
//    @Test
//    void doGetString_with500Response_throwsPlaywrightException() throws Exception {
//        String url = "https://api.example.com/data";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(500);
//        when(apiResponse.statusText()).thenReturn("Internal Server Error");
//        when(apiResponse.text()).thenReturn("Error details");
//
//        assertThatThrownBy(() ->
//                invokeUnwrapped(fetcher, "doGetString",
//                        new Class[]{String.class, APIRequestContext.class},
//                        url, apiRequestContext)
//        )
//                .isInstanceOf(PlaywrightException.class)
//                .hasMessageContaining("HTTP 500");
//    }
//
//    @ParameterizedTest
//    @ValueSource(ints = {200, 201, 204, 299})
//    void doGetString_withSuccessStatuses_returnsBody(int statusCode) throws Exception {
//        String url = "https://api.example.com/data";
//        String responseBody = "{\"data\":\"value\"}";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(statusCode);
//        when(apiResponse.text()).thenReturn(responseBody);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "doGetString", String.class, APIRequestContext.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, url, apiRequestContext);
//
//        assertThat(result).isEqualTo(responseBody);
//    }
//
//    @ParameterizedTest
//    @ValueSource(ints = {400, 404, 500, 502, 503})
//    void doGetString_withErrorStatuses_throwsException(int statusCode) throws Exception {
//        String url = "https://api.example.com/data";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(statusCode);
//        when(apiResponse.statusText()).thenReturn("Error");
//        when(apiResponse.text()).thenReturn("");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "doGetString", String.class, APIRequestContext.class);
//        method.setAccessible(true);
//
//        assertThatThrownBy(() -> method.invoke(fetcher, url, apiRequestContext))
//                .hasCauseInstanceOf(PlaywrightException.class);
//    }
//
//    // ================ processParsedEvent Tests ================
//
//    @Test
//    void processParsedEvent_withValidEvent_normalizesAndProcesses() throws Exception {
//        SportyEvent sportyEvent = new SportyEvent();
//        sportyEvent.setEventId("event-1");
//
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        when(sportyBetService.convertToNormalEvent(sportyEvent)).thenReturn(normalizedEvent);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, sportyEvent);
//
//        Thread.sleep(200); // Wait for async processing
//
//        verify(sportyBetService).convertToNormalEvent(sportyEvent);
//        verify(arbDetector).addEventToPool(normalizedEventCaptor.capture());
//        assertThat(normalizedEventCaptor.getValue().getEventId()).isEqualTo("event-1");
//    }
//
//    @Test
//    void processParsedEvent_withNullEvent_skipsProcessing() throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, (SportyEvent) null);
//
//        verify(sportyBetService, never()).convertToNormalEvent(any());
//        verify(arbDetector, never()).addEventToPool(any());
//    }
//
//    @Test
//    void processParsedEvent_whenNormalizationReturnsNull_skipsDownstream() throws Exception {
//        SportyEvent sportyEvent = new SportyEvent();
//        sportyEvent.setEventId("event-1");
//
//        when(sportyBetService.convertToNormalEvent(sportyEvent)).thenReturn(null);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, sportyEvent);
//
//        Thread.sleep(200);
//
//        verify(sportyBetService).convertToNormalEvent(sportyEvent);
//        verify(arbDetector, never()).addEventToPool(any());
//        verify(betLegRetryService, never()).updateFailedBetLeg(any(), any());
//    }
//
//    @Test
//    void processParsedEvent_callsBetLegRetryServiceAsync() throws Exception {
//        SportyEvent sportyEvent = new SportyEvent();
//        sportyEvent.setEventId("event-1");
//
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        when(sportyBetService.convertToNormalEvent(sportyEvent)).thenReturn(normalizedEvent);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, sportyEvent);
//
//        Thread.sleep(300); // Wait for async processing
//
//        verify(betLegRetryService, timeout(1000)).updateFailedBetLeg(
//                normalizedEventCaptor.capture(),
//                eq(BookMaker.SPORTY_BET)
//        );
//        assertThat(normalizedEventCaptor.getValue().getEventId()).isEqualTo("event-1");
//    }
//
//    @Test
//    void processParsedEvent_whenBetRetryThrowsException_continuesProcessing() throws Exception {
//        SportyEvent sportyEvent = new SportyEvent();
//        sportyEvent.setEventId("event-1");
//
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        when(sportyBetService.convertToNormalEvent(sportyEvent)).thenReturn(normalizedEvent);
//        doThrow(new RuntimeException("Retry service error"))
//                .when(betLegRetryService).updateFailedBetLeg(any(), any());
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//
//        assertThatCode(() -> method.invoke(fetcher, sportyEvent))
//                .doesNotThrowAnyException();
//
//        Thread.sleep(300);
//
//        verify(arbDetector).addEventToPool(any(NormalizedEvent.class));
//    }
//
//    @Test
//    void processParsedEvent_whenArbDetectorIsNull_handlesGracefully() throws Exception {
//        SportyEvent sportyEvent = new SportyEvent();
//        sportyEvent.setEventId("event-1");
//
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        when(sportyBetService.convertToNormalEvent(sportyEvent)).thenReturn(normalizedEvent);
//
//        // Create fetcher with null arbDetector
//        SportyBetOddsFetcher fetcherWithNullDetector = new SportyBetOddsFetcher(
//                scraperConfig,
//                profileManager,
//                sportyBetService,
//                betLegRetryService,
//                null, // null arbDetector
//                objectMapper
//        );
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processParsedEvent", SportyEvent.class);
//        method.setAccessible(true);
//
//        assertThatCode(() -> method.invoke(fetcherWithNullDetector, sportyEvent))
//                .doesNotThrowAnyException();
//    }
//
//    // ================ processBetRetryInfo Tests ================
//
//    @Test
//    void processBetRetryInfo_withValidEvent_callsRetryService() throws Exception {
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processBetRetryInfo", NormalizedEvent.class);
//        method.setAccessible(true);
//        method.invoke(fetcher, normalizedEvent);
//
//        verify(betLegRetryService).updateFailedBetLeg(normalizedEvent, BookMaker.SPORTY_BET);
//    }
//
//    @Test
//    void processBetRetryInfo_whenServiceThrowsException_logsError() throws Exception {
//        NormalizedEvent normalizedEvent = new NormalizedEvent();
//        normalizedEvent.setEventId("event-1");
//
//        doThrow(new RuntimeException("Service error"))
//                .when(betLegRetryService).updateFailedBetLeg(any(), any());
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "processBetRetryInfo", NormalizedEvent.class);
//        method.setAccessible(true);
//
//        assertThatCode(() -> method.invoke(fetcher, normalizedEvent))
//                .doesNotThrowAnyException();
//    }
//
//    // ================ safeBody Tests ================
//
//    @Test
//    void safeBody_withValidResponse_returnsText() throws Exception {
//        when(apiResponse.text()).thenReturn("response body");
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "safeBody", APIResponse.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, apiResponse);
//
//        assertThat(result).isEqualTo("response body");
//    }
//
//    @Test
//    void safeBody_whenTextThrowsException_returnsNull() throws Exception {
//        when(apiResponse.text()).thenThrow(new RuntimeException("Error reading text"));
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "safeBody", APIResponse.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, apiResponse);
//
//        assertThat(result).isNull();
//    }
//
//    // ================ snippet Tests ================
//
//    @Test
//    void snippet_withShortString_returnsFullString() throws Exception {
//        String input = "Short string";
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("snippet", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        assertThat(result).isEqualTo(input);
//    }
//
//    @Test
//    void snippet_withLongString_truncatesTo500Chars() throws Exception {
//        String input = "x".repeat(1000);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("snippet", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        assertThat(result).hasSize(503); // 500 + "..."
//        assertThat(result).endsWith("...");
//    }
//
//    @Test
//    void snippet_withNullString_returnsNullString() throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("snippet", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, (String) null);
//
//        assertThat(result).isEqualTo("null");
//    }
//
//    @ParameterizedTest
//    @ValueSource(ints = {0, 100, 500, 501, 1000})
//    void snippet_withVariousLengths_handlesCorrectly(int length) throws Exception {
//        String input = "x".repeat(length);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("snippet", String.class);
//        method.setAccessible(true);
//        String result = (String) method.invoke(fetcher, input);
//
//        if (length <= 500) {
//            assertThat(result).isEqualTo(input);
//        } else {
//            assertThat(result).hasSize(503);
//            assertThat(result).endsWith("...");
//        }
//    }
//
//    // ================ backoffWithJitter Tests ================
//
//    @Test
//    void backoffWithJitter_withAttempt1_delaysAppropriately() throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("backoffWithJitter", int.class);
//        method.setAccessible(true);
//
//        long start = System.currentTimeMillis();
//        method.invoke(fetcher, 1);
//        long duration = System.currentTimeMillis() - start;
//
//        // Should be around 2000ms * 2^1 + jitter (500-1500)
//        assertThat(duration).isGreaterThan(2000).isLessThan(8000);
//    }
//
//    @Test
//    void backoffWithJitter_withAttempt0_delaysMinimally() throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("backoffWithJitter", int.class);
//        method.setAccessible(true);
//
//        long start = System.currentTimeMillis();
//        method.invoke(fetcher, 0);
//        long duration = System.currentTimeMillis() - start;
//
//        // Should be around 2000ms * 2^1 + jitter (since Math.max(1, 0) = 1)
//        assertThat(duration).isGreaterThan(1500).isLessThan(8000);
//    }
//
//    @Test
//    void backoffWithJitter_capsAt20Seconds() throws Exception {
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod("backoffWithJitter", int.class);
//        method.setAccessible(true);
//
//        long start = System.currentTimeMillis();
//        method.invoke(fetcher, 10); // Very high attempt number
//        long duration = System.currentTimeMillis() - start;
//
//        // Should be capped at 20000ms
//        assertThat(duration).isLessThanOrEqualTo(21000);
//    }
//
//    // ================ Parameterized Tests ================
//
//    @ParameterizedTest
//    @MethodSource("provideHttpStatusCodes")
//    void doGetString_withVariousStatusCodes_handlesCorrectly(
//            int statusCode,
//            boolean shouldSucceed
//    ) throws Exception {
//        String url = "https://api.example.com/data";
//        String responseBody = "{\"data\":\"value\"}";
//
//        when(apiRequestContext.get(url)).thenReturn(apiResponse);
//        when(apiResponse.status()).thenReturn(statusCode);
////        when(apiResponse.statusText()).thenReturn("Status");
//        when(apiResponse.text()).thenReturn(responseBody);
//
//        Method method = SportyBetOddsFetcher.class.getDeclaredMethod(
//                "doGetString", String.class, APIRequestContext.class);
//        method.setAccessible(true);
//
//        if (shouldSucceed) {
//            String result = (String) method.invoke(fetcher, url, apiRequestContext);
//            assertThat(result).isEqualTo(responseBody);
//        } else {
//            assertThatThrownBy(() -> method.invoke(fetcher, url, apiRequestContext))
//                    .hasCauseInstanceOf(PlaywrightException.class);
//        }
//    }
//
//    private static Stream<Arguments> provideHttpStatusCodes() {
//        return Stream.of(
//                Arguments.of(200, true),
//                Arguments.of(201, true),
//                Arguments.of(204, true),
//                Arguments.of(299, true),
//                Arguments.of(300, false),
//                Arguments.of(400, false),
//                Arguments.of(401, false),
//                Arguments.of(403, false),
//                Arguments.of(404, false),
//                Arguments.of(500, false),
//                Arguments.of(502, false),
//                Arguments.of(503, false)
//        );
//    }
//
//    // ================ Helper Methods ================
//
//    private UserAgentProfile createMockUserAgentProfile() {
//        UserAgentProfile profile = new UserAgentProfile();
//        profile.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0");
//
//        // Viewport
//        UserAgentProfile.Viewport viewport = new UserAgentProfile.Viewport();
//        viewport.setWidth(1920);
//        viewport.setHeight(1080);
//        profile.setViewport(viewport);
//
//        // Headers
//        UserAgentProfile.Headers headers = new UserAgentProfile.Headers();
//        Map<String, String> standardHeaders = new HashMap<>();
//        standardHeaders.put("Accept", "text/html,application/json");
//        standardHeaders.put("Accept-Language", "en-US,en;q=0.9");
//        headers.setStandardHeaders(standardHeaders);
//
//        Map<String, String> clientHintsHeaders = new HashMap<>();
//        clientHintsHeaders.put("Sec-CH-UA", "\"Chromium\";v=\"120\"");
//        headers.setClientHintsHeaders(clientHintsHeaders);
//        profile.setHeaders(headers);
//
//        // Client Hints
//        UserAgentProfile.ClientHints clientHints = new UserAgentProfile.ClientHints();
//        clientHints.setPlatform("Windows");
//        clientHints.setPlatformVersion("10.0.0");
//        clientHints.setArchitecture("x86");
//        clientHints.setBitness("64");
//        clientHints.setModel("");
//        clientHints.setUaFullVersion("120.0.6099.109");
//        clientHints.setMobile(String.valueOf(false));
//
//        List<UserAgentProfile.ClientHints.Brand> brands = new ArrayList<>();
//        UserAgentProfile.ClientHints.Brand brand = new UserAgentProfile.ClientHints.Brand();
//        brand.setBrand("Chromium");
//        brand.setVersion("120");
//        brands.add(brand);
//        clientHints.setBrands(brands);
//        profile.setClientHints(clientHints);
//
//        // WebGL
//        UserAgentProfile.Webgl webgl =UserAgentProfile.Webgl.builder()
//                .vendor("Google Inc.")
//                .renderer("ANGLE (Intel, Intel(R) UHD Graphics Direct3D11)")
//                .version("WebGL 1.0")
//                .build();
//        profile.setWebgl(webgl);
//
//        profile.setWebglVendor("Google Inc.");
//        profile.setWebglRenderer("ANGLE (Intel)");
//
//        // Screen
//        UserAgentProfile.Screen screen = new UserAgentProfile.Screen();
//        screen.setAvailWidth(1920);
//        screen.setAvailHeight(1040);
//        screen.setColorDepth(24);
//        screen.setPixelDepth(24);
//        profile.setScreen(screen);
//
//        // Other properties
//        profile.setCanvasFillStyle("#000000");
//        profile.setAudioFrequency((int) 440.0);
//        profile.setHardwareConcurrency(8);
//        profile.setDeviceMemory(8);
//        profile.setPlatform("Win32");
//        profile.setLanguages(List.of("en-US", "en"));
//        profile.setTimeZone("America/New_York");
//
//        // Plugins
//        List<UserAgentProfile.Plugin> plugins = new ArrayList<>();
//        UserAgentProfile.Plugin plugin = new UserAgentProfile.Plugin();
//        plugin.setName("Chrome PDF Plugin");
//        plugin.setDescription("Portable Document Format");
//        plugins.add(plugin);
//        profile.setPlugins(plugins);
//
//        // Connection
//        UserAgentProfile.Connection connection = new UserAgentProfile.Connection();
//        connection.setDownlink(10.0);
//        connection.setEffectiveType("4g");
//        connection.setRtt(50);
//        profile.setConnection(connection);
//
//        // Battery
//        UserAgentProfile.Battery battery = new UserAgentProfile.Battery();
//        battery.setCharging(true);
//        battery.setLevel(0.85);
//        profile.setBattery(battery);
//
//        // Geolocation
//        UserAgentProfile.Geolocation geolocation = new UserAgentProfile.Geolocation();
//        geolocation.setLatitude(40.7128);
//        geolocation.setLongitude(-74.0060);
//        geolocation.setAccuracy((int) 100.0);
//        profile.setGeolocation(geolocation);
//
//        // Permissions
//        UserAgentProfile.Permissions permissions = new UserAgentProfile.Permissions();
//        permissions.setNotifications("default");
//        permissions.setGeolocation("prompt");
//        profile.setPermissions(permissions);
//
//        // Media Devices
//        List<UserAgentProfile.MediaDevice> mediaDevices = new ArrayList<>();
//        profile.setMediaDevices(mediaDevices);
//
//        // Fonts
//        profile.setFonts(List.of("Arial", "Times New Roman", "Courier New"));
//
//        // Storage
//        UserAgentProfile.Storage storage = new UserAgentProfile.Storage();
//        storage.setQuota(10737418240L);
//        storage.setUsage(1073741824L);
//        profile.setStorage(storage);
//
//        return profile;
//    }
//
//    private NormalizedEvent createNormalizedEvent(String eventId) {
//        NormalizedEvent event = new NormalizedEvent();
//        event.setEventId(eventId);
//        event.setHomeTeam("Home Team");
//        event.setAwayTeam("Away Team");
//        event.setLeague("Test League");
//        return event;
//    }
//
//    private SportyEvent createSportyEvent(String eventId) {
//        SportyEvent event = new SportyEvent();
//        event.setEventId(eventId);
//        return event;
//    }
//
//    /**
//     * Helper to access private fields for testing
//     */
//    @SuppressWarnings("unchecked")
//    private <T> T getPrivateField(Object obj, String fieldName, Class<T> fieldType) {
//        try {
//            Field field = obj.getClass().getDeclaredField(fieldName);
//            field.setAccessible(true);
//            return (T) field.get(obj);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to access field: " + fieldName, e);
//        }
//    }
//
//    /**
//     * Helper to set private fields for testing
//     */
//    private void setPrivateField(Object obj, String fieldName, Object value) {
//        try {
//            Field field = obj.getClass().getDeclaredField(fieldName);
//            field.setAccessible(true);
//            field.set(obj, value);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to set field: " + fieldName, e);
//        }
//    }
//
//    private Object invokeUnwrapped(Object target, String name, Class<?>[] types, Object... args) throws Exception {
//        try {
//            Method m = target.getClass().getDeclaredMethod(name, types);
//            m.setAccessible(true);
//            return m.invoke(target, args);
//        } catch (InvocationTargetException ite) {
//            // rethrow the *real* exception
//            Throwable cause = ite.getCause();
//            if (cause instanceof Exception e) throw e;
//            if (cause instanceof Error err) throw err;
//            throw ite; // fallback
//        }
//    }
//
//}