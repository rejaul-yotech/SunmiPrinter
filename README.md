# ValtPrinter 🖨️

A premium, production-grade Android application for seamless integration with **SUNMI Cloud Printers**. Built with **Clean Architecture**, **Jetpack Compose**, and a robust **WYSIWYG** (What You See Is What You Get) printing engine.

## 🚀 Features

-   **Multi-Protocol Discovery**: Simultaneous scanning for printers via **USB**, **LAN (Ethernet/Wi-Fi)**, and **Bluetooth**.
-   **USB Plug-and-Play (Auto-connect)**: Instant detection and automatic connection to USB printers upon device attachment.
-   **WYSIWYG Printing**: Renders Jetpack Compose UI directly to a 384px (80mm) bitmap, ensuring the printed receipt matches the on-screen preview perfectly.
-   **Hybrid Print Engine**:
    -   **SDK Integration**: Native SUNMI SDK support for USB and general printing.
    -   **Socket Bypass**: Direct ESC/POS raster command injection for high-speed, high-clarity LAN printing via port 9100.
-   **Modern UI/UX**: A state-driven, premium dark-themed interface built entirely with Jetpack Compose.
-   **Robust Error Handling**: Comprehensive lifecycle management and crash prevention (including `WindowRecomposer` isolation).

## 🛠️ Architecture

ValtPrinter follows the **Clean Architecture** pattern to ensure maintainability, testability, and scalability.

-   **Domain Layer**: Contains pure business logic, Use Cases, and repository interfaces. No Android dependencies.
-   **Data Layer**: Implements repository interfaces, manages SDK integrations, and handles raw socket communications.
-   **Presentation Layer**: MVVM with Jetpack Compose. Reactive UI driven by `StateFlow` and structured `PrinterState` sealed classes.
-   **Core Utilities**: Includes the `BitmapRenderer` for off-screen Composable-to-Bitmap conversion.

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
