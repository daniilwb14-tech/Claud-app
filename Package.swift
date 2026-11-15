// swift-tools-version:5.9
// Пакет нужен только для юнит-тестов ядра (`swift test`). Само приложение собирается из Grosh.xcodeproj.
import PackageDescription

let package = Package(
    name: "GroshCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    targets: [
        .target(name: "GroshCore", path: "Grosh/Core"),
        .testTarget(name: "GroshCoreTests", dependencies: ["GroshCore"], path: "Tests/GroshCoreTests"),
    ]
)
