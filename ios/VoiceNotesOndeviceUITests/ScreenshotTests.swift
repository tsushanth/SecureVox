import XCTest

@MainActor
class ScreenshotTests: XCTestCase {
    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        setupSnapshot(app)
        app.launch()
    }

    func testScreenshots() {
        sleep(3)

        // 1. Recordings list (default)
        snapshot("01_Recordings")

        // 2. Settings tab
        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        snapshot("02_Settings")

        // 3. Back to recordings
        app.tabBars.buttons["Recordings"].tap()
        sleep(1)
        snapshot("03_RecordingsMain")
    }
}
