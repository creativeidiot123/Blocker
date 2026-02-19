# 🛡️ Blocker

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Blocker App Icon" width="120"/>
  <br>
  <p><b>A powerful, privacy-focused Android app blocker designed for ultimate focus and digital wellbeing.</b></p>
  
  [![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android)](https://www.android.com/)
  [![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
  [![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
</div>

## 📖 Overview

Blocker is a robust Android application engineered to help you reclaim your time and focus. Whether you are studying, working, or simply trying to reduce screen time, Blocker enforces strict app restrictions to keep you on track. Featuring advanced protection mechanisms and a gorgeous Material You 3 design, it's not just another app blocker—it's a commitment to productivity.

## ✨ Key Features

*   **🔒 Aggressive App Blocking:** Utilizes Accessibility services to instantly block restricted applications, preventing bypasses via floating windows or split screens.
*   **🧠 Anki Integration:** Unique integration with Anki! Automatically blocks distracting apps when you have Anki cards due, ensuring you finish your study sessions first.
*   **🛡️ Uninstall & Modification Protection:** Employs Device Admin privileges and Accessibility guards to prevent unauthorized uninstallation or tampering with the app's settings.
*   **🎨 Material You 3 Design:** A stunning, modern, and expressive user interface that adapts to your device's Monet color palette for a seamless, native experience.
*   **⚡ Lightweight & Efficient:** Optimized for performance with minimal battery drain, ensuring your device runs smoothly while staying protected.

## 🚀 Installation

1. Go to the [Releases page](https://github.com/creativeidiot123/Blocker/releases) of this repository.
2. Download the latest `app-release.apk` file.
3. On your Android device, open the downloaded APK.
4. If prompted, allow your file manager or browser to "Install unknown apps".
5. Follow the on-screen instructions to complete the installation.

## ⚙️ Required Permissions

For Blocker to function correctly and provide its strict blocking capabilities, it requires the following elevated permissions:

*   **Accessibility Service:** Used aggressively to detect when a blocked app is launched (even in floating windows) and immediately close it. Also used to prevent users from force-stopping or uninstalling Blocker from Android Settings.
*   **Device Administrator:** Used as a secondary layer of protection to prevent the app from being uninstalled without authorization.
*   **Draw Over Other Apps (Overlay):** Required to display the "Blocked" screen over applications you are trying to restrict.

*Note: Blocker respects your privacy. It does not collect, store, or transmit any personal data. It operates entirely offline on your device.*

## 🛠️ Built With

*   [Kotlin](https://kotlinlang.org/) - First-class and official programming language for Android development.
*   [Material Design 3](https://m3.material.io/) - UI framework for creating beautiful, dynamic interfaces.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
