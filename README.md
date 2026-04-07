# ValtPrinter 🖨️

A premium, production-grade Android application for seamless integration with **SUNMI Cloud Printers**. Built with **Clean Architecture**, **Jetpack Compose**, and a robust **WYSIWYG** (What You See Is What You Get) printing engine.

## 🚀 Features

-   **Real-Time Vitality (Heartbeat)**: 3-second diagnostic pulses to ensure hardware is active and responsive.
-   **Self-Healing Auto-Reconnection**: Intelligent 60-second recovery loop that automatically re-establishes connectivity with visual countdowns.
-   **Multi-Protocol Discovery**: Simultaneous scanning for printers via **USB**, **LAN (Ethernet/Wi-Fi)**, and **Bluetooth**.
-   **Jetpack Compose Dashboard**: A high-end, state-driven interface featuring:
    *   **Breathing Status Auras**: Dynamic neon glows to indicate system health (Stable/Recovering/Fault).
    *   **Micro-Animations**: Shaking hardware icons during faults and smooth transitions for state changes.
-   **Multi-Sensory Feedback**: Guarded haptic pulses and audio tones for critical hardware events via the `FeedbackManager`.
-   **WYSIWYG Printing**: Renders Jetpack Compose UI directly to a 384px (80mm) bitmap for perfect pixel-to-paper matches.
-   **Stabilized Architecture**: Fully integrated Hilt-WorkManager pipeline for reliable background maintenance and log pruning.

## 🛠️ Architecture

ValtPrinter follows the **Clean Architecture** pattern to ensure maintainability, testability, and "Elite" stability.

-   **Domain Layer**: Pure business logic, Use Cases, and repository interfaces.
-   **Data Layer**: SDK integration, Raw ESC/POS socket engines, and Hilt-driven Background Workers.
-   **Presentation Layer (MVVM)**: Reactive UI driven by `StateFlow`. Uses advanced Compose features like `infiniteTransition` for "Living UI" aesthetics.
-   **Fault Tolerance Layer**: Deep-level safety guards around haptics and hardware calls to prevent process-killing exceptions.

## 📂 Implementation Tree

```text
ValtPrinter/
├── app/
│   ├── src/main/java/com/yotech/valtprinter/
│   │   ├── core/
│   │   │   ├── util/
│   │   │   │   └── BitmapRenderer.kt (Off-screen rendering engine)
│   │   │   └── ValtPrinter.kt (Application Class)
│   │   ├── data/
│   │   │   ├── mapper/
│   │   │   │   └── PrinterMapper.kt (SDK to Domain mapping)
│   │   │   ├── model/ (Internal data models)
│   │   │   ├── repository/
│   │   │   │   └── PrinterRepositoryImpl.kt (Core logic implementation)
│   │   │   └── source/
│   │   │       ├── RawSocketPrintSource.kt (ESC/POS socket engine)
│   │   │       └── SdkPrintSource.kt (SUNMI SDK wrapper)
│   │   ├── di/
│   │   │   └── PrinterModule.kt (Hilt Dependency Injection)
│   │   ├── domain/
│   │   │   ├── model/ (Sealed states, Domain entities)
│   │   │   ├── repository/ (Repository interfaces)
│   │   │   └── usecase/ (Business logic orchestration)
│   │   ├── presentation/
│   │   │   ├── receipt/ (Receipt components and preview)
│   │   │   ├── ui/ (Main screens and activities)
│   │   │   └── viewmodel/ (Reactive UI state management)
│   │   └── ui/theme/ (Premium dark theme definition)
│   └── src/main/AndroidManifest.xml
└── build.gradle.kts
```

## 📦 Requirements

-   **SUNMI External Printer Library 2** (v1.0.14+)
-   **Android SDK**: 24 (min), 36 (target)
-   **Dependencies**: Hilt, Compose, Coroutines, KSP, Gson.

## 🚦 Getting Started

1.  **Clone the repository**.
2.  **Add the SUNMI SDK**: Place `externalprinterlibrary2-1.0.14-release.aar` in the `app/libs/` directory.
3.  **Sync Gradle**: Build the project using Android Studio or `./gradlew assembleDebug`.
4.  **Permissions**: The app will request Bluetooth and Location permissions at runtime for discovery.

## ⚖️ License

Copyright © 2026 Muhammad Rejaul Karim. All rights reserved.
Licensed under the Apache License, Version 2.0.
