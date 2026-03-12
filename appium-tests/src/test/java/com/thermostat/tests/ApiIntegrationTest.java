package com.thermostat.tests;

import com.thermostat.base.BaseTest;
import com.thermostat.pages.DashboardPage;
import com.thermostat.utils.Config;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * ApiIntegrationTest
 *
 * Verifies that UI actions actually persist to the backend API,
 * not just update the local React state.
 *
 * All HTTP calls use REST Assured's fluent given/when/then style.
 *
 * IMPORTANT — DATABASE SAFETY:
 *  These tests call the real API which writes to the real Firebase database.
 *  To avoid leaving dirty data behind, @BeforeMethod snapshots the current
 *  thermostat state and @AfterMethod always restores it, even if the test fails.
 *  The database ends up in exactly the same state it started in.
 *
 * HOW IT WORKS:
 *  1. Snapshot — read current targetTemp + systemMode from the API
 *  2. Open the app in the browser (via Appium/WebDriver)
 *  3. Perform a UI action (e.g. click +)
 *  4. Wait for the debounce + network round-trip to complete
 *  5. Assert the API response reflects the change
 *  6. Restore — PATCH the original values back, regardless of pass/fail
 */
public class ApiIntegrationTest extends BaseTest {

    private static final String THERMOSTAT_URL = "/api/thermostats/1";
    private static final String LIST_URL       = "/api/thermostats";

    // Captured before each test, restored after each test
    private int    originalTargetTemp;
    private String originalSystemMode;

    // ── One-time setup ───────────────────────────────────────────────────────

    @BeforeClass(alwaysRun = true)
    public void configureRestAssured() {
        RestAssured.baseURI = Config.APP_BASE_URL;
        log.info("REST Assured base URI: {}", Config.APP_BASE_URL);
    }

    // ── Snapshot & Restore ───────────────────────────────────────────────────

    @BeforeMethod(alwaysRun = true)
    public void snapshotState() {
        Response r = given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .extract().response();

        originalTargetTemp = r.jsonPath().getInt("targetTemp");
        originalSystemMode = r.jsonPath().getString("systemMode");
        log.info("Snapshot — targetTemp: {}°, systemMode: '{}'",
                originalTargetTemp, originalSystemMode);
    }

    /**
     * Runs after EVERY test (pass or fail) and PATCHes the API back
     * to the values that existed before the test ran.
     * The database is left exactly as it was found.
     */
    @AfterMethod(alwaysRun = true)
    public void restoreState() {
        String body = String.format(
                "{\"targetTemp\":%d,\"systemMode\":\"%s\"}",
                originalTargetTemp, originalSystemMode);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch(THERMOSTAT_URL)
                .then()
                .statusCode(200);

        log.info("Restored — targetTemp: {}°, systemMode: '{}'",
                originalTargetTemp, originalSystemMode);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test(description = "GET /api/thermostats returns HTTP 200 with all required fields")
    public void testApiHealthCheck() {
        given()
                .accept(ContentType.JSON)
                .when()
                .get(LIST_URL)
                .then()
                .statusCode(200)
                .body("$",           not(emptyIterable()))
                .body("[0].currentTemp",     notNullValue())
                .body("[0].targetTemp",      notNullValue())
                .body("[0].systemMode",      notNullValue())
                .body("[0].fanMode",         notNullValue())
                .body("[0].currentHumidity", notNullValue());

        log.info("testApiHealthCheck passed — all required fields present");
    }

    @Test(description = "GET /api/thermostats/1 response has correct field types")
    public void testGetResponseShape() {
        given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("currentTemp",     isA(Number.class))
                .body("targetTemp",      isA(Number.class))
                .body("currentHumidity", isA(Number.class))
                .body("systemMode",      isA(String.class))
                .body("fanMode",         isA(String.class));

        log.info("testGetResponseShape passed — all fields have correct types");
    }

    @Test(description = "Clicking + sends the updated target temperature to the API")
    public void testIncreaseTempPersistsToApi() {
        DashboardPage page = new DashboardPage(driver).waitUntilLoaded();

        if (page.getSystemModeLabel().equals("off")) {
            page.clickSystemMode("heat");
            waitForUiSettle();
        }

        int apiBefore = fetchTargetTemp();
        page.clickIncreaseTemp();
        waitForApiRoundTrip();
        int apiAfter = fetchTargetTemp();

        log.info("targetTemp API: {} → {}", apiBefore, apiAfter);

        given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("targetTemp", equalTo(apiBefore + 1));
    }

    @Test(description = "Clicking − sends the updated target temperature to the API")
    public void testDecreaseTempPersistsToApi() {
        DashboardPage page = new DashboardPage(driver).waitUntilLoaded();

        if (page.getSystemModeLabel().equals("off")) {
            page.clickSystemMode("heat");
            waitForUiSettle();
        }

        int apiBefore = fetchTargetTemp();
        page.clickDecreaseTemp();
        waitForApiRoundTrip();

        log.info("targetTemp API before: {}", apiBefore);

        given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("targetTemp", equalTo(apiBefore - 1));
    }

    @Test(description = "Switching system mode to 'heat' in the UI persists to the API")
    public void testSystemModePersistsToApi() {
        DashboardPage page = new DashboardPage(driver).waitUntilLoaded();
        page.clickSystemMode("heat");
        waitForApiRoundTrip();

        given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("systemMode", equalTo("heat"));

        log.info("testSystemModePersistsToApi passed — systemMode == 'heat'");
    }

    @Test(description = "PATCH targetTemp returns 200 and reflects the new value")
    public void testPatchTargetTemp() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"targetTemp\":75}")
                .when()
                .patch(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("targetTemp", equalTo(75));

        log.info("testPatchTargetTemp passed — targetTemp patched to 75");
    }

    @Test(description = "PATCH systemMode returns 200 and reflects the new mode")
    public void testPatchSystemMode() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"systemMode\":\"auto\"}")
                .when()
                .patch(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .body("systemMode", equalTo("auto"));

        log.info("testPatchSystemMode passed — systemMode patched to 'auto'");
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private int fetchTargetTemp() {
        return given()
                .accept(ContentType.JSON)
                .when()
                .get(THERMOSTAT_URL)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("targetTemp");
    }
}
