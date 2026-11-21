# F&O EMA for Android

The app charts NSE cash-market closes and EMA(9) for 236 stocks matched to stock futures in Angel One's instrument master on 21 November 2026. The bundled catalog is a snapshot. Use the stock chooser and switch among 1-, 5-, and 15-minute candles. The default is RELIANCE on five minutes.

## Try it

1. Install the test APK on Android 8.0 or later and open **F&O EMA**.
2. Try the chart in demo mode with simulated prices.
3. To request live data, open **Connect Angel One**. Enter your SmartAPI key, Angel One client code and PIN, current six-digit TOTP, and public IP in the app. If your account enforces an IP allowlist, the connection has to originate from the allowed IP. The IP field supplies a request header.
4. During NSE market hours, the app requests a new quote about every ten seconds while open. Historical candles are reloaded about every thirty seconds. The quote's exchange trade time appears under the chart. Fresh trades in the current regular NSE session can update the open candle, so its EMA may change before the candle closes.

The app sends the login details directly to Angel One over HTTPS and keeps the SmartAPI session in memory. Closing the app process ends the local session. The app has no order controls.

## Build the source

Open this directory in Android Studio, use its bundled JDK 17 or newer, and install Android SDK Platform 35. Sync Gradle and run the app on a phone or emulator. From the terminal, configure the Android SDK and JDK, then run `./gradlew :app:assembleDebug` (`gradlew.bat :app:assembleDebug` on Windows).

EMA starts at the mean of the first nine closes from the historical response. Each subsequent close receives a weight of 0.2. The chart displays up to 80 candles and uses IST for timestamps.
