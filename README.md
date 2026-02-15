# HelioLAN

HelioLAN lets you see your phone health data on a laptop browser over your home Wi-Fi. It is built for quick daily check-ins and sharing a bigger screen view.

Repository: https://github.com/AJMSD/HelioLan
README: https://github.com/AJMSD/HelioLan/blob/main/README.md

## What You Need

- Android phone
- Zepp app connected to your watch/band
- Health Connect on your phone
- Laptop (or desktop) with a web browser
- Phone and laptop on the same Wi-Fi

HelioLAN currently supports Android only. iOS/Apple is not supported because Apple platform restrictions do not allow this same local-dashboard + background-sync flow.

## Setup

1. Install and open HelioLAN on your Android phone.
2. Follow the in-app setup screen from top to bottom.
3. Open Zepp and make sure your latest data has synced.
4. Return to HelioLAN and allow the requested Health Connect access.
5. Set a 4-8 digit passcode when prompted (recommended).
6. Tap to start the dashboard in HelioLAN.
7. Wait until HelioLAN shows a dashboard web address.

## Permissions (What to Allow and Why)

- Health data access: lets HelioLAN read your steps, heart, sleep, and related health stats.
- Background run permission (if asked): keeps the dashboard available while your phone screen is off.
- Network access: lets your laptop open the dashboard from your phone.

If you deny a permission, that part of the dashboard can appear empty.

## Open It on Your Laptop

1. Keep HelioLAN open on your phone and make sure the dashboard is running.
2. On your laptop browser, enter the web address shown in HelioLAN.
3. If HelioLAN shows a QR code, you can scan it instead.
4. Enter your passcode if asked.

"Same Wi-Fi" means both devices are connected to the same home router/network name.

## How to Tell It's Working

- You can open the dashboard page on your laptop.
- You can sign in with your passcode (if enabled).
- The "Last synced" time updates.
- You can see values on Today, Sleep, Cardio, Activity, and Nutrition.

## Known Limitations

- Data freshness can lag (often around 15-20 minutes, sometimes longer) because HelioLAN can only read what Zepp has already written into Health Connect.
- Metric coverage is limited to what Zepp exposes to Health Connect and what permissions you grant.
- Sync is not truly real-time. Android background limits (Doze/OEM battery optimizers) can delay updates.
- If Zepp, Health Connect, and HelioLAN are not allowed to run properly in background, updates can become slow.
- Better background behavior usually costs more battery.
- Older historical data may be limited without Health Connect history permission.

## Troubleshooting

- Symptom: Laptop cannot open the page.
  Fix: Check both devices are on the same Wi-Fi and HelioLAN is still running on the phone.

- Symptom: Login keeps failing.
  Fix: Re-enter the passcode you set in HelioLAN. If needed, update passcode in Settings.

- Symptom: Dashboard opens but shows little/no data.
  Fix: Open Zepp first, let it sync, then run sync again in HelioLAN.

- Symptom: Some cards say data is unavailable.
  Fix: Re-check Health Connect permissions in HelioLAN and try sync again.

- Symptom: Dashboard stops after a while.
  Fix: Keep HelioLAN running and disable battery-saving restrictions for the app.

## Personal Notes

- This setup has been tested on an older secondary Android phone, not a primary daily-use phone.
- A MacroDroid automation was used to try improving Zepp -> Health Connect freshness:
  - Open Zepp on a locked phone for ~10 seconds.
  - Return to Home automatically.
- In practice, this only showed limited improvement, but it may still help in some device/app combinations.
- This approach will not be practical for everyone, so treat it as an optional workaround.

## Privacy Promise

By default, HelioLAN keeps your data on your phone and your local Wi-Fi network. Nothing is sent to outside services unless you choose to export/share it yourself.

