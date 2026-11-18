# 📱 Native Android Starter App (Kotlin + GitHub Actions CI)

A lightweight, modern native Android application built using **Kotlin**, **Material 3**, and **ViewBinding**, pre-configured with **GitHub Actions CI/CD** to automatically build and generate APKs in the cloud.

---

## 🚀 How to Build APK on GitHub (Zero Local Setup)

Follow these simple steps to push this code to your GitHub account and download the compiled APK:

### 1. Create a New Repository on GitHub
1. Open [github.com/new](https://github.com/new) in your browser.
2. Enter a repository name (e.g. `native-android-starter`).
3. Keep it **Public** (or Private).
4. Do **not** check "Add a README file" (we already have one).
5. Click **Create repository**.

---

### 2. Push this Code from Termux to GitHub

Run these commands inside this project directory:

```bash
cd /data/user/0/com.termux/files/home/.gemini/antigravity-cli/scratch/native-android-starter

# Initialize git repository
git init -b main

# Add all files
git add .

# Commit changes
git commit -m "Initial commit: Native Android Kotlin app with GitHub Actions"

# Link your GitHub repository (replace with your repo URL)
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO_NAME>.git

# Push to GitHub
git push -u origin main
```

---

### 3. Download the Generated APK

1. Go to your repository on GitHub in your browser.
2. Click on the **Actions** tab at the top.
3. You will see a workflow running named **"Build Android APK"**.
4. Wait ~2-3 minutes for the green checkmark (Build passed).
5. Click on the completed workflow run.
6. Scroll down to the **Artifacts** section at the bottom.
7. Click on **`NativeStarterApp-Debug-APK`** to download your ready-to-install `.apk` file!

---

## 🛠️ Project Structure

```
├── .github/workflows/
│   └── build-apk.yml          # Automated CI/CD script for cloud APK generation
├── app/
│   ├── build.gradle.kts       # App module config (SDK 34, Kotlin, dependencies)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/starterapp/
│       │   └── MainActivity.kt # Kotlin logic & interactive click listener
│       └── res/
│           ├── layout/activity_main.xml # Material 3 responsive layout
│           └── values/ (colors, strings, themes)
├── gradle/wrapper/            # Gradle 8.7 wrapper
├── build.gradle.kts           # Root build configuration
└── settings.gradle.kts        # Repository management
```
