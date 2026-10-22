# ⚡ Bridge — Local Direct File Transfer & P2P Sharing

<p align="center">
  <strong>Blazing fast, secure, local peer-to-peer file transfer between Android devices and any web browser — without cloud uploads, cables, or cellular data.</strong>
</p>

---

## 📱 What is Bridge?

**Bridge** is a modern, high-performance Android application designed to bridge the gap between your Android device and any computer, tablet, or secondary phone on the same local Wi-Fi or hotspot network. 

Whether you need to send 4K videos to your laptop, download photos from a web browser directly to your phone's Gallery, or share files securely with AES-256 encryption, Bridge provides an instantaneous, cable-free connection.

---

## 🌟 Key Features & Capabilities

- **🌐 Zero-Cloud Direct Local Transfer**: Data streams directly device-to-device over your local network. Your private files never touch third-party cloud servers.
- **📷 QR Code & URL Instant Pairing**: Scan the host device's QR code using the built-in CameraX scanner or type the local URL into any browser.
- **🔄 Two-Way Sharing & Receiving**:
  - **Android to Web**: Select files on Android and host them instantly. Web peers can download them in one click.
  - **Web to Android**: Web peers can drag and drop photos and files into the browser interface to upload them directly to your phone.
- **🔒 AES-256-GCM End-to-End Encryption**: Optional military-grade cryptographic protection with custom passphrases for sensitive transfers.
- **🖼️ Instant MediaStore Integration**: Received photos and videos automatically appear in your Android Gallery/Photos app and Downloads folder instantly.
- **📂 Direct File Actions**: Open downloaded files directly with your device's default apps (Gallery, PDF viewer, media player) or share them via WhatsApp, Drive, and Email.
- **🎨 Liquid Glass UI & Smooth Animations**: Crafted with Jetpack Compose Material Design 3, featuring fluid tab transitions and minimalist translucent styling.

---

## 🛠️ How the App is Made (Architecture & Tech Stack)

Bridge is engineered with modern Android development best practices and high-performance concurrency primitives:

- **Language**: 100% **Kotlin**
- **UI Framework**: **Jetpack Compose** & **Material Design 3 (M3)** with Window Size Classes and Material You dynamic color support.
- **Architecture**: **MVVM (Model-View-ViewModel)** with Kotlin Coroutines and StateFlow for reactive, non-blocking asynchronous operations.
- **Embedded Local HTTP Server**: Custom multi-threaded `ServerSocket` HTTP server (`BridgeHttpServer`) running locally on port `8080`, supporting streaming chunked transfers, multipart file uploads, and Server-Sent Events (SSE).
- **Client & Networking**: **OkHttp** for robust HTTP client requests and streaming downloads with real-time progress callbacks.
- **Cryptography**: Custom `CryptoEngine` implementing AES-256-GCM authenticated encryption for secure stream ciphering.
- **QR Code & Camera**: **CameraX** API combined with a high-performance zxing-based QR code analyzer.
- **Storage & Sharing**: Safe scoped storage management (`StorageManager`) and `FileProvider` for secure URI granting and system app integrations.

---

## 🚀 How to Access & Use the App

### 1. Starting a Share Session (Sending Files from Phone)
1. Open Bridge on your Android device and navigate to the **Share** tab.
2. Tap **"Start Sharing Server"** or tap **"Select Files"** to pick documents, photos, or videos from your device.
3. A high-contrast **QR Code** and local connection URL (e.g., `http://192.168.1.15:8080?token=xyz`) will appear on screen.
4. **On any computer or secondary device**, open any web browser (Chrome, Safari, Firefox, Edge) and either:
   - Scan the QR code with your phone's camera.
   - Type the URL shown in the app.
5. In the web dashboard, you can download shared files or drag-and-drop files to upload them directly back to the phone.

### 2. Receiving Files from Another Device
1. On your Android device, switch to the **Receive** tab.
2. Tap **"Open QR Scanner"** and scan the sender's QR code, or paste their connection URL into the URL input field.
3. Tap **Connect**. The app will instantly display all available files shared by the peer.
4. Select the files you want and tap **"Download"**. Track real-time progress on the **Transfers** tab.

---

## 📂 Where Files Are Saved

- **Incoming Files**: All files received from web peers or remote senders are saved automatically to your device's **`Downloads/Bridge`** directory (with fallback to app-external storage to guarantee write permissions across all Android versions).
- **Gallery Integration**: Images and videos received are immediately scanned by Android's MediaStore scanner so they appear instantly in your phone's native Gallery and Photos applications.
- **In-App Management**: You can tap **"Open"** on any completed transfer or received item to view it instantly using your device's default system handlers.

---

## 📦 How to Download & Export the Project

1. **Export as ZIP**: Open the AI Studio menu / settings and select **Download Project as ZIP**.
2. **Push to GitHub**: Connect your GitHub account via AI Studio or clone the exported repository into Android Studio.
3. **Build & Run**: Open the project in **Android Studio** (Koala / Jellyfish or newer), ensure `ANDROID_SDK_ROOT` is configured, and run on any emulator or physical device.

---

## 📄 License

Built with ❤️ for secure, private, high-speed local sharing.
