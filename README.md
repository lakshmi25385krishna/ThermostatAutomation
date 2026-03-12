# ThermostatAutomation
What It Is
A full-stack IoT web application that simulates a smart thermostat. A React frontend lets users control temperature, system mode (heat/cool/auto/off), and fan mode in real time. An Express REST API serves both the web UI and a physical thermostat device, with Firebase Firestore as the live database. The API supports polling and server-sent events so a real device can push temperature readings back to the UI.

Automation (the main talking point)
This is where the project stands out. You built a complete Java-based E2E test automation framework from scratch:

Framework Architecture

Built on Appium + Selenium WebDriver for browser automation, TestNG as the test runner, and REST Assured for API-layer assertions — all orchestrated through Maven
Followed the Page Object Model (POM) pattern: a DashboardPage class encapsulates all UI interactions, keeping tests clean and maintainable
BaseTest provides shared setup/teardown, WebDriver lifecycle management, and logging via SLF4J/Logback
Test Coverage — 5 Test Classes

DashboardLoadTest — verifies the page loads, thermostat name appears, and all controls are present
TemperatureControlTest — validates + / − buttons update the displayed target temperature correctly
SystemModeTest — asserts each mode button (heat, cool, auto, off) activates and reflects in the UI
FanModeTest — verifies fan mode toggle behavior
ApiIntegrationTest — the most sophisticated class: combines UI actions with direct API assertions using REST Assured's fluent given().when().then() DSL to confirm that UI changes actually persist to the backend, not just local React state
Key Engineering Decisions

Database safety by design: every test in ApiIntegrationTest uses a @BeforeMethod snapshot (reads current Firebase state) and an @AfterMethod(alwaysRun=true) restore (PATCHes original values back), so the database is always left exactly as it was found — even if a test fails mid-run
API + UI cross-layer validation: tests click a button in the browser, wait for the debounce and network round-trip, then independently query the REST API to confirm the change persisted — this catches bugs that pure UI tests would miss
REST Assured integration: replaced raw Java HttpClient code with REST Assured's expressive assertion syntax, making tests readable as living documentation (e.g., .body("systemMode", equalTo("heat")))
Stable test selectors: every interactive element in the React app has a data-testid attribute, making tests resilient to CSS and layout changes
How to run:

mvn test -Dtest=ApiIntegrationTest -Dapp.base.url=http://localhost:4000
