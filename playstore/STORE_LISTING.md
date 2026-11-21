# AndroidMeasure — Google Play Store Listing Pack

## App Name (max 30 chars)
AndroidMeasure

## Short Description (max 80 chars)
Free, zero-data AR tape measure: lines, perimeters, areas, boxes, angles & level

## Full Description
Measure real-world objects quickly and privately right from your Android camera.

AndroidMeasure uses on-device ARCore depth and multi-view 3D geometry to turn your phone into a clean, accurate measuring toolkit. It is completely free, has zero ads, and collects zero data.

### Measurement Tools
- **Line:** Point-to-point distance with 3D ruler tick marks, horizontal/vertical guides, and multi-view triangulation refinement.
- **Perimeter & Area:** Chain multiple edges to measure running length, or magnetically snap back to the first pin to close a loop and measure enclosed area on flat or angled surfaces.
- **3D Box & Volume:** Tap 4 points (corner, length, width, height) to measure L x W x H and volume in cm³, liters, in³, or ft³.
- **Angle Protractor:** Tap 3 points to measure interior and exterior angles in degrees.
- **Vertical Height:** Measure pure vertical height from a floor or tabletop.
- **Digital Spirit Level:** Built-in surface and edge level with one-tap zero tare.

### Built for Precision & Ease of Use
- **Magnifier Loupe & Smart Corner Snap:** Zoom in 2x, 4x, or 6x while aiming and lock cleanly onto sharp corners.
- **Magnetic Pin Snapping:** Join lines and close perimeters cleanly without gaps.
- **Metric & Imperial Units:** Auto Metric, mm, cm, m, decimal inches, carpenter fractional inches (1/16"), and feet & inches.
- **Optional Scale Calibration:** Calibrate against a credit card, coin, A4/Letter paper, or custom length to remove camera scale drift.
- **Photo Snapshots & Exports:** Snap clean annotated photos directly to your gallery, rename measurements, copy dimensions as text, or export CSV and A4 PDF spec sheets.
- **Labs Overlays (Optional in Settings):** Live Depth Heatmap, 3D Point Cloud, Detected 3D Planes, Multi-View 3D Rays, and Sensor Telemetry HUD.

### 100% Free & Zero Data Collection
- No internet permission in the app manifest.
- No ads, no subscriptions, no accounts, no analytics, and no telemetry.
- Everything runs offline on your phone.

*Requires an ARCore-supported Android device with a rear camera.*

---

## Release Notes (v1.0.0)
- Initial release with Line, Perimeter/Area, 3D Box/Volume, Angle, Height, and Spirit Level modes.
- One-tap annotated photo snapshots saved to Pictures/AndroidMeasure, plus PDF and CSV export.
- Full Settings screen, metric/imperial fractional units, reference scale calibration, and optional Labs developer overlays.
- 100% free and offline with zero data collection.

---

## Google Play Console — Data Safety Answers
- **Does your app collect or share any of the required user data types?** -> **No**
- **Permissions requested:** `CAMERA` (required for ARCore 3D measuring), `VIBRATE` (optional haptics).
- **Network / Internet permissions:** None.
- **Privacy Policy URL:** Upload `playstore/privacy_policy.html` to GitHub Pages (free) and paste the HTTPS URL into Play Console -> App Content -> Privacy Policy.

---

## Pre-Upload Checklist
1. Update `feedback_email` in `app/build.gradle.kts` to your real support email address.
2. Create an upload keystore (`keytool -genkey -v -keystore release.jks ...`) and store it safely offline.
3. Build the signed release App Bundle:
   `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:bundleRelease`
4. Upload `app/build/outputs/bundle/release/app-release.aab`, `playstore/icon_512.png`, `playstore/feature_graphic_1024x500.png`, and 2–4 phone screenshots taken directly on your Pixel 8a.
