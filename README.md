# 🛡️ Blocker

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Blocker App Icon" width="120"/>
  <br>
  <p><b>A powerful, privacy-focused Android app blocker designed for strict focus and real digital discipline.</b></p>
  
  [![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android)](https://www.android.com/)
  [![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
  [![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
</div>

---

## 📖 Overview

Blocker is a robust Android application engineered to eliminate distractions at the system level.

It is not a reminder app.  
It is not a habit tracker.  
It is not built around motivation.

Blocker enforces rules.

Whether you are studying, working, or trying to reduce compulsive screen use, Blocker applies strict app restrictions with tamper-resistant safeguards to keep you accountable.

Built around friction, not inspiration.

---

## ✨ Key Features

### 🔒 Aggressive App Blocking
- Instantly blocks restricted apps using Accessibility services.
- Prevents bypass through floating windows, split-screen mode, or quick app switching.
- Displays a non-dismissible overlay when access is denied.

### 🧠 Advanced Anki Enforcement System
- Automatically blocks selected apps when Anki has due cards.
- Grants **1 minute of app access per completed Anki card**.
- Supports strict **Focus / Pomodoro Zones** where no restricted apps can be accessed.
- Locks Anki new/review card limits to prevent reducing workload to bypass restrictions.
- Detects Anki uninstall or deck deletion attempts.
- If tampering is detected, all blocked apps are hard-locked.

Designed with the assumption that users will try to bypass it.

### 🛡️ Uninstall & Tamper Protection
- Uses Device Administrator privileges to prevent unauthorized removal.
- Accessibility guard prevents force-stop attempts.
- Settings modification protections for critical enforcement features.
- Multi-layer protection approach.

### 🎨 Material You 3 Design
- Fully integrated with Material Design 3.
- Dynamic Monet color adaptation.
- Clean, modern, system-native interface.

### ⚡ Lightweight & Efficient
- Minimal battery impact.
- Runs locally.
- No background data collection.

---

## 🧠 Philosophy

Discipline works better when exits are removed.

Most productivity tools assume good intentions.  
Blocker assumes weakness.

Instead of increasing motivation, it removes escape routes.

No dopamine until the reps are done.

---

## 🚀 Installation

1. Visit the [Releases page](https://github.com/creativeidiot123/Blocker/releases).
2. Download the latest `.apk` file.
3. Open the APK on your Android device.
4. Allow installation from unknown sources if prompted.
5. Complete the setup process and grant required permissions.

---

## ⚙️ Required Permissions

Blocker requires elevated permissions to function as a strict enforcement tool:

### Accessibility Service
- Detects when blocked apps are launched.
- Closes restricted apps immediately.
- Prevents force-stopping or removal via system settings.

### Device Administrator
- Prevents unauthorized uninstallation.
- Adds an additional layer of protection.

### Draw Over Other Apps (Overlay)
- Displays the "Blocked" screen over restricted apps.
- Ensures enforcement even in multi-window modes.

> Blocker operates entirely offline.  
> It does not collect, store, or transmit any personal data.

---

## 🛠️ Built With

- [Kotlin](https://kotlinlang.org/) — Official Android development language.
- [Material Design 3](https://m3.material.io/) — Modern UI framework.
- Android Accessibility APIs.
- Android Device Admin APIs.

---

## 📌 Intended Use

Blocker is designed for:
- Students preparing for competitive exams
- Deep work sessions
- Language learning with Anki
- Reducing compulsive social media usage

It is intentionally strict.

If you are looking for flexible or gentle blocking, this may not be the right tool.

---

## 🧪 Status

Actively maintained and evolving.

Future improvements may include:
- Custom reward ratios
- Detailed usage analytics
- Additional tamper detection strategies
- Expanded study-app integrations

---

## 📄 License

This project is licensed under the MIT License.  
See the [LICENSE](LICENSE) file for details.
