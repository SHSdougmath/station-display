# Station Display app

This is a full-screen Android app for the classroom station tablets (Galaxy Tab A SM-T350 and any tablet running Android 6 or newer). It reads the **Station Rotation** Google Sheet web app and shows the station number, the round, the timer, the problem, and a **MOVE!** screen between rounds.

It doesn't need a browser, Fully Kiosk, or a license. You set it as the tablet's Home screen, so the Home button just brings it back.

---

## A. Get GitHub to build the app (one time)

1. On **github.com**, click **New** to make a repository. Name it `station-display`, set it to **Private**, and click **Create repository**.
2. On the new empty repository page, click **uploading an existing file**.
3. Unzip `station-display.zip` on your computer. Open the unzipped folder, select **everything inside it** (including the `.github` folder), and drag it all onto the GitHub page. Click **Commit changes**.
   - Check that `.github/workflows/build.yml` shows up in the repository. If the `.github` folder didn't upload, drag that folder in by itself.
4. Click the **Actions** tab. A build named **Build Station Display app** starts by itself and takes about 3–5 minutes.
5. When it shows a green check, click on the build. Under **Artifacts**, download **StationDisplay-apk**. It's a zip file with **StationDisplay.apk** inside.

### Put your server link in the app (after the Google Sheet is deployed)

1. In the repository, open **`app-config.properties`** and click the pencil icon.
2. Paste your Apps Script web app link after `SERVER_URL=`. It ends in `/exec`.
3. If you want, change `SETTINGS_PIN`. This is the PIN for the hidden teacher menu.
4. Click **Commit changes**. GitHub builds a new APK, which you download the same way as in step 5.

This way, every tablet already knows the link. You can also type or change the link on a tablet in the hidden menu.

---

## B. Install on each tablet (about 3 minutes each)

1. Copy **StationDisplay.apk** onto a microSD card, or onto the tablet with a USB cable.
2. On the tablet, go to **Settings › Lock screen and security** and turn on **Unknown sources**.
3. Open **My Files**, tap **StationDisplay.apk**, and tap **Install**.
4. Press the **Home** button. When Android asks which app to use, choose **Station Display** and then **Always**.
5. The first time it opens, pick **which station** this tablet is, check the server link, and tap **Save**.
6. Under **Settings › Lock screen and security › Screen lock type**, choose **None**. Then after a restart, the tablet goes straight to the station screen.
7. Join the **guest Wi-Fi** if you haven't yet.

After that, you just plug the tablet in. It opens the station screen by itself and keeps the screen on. **Brightness** is the same on every tablet: it comes from **Tablet brightness (%)** in the Sheet's Settings tab.

### Hidden teacher menu

**Press and hold the top-left corner for 3 seconds** and enter the PIN (default **3090**). From there you can:

- change the station number
- change the server link
- set the screen direction: landscape, portrait, or turning with the tablet
- open Wi-Fi or Android settings

### Updating the app later

Download the new APK and install it over the old one. It keeps each tablet's station number and settings, because every build uses the same signing key (`station-key.jks`, already in the repository).

### What the screen shows

| Screen | Meaning |
|---|---|
| Big station number, "Waiting to start" | Ready. Press Start on your controller. |
| Problem with timer | A round is running. The timer turns red in the last 30 seconds. |
| Yellow **MOVE!** with a countdown | Move time. Groups walk to their next station. |
| Red message under the number | No connection, or the server link is wrong. The message says which. |
| Small dot, bottom-right | Green means connected. Red means it's having trouble. |

---

## C. Optional: extra lockdown

Without this step, students can still pull down the notification shade or tap the Recent Apps button. With it, the tablet is locked to the app: no notification shade, no recent apps, no lock screen, and it stays on while plugged in.

You need a computer with **adb** (Android platform-tools), and **no Google accounts** on the tablet (remove them under Settings › Accounts). Then:

1. On the tablet, go to **Settings › About device** and tap **Build number** 7 times. Then go to **Developer options** and turn on **USB debugging**.
2. Plug the tablet into the computer and run:

   ```
   adb shell dpm set-device-owner net.room309.stations/.AdminReceiver
   ```

3. Restart the tablet.

To undo it, open the hidden menu and tap **Remove extra lockdown**.
