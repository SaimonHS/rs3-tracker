# RS Tracker

An Android app that makes a **DJI RS 3 Mini** gimbal follow you, using the camera picture you already see in Sony's **Monitor & Control** app (made for a ZV-E10 II).

The phone doesn't need to be mounted on the gimbal: the app looks at the Sony's own picture on your screen, finds you in it, and steers the gimbal over Bluetooth, the same way the Ronin app's virtual joystick does.

## Install

1. On your Android phone, open **[Releases → latest](../../releases/latest)**.
2. Tap **RS-Tracker.apk** to download it, then open the download.
3. If Android asks, allow your browser to install apps, then tap **Install**.

New builds appear there automatically; installing a newer one updates the app.

## Use

1. Connect the ZV-E10 II in Monitor & Control as usual.
2. Close the DJI Ronin app (the gimbal only talks to one app at a time).
3. Open RS Tracker → **Find gimbal** → pick your RS 3 Mini.
4. Tap **Start tracking**. When Android asks what to share, pick **Entire screen** (simplest) or **Monitor & Control**.
5. Monitor & Control opens with a small floating RS Tracker panel. Step into the picture.
6. First time only: tap **Learn directions** on the panel while standing still in the picture.

The floating panel has **Track/Pause**, **Recenter** and a joystick for steering by hand. Touching the joystick pauses tracking.

### Settings

| Setting | What it does |
| --- | --- |
| Whole person / Face | Follow your body (works far away and turned around) or your face (close-up talking shots). |
| Keep in frame | Which part of you sits on your spot: head, upper body or whole body. |
| Max speed | Fastest the gimbal may turn while tracking. |
| Response | How hard it reacts. Lower it if it swings back and forth. |
| Smoothness | Softens starts and stops. |
| Dead zone | How far you can move before it turns. |
| Your spot | Where in the picture you want to be. |

## How it works

- **Seeing:** Android screen sharing gives the app the Monitor & Control screen. It finds the camera picture on it (the non-black area) and runs Google MediaPipe person or face detection on the phone. Nothing leaves the phone.
- **Steering:** it sends the RS 3 Mini the same Bluetooth joystick command the DJI Ronin app sends, about 10 times a second, and stops the gimbal whenever it loses you or the picture.

## Unofficial

DJI doesn't publish a way for other apps to control the RS 3 Mini. The Bluetooth frame format, checksums and start-up messages used here were decoded from the Ronin app by the open-source [rs3mini-bridge](https://github.com/b-rutledge-code/rs3mini-bridge) project. This app isn't made, approved or supported by DJI or Sony, and a gimbal firmware update could stop it from working. Keep a hand near the gimbal the first time you use it.

## Building

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`) and published under Releases. You can also open the project in Android Studio. The detection models are downloaded during the build.

`app/debug.keystore` is a throwaway signing key so that new builds install over old ones. It doesn't protect anything.
