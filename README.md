# MT610 Print

Android print service for the **HPRT MT610** Bluetooth thermal printer.
Once set up, the MT610 shows up in Android's normal **Print** menu (Chrome, Gmail,
PDF viewers, Files...), and you can **Share** a PDF or picture straight to the app.
Pages are sized for **8.5 x 11 in Letter** (1728 dots wide at 203 dpi).

## Build
Push this repo to GitHub. The **Build APK** action builds it and puts
`MT610Print.apk` on the repo's **Releases** page (tag `latest`).
Every build uses the same signing key, so new versions install over old ones.

## First-time setup on the phone
1. Pair the printer in Android Bluetooth settings (it shows as `MT610-xxxx`).
2. Install the APK, open **MT610 Print**, allow *Nearby devices*.
3. Pick the printer, then find the working mode:
   - Leave mode **1** selected, tap **Print test**.
   - If the strip says **MODE 1 WORKS**, done.
   - If it prints junk or nothing: switch the printer off/on, pick mode 2, test again, and so on.
4. Tap **Open Android print settings**, turn on **MT610 Print**.
5. Print from any app: Print -> choose the MT610 -> paper size Letter.

## Print quality
Every page goes through a thermal-paper cleanup before printing:
- paper color, yellowing and phone-camera shadows are flattened to white
- faded, gray and thin text is made solid black (adaptive threshold)
- gray shading in forms becomes a light, even dot pattern behind the text
- photos get auto-contrast, sharpening and Atkinson dithering

**Processing** - *Auto* (default) handles text, forms and photos on the same page.
*Document* forces sharp text everywhere, *Photo* forces smooth shading,
*Simple* is plain black/white.
**Darkness** - slide right for darker. **Extra bold** - thickens text and lines.
Open a file in the app to see an exact preview before printing.
The same processing is used when printing from Android's Print menu.
- **After each page** - use *Form feed* if your paper has page marks and pages drift.
- **Rows per block** - lower it if long pages print cut off or garbled.
- **300 dpi** - only for the MT610 **Pro**.

## If no mode works
The printer's command language isn't published. Capture what HPRT's own app sends:
1. Settings -> About phone -> Software information -> tap *Build number* 7 times.
2. Developer options -> turn on **Enable Bluetooth HCI snoop log**, then toggle Bluetooth off/on.
3. Print one page from HPRT's **HerePrint** app.
4. Developer options -> **Take bug report** (full) and share the zip. The log inside
   (`btsnoop_hci.log`) shows the exact bytes, and the app can be updated to match.
