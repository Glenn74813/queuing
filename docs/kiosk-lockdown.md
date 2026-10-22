# Kiosk and Display Device Lock-down

Procedure for preparing the interactive kiosks (`https://<site-host>/kiosk`) and queue displays
(`https://<site-host>/display`) as locked-down, single-purpose devices (open issue OI-16).

> **Status: draft procedure, not yet validated on PhilHealth hardware or OS images.** It is based
> on what the QMS frontend needs and on the vendors' documented kiosk features. Every step must be
> tried on the procured kiosk, display PC and thermal printer models, and the results recorded in
> [hardware-compatibility.md](hardware-compatibility.md), before roll-out. Settings named here
> (policy names, menu paths, package names) must be checked against the exact OS build in use.

## What the QMS pages need from the device

| Need | Why | Applies to |
|---|---|---|
| Chromium-based browser 120+ (Google Chrome, Chromium, Microsoft Edge), full screen | Supported browsers ([hardware-compatibility.md](hardware-compatibility.md)) | Both |
| A **persistent** browser profile | The device key entered on the setup screen is kept in the browser's `localStorage` (`qms.deviceKey`). An incognito, guest or InPrivate session, or a profile wiped at each start, loses the key and shows the setup screen again. | Both |
| Silent printing to the default printer: `--kiosk-printing` | The kiosk calls the browser's print function after a ticket is issued; the ticket layout is 80 mm wide (`@page { size: 80mm auto }`). | Kiosk |
| Audio autoplay: `--autoplay-policy=no-user-gesture-required` | The display plays a chime (and optional voice) without a click. | Display |
| HTTPS to the site server only, trusting PhilHealth's CA | All pages and the WebSocket (`/ws`) are served by the site node. A certificate warning page would block the device. | Both |
| No sleep or screen blanking | Displays must stay on all day; kiosks must wake on touch. | Both (mainly display) |

The pages recover on their own after network problems: the display reconnects its WebSocket, polls
every 15 s, and reloads itself after 5 minutes without a successful request; the kiosk returns to
its home screen after 20 s without interaction. The lock-down therefore only has to keep the
browser running full screen on the right URL.

Browser command lines used throughout this document (`<site-host>` is the site server's host name
on its TLS certificate):

```text
# Kiosk
<browser> --kiosk --kiosk-printing --noerrdialogs --disable-session-crashed-bubble --disable-pinch
          --overscroll-history-navigation=0 --no-first-run --user-data-dir=<persistent profile dir>
          https://<site-host>/kiosk
# Display
<browser> --kiosk --noerrdialogs --disable-session-crashed-bubble --disable-pinch
          --overscroll-history-navigation=0 --no-first-run --autoplay-policy=no-user-gesture-required
          --user-data-dir=<persistent profile dir> https://<site-host>/display
```

## Common steps (any OS)

### Firmware (BIOS/UEFI)

1. Set a supervisor (administrator) password; record it in PhilHealth's credential vault, not on
   the device.
2. Boot order: internal disk only. Disable boot from USB, network (PXE) and optical media, and
   disable the one-time boot menu hot key where the firmware allows it.
3. Enable Secure Boot.
4. Set "restore on AC power loss" to **Power on**, so the device restarts by itself after an outage.
5. Lock the chassis or enclosure (cable lock, locked kiosk cabinet). Cover or remove exposed USB
   ports that are not needed for the touchscreen, printer or display.

### Browser policies

Apply as machine policy (Windows: Group Policy / registry with the Chrome or Edge ADMX templates;
Linux: JSON file in the browser's managed-policy directory, see below). Confirm they are active on
`chrome://policy` (or `edge://policy`) before the URL allowlist blocks that page.

| Policy | Value | Purpose |
|---|---|---|
| `URLBlocklist` | `["*"]` | Block everything … |
| `URLAllowlist` | `["https://<site-host>"]` | … except the site server |
| `DeveloperToolsAvailability` | `2` | No developer tools |
| `IncognitoModeAvailability` | `1` | No incognito windows |
| `BrowserSignin` | `0` | No browser sign-in or sync |
| `PasswordManagerEnabled` | `false` | Do not offer to save the device key or passwords |
| `DownloadRestrictions` | `3` | Block all downloads |
| `TranslateEnabled` | `false` | No translation prompt over the UI |
| `PrintPreviewUseSystemDefaultPrinter` | `true` | Kiosk: print to the OS default printer, not the last-used one |
| `AutoplayAllowed` | `true` | Display: allow the chime (in addition to the command-line flag) |

### Device-key enrollment

1. An administrator registers the device: **Devices** › **Register device**, type `KIOSK` or
   `DISPLAY`. The API key is shown once ("Copy this key now — it will not be shown again.").
2. On the device, with the lock-down in place and the browser on `/kiosk` or `/display`, the
   **Kiosk setup** / **Queue display setup** screen appears. Attach a USB keyboard temporarily,
   enter the key in **Device API key** and press **Save and start**. The key is verified with the
   server and stored in `localStorage` under `qms.deviceKey`.
3. Remove the keyboard. Restart the device and confirm the page opens without the setup screen
   (this proves the profile is persistent).
4. To reset a device (for example after **Rotate key** in the Devices page), press and hold the
   **top-left corner of the screen for 5 seconds** and confirm. The key is removed and the setup
   screen appears.

Treat the key like a password: do not write it on the device. If a device is lost or replaced,
deactivate it (**Edit** › clear **Active**) or rotate its key.

### Thermal printer (kiosk)

1. Install the vendor's OS driver and connect the printer (USB or LAN).
2. Set the paper size to the 80 mm roll (vendor "Receipt" / 80 × 200 mm preset or a custom 80 mm
   size) and enable cut after each job.
3. Make it the **default printer** of the kiosk account, and stop the OS from changing the default
   (see the OS sections).
4. Start the browser with `--kiosk-printing`, issue a test ticket, and check that it prints without
   a dialog, at full width, and is cut.

Printing goes through the browser and the OS driver. Paper-out detection, printer status and direct
ESC/POS printing are not available (OI-15); staff must check the paper during the day.

## Windows 10/11 (IoT Enterprise, Enterprise, Pro)

### Choice of kiosk mechanism

| Mechanism | Editions | Suitability |
|---|---|---|
| **Shell Launcher v2** with Chrome (or Edge) as the custom shell | IoT Enterprise, Enterprise, Education | **Recommended for kiosks and displays.** Runs the browser with the flags above instead of Explorer, with an automatic restart action. |
| Assigned Access, single-app kiosk with Microsoft Edge ("digital/interactive signage" or "public browsing") | Pro, Enterprise, Education, IoT Enterprise | **Not recommended.** Edge's kiosk mode runs InPrivate sessions, which discard `localStorage` (the device key) when the session restarts, and it does not take custom flags such as `--kiosk-printing`. Use only if a trial shows the key persists. |
| Auto-logon + scheduled task + Group Policy restrictions | Pro | Fallback when only Pro is licensed. Explorer still runs underneath, so it is weaker; rely more on the restrictions below. |

### Shell Launcher v2 (recommended)

1. Create a local **standard** user, for example `qmskiosk`, with a long random password stored in
   the credential vault. It must not be an administrator.
2. Install Google Chrome (enterprise MSI) or use Edge. Apply the browser policies above.
3. Enable the feature (administrator PowerShell):
   `Enable-WindowsOptionalFeature -Online -FeatureName Client-EmbeddedShellLauncher`
4. Set the custom shell for `qmskiosk` through the Shell Launcher WMI provider
   (`root\standardcimv2\embedded:WESL_UserSetting`, method `SetCustomShell`) or through MDM
   (AssignedAccess CSP with a Shell Launcher configuration). Shell: the kiosk or display command
   line above, with `--user-data-dir=C:\QMS\profile` (a folder the kiosk user can write to).
   Default action on shell exit: **restart the shell** (value `0`). Keep Explorer as the shell for
   administrators.
5. Configure auto-logon for `qmskiosk` (Sysinternals Autologon stores the password encrypted;
   do not use plain-text `DefaultPassword` registry values).

### Fallback for Windows Pro (auto-logon + scheduled task)

1. Standard user `qmskiosk` and auto-logon as above.
2. A scheduled task, trigger **At log on** of `qmskiosk`, running a small PowerShell loop that
   starts the browser with the command line above and starts it again 5 seconds after it exits.
   In the task settings, enable **If the task fails, restart every 1 minute**.
3. Apply all restrictions in the next section; there is no replacement shell.

### Restrictions (Group Policy for the kiosk user or the machine)

| Area | Setting |
|---|---|
| Ctrl+Alt+Del screen | User Configuration › Administrative Templates › System › Ctrl+Alt+Del Options: **Remove Task Manager**, **Remove Lock Computer**, **Remove Change Password**, **Remove Logoff** = Enabled |
| Edge swipes (touch) | Computer Configuration › Administrative Templates › Windows Components › Edge UI › **Allow edge swipe** = Disabled |
| Key combinations (IoT Enterprise / Enterprise) | Enable **Keyboard Filter** (`Client-KeyboardFilter` feature) and block Windows-key combinations, Alt+Tab, Alt+F4, Ctrl+Esc and the Ctrl+Alt+Del screen for the kiosk account. On Pro, keep no keyboard attached. |
| Notifications and tips | Turn off toast notifications, "Windows Spotlight" and consumer experiences for the kiosk user. |
| Default printer | Turn on **Turn off Windows default printer management** (Printers policy), so Windows does not switch the default to the last-used printer. |
| Removable storage | Computer Configuration › Administrative Templates › System › Removable Storage Access › **All Removable Storage classes: Deny all access** = Enabled. Check that the touchscreen, USB printer and temporary enrollment keyboard still work (they are not storage devices). |
| Sleep and screen blanking | `powercfg /change monitor-timeout-ac 0`, `powercfg /change standby-timeout-ac 0`, `powercfg /change hibernate-timeout-ac 0`; disable the screen saver by policy. |
| Firewall | Windows Defender Firewall: block outbound by default for the kiosk profile; allow TCP 443 (and 80, redirected to HTTPS) to the site server's address, DNS to the site's resolvers (or a `hosts` entry for `<site-host>`), NTP, and the update server (WSUS) if used. Prefer also restricting at the switch/VLAN, coordinated with PhilHealth network administrators. |
| Certificate trust | Import PhilHealth's root CA into **Local Computer › Trusted Root Certification Authorities** (Chrome and Edge use the Windows store). |

### Updates (Windows)

* OS: Windows Update for Business or WSUS, per PhilHealth ICT policy. Schedule installation and
  restarts **after office hours** (Configure Automatic Updates, option "Auto download and schedule
  the install", with a scheduled time outside service hours); set active hours to cover service hours.
* Browser: control Chrome or Edge updates with their update policies (Google Update / Edge Update
  ADMX). Allow updates within the major versions tested with QMS, and test a new major version on
  one device first.

## Ubuntu (24.04 LTS)

Two session options. Both use a dedicated user and restart the browser automatically.

| Option | Description |
|---|---|
| A. **cage** (recommended) | `cage` is a Wayland compositor that runs a single full-screen application and nothing else: no desktop, panels or window switching, and VT switching is disabled unless started with `-s`. |
| B. **Openbox** | Minimal X11 window manager with display-manager auto-login, for hardware where Wayland does not work well. More keyboard shortcuts have to be removed by hand. |

### Base system

1. Install Ubuntu 24.04 (Server or minimal Desktop install), all updates, and the browser:
   Chromium (`snap install chromium`) or Google Chrome (`.deb` from Google's repository).
2. Create the kiosk user, without sudo and with a locked password:
   `adduser --disabled-password --gecos "QMS kiosk" kiosk`
3. Browser policies (JSON file with the table above), for example
   `/etc/chromium-browser/policies/managed/qms.json` (Ubuntu Chromium snap) or
   `/etc/opt/chrome/policies/managed/qms.json` (Google Chrome). Check them on `chrome://policy`.
4. Trust PhilHealth's CA: `/usr/local/share/ca-certificates/philhealth-ca.crt` and
   `update-ca-certificates`, **and** add it to the browser's NSS database of the kiosk user
   (`certutil -d sql:<profile>/.pki/nssdb -A -t "C,," -n "PhilHealth CA" -i philhealth-ca.crt`,
   package `libnss3-tools`; the snap keeps it under `~/snap/chromium/current/.pki/nssdb`). Verify by
   opening the site with no certificate warning.
5. Disable the console Ctrl+Alt+Del reboot and the magic SysRq key:
   `systemctl mask ctrl-alt-del.target`; `echo 'kernel.sysrq = 0' > /etc/sysctl.d/90-qms-kiosk.conf`.
6. Block USB storage (keyboard, touchscreen and printer keep working):
   ```text
   # /etc/modprobe.d/qms-no-usb-storage.conf
   install usb-storage /bin/false
   install uas /bin/false
   blacklist usb-storage
   blacklist uas
   ```
   then `update-initramfs -u` and reboot.
7. Firewall (`ufw`): `ufw default deny incoming`, `ufw default deny outgoing`, then allow outgoing
   TCP 443 and 80 to the site server, UDP/TCP 53 to the site's DNS resolvers, UDP 123 for NTP, and
   the update mirror (or a local mirror) during the maintenance window. Add SSH from the support
   network only if remote support is approved. Prefer also restricting at the switch/VLAN.

### Option A: cage

Install `cage` (`apt install cage`) and create `/etc/systemd/system/qms-kiosk.service`:

```ini
[Unit]
Description=QMS kiosk browser (cage)
After=systemd-user-sessions.service network-online.target
Wants=network-online.target
Conflicts=getty@tty1.service

[Service]
User=kiosk
PAMName=login
TTYPath=/dev/tty1
StandardInput=tty
UtmpIdentifier=tty1
UtmpMode=user
Environment=XDG_SESSION_TYPE=wayland
# Display: replace --kiosk-printing with --autoplay-policy=no-user-gesture-required and /kiosk with /display.
ExecStart=/usr/bin/cage -d -- /snap/bin/chromium --kiosk --kiosk-printing --noerrdialogs \
    --disable-session-crashed-bubble --disable-pinch --overscroll-history-navigation=0 \
    --no-first-run https://<site-host>/kiosk
Restart=always
RestartSec=5

[Install]
WantedBy=graphical.target
```

`systemctl set-default graphical.target`, `systemctl enable qms-kiosk.service`, reboot. If the
browser or cage exits for any reason, systemd starts it again after 5 seconds. The snap keeps the
profile in `~kiosk/snap/chromium/`; for Google Chrome add `--user-data-dir=/home/kiosk/.config/qms-browser`.

### Option B: Openbox with auto-login

1. `apt install openbox lightdm x11-xserver-utils` and enable auto-login in
   `/etc/lightdm/lightdm.conf.d/50-qms-kiosk.conf`:
   ```ini
   [Seat:*]
   autologin-user=kiosk
   autologin-session=openbox
   ```
2. Remove all key and root-menu bindings: copy `/etc/xdg/openbox/rc.xml` to
   `~kiosk/.config/openbox/rc.xml` and empty its `<keyboard>` section and the `Root` and `Desktop`
   contexts in `<mouse>`.
3. Prevent VT switching and X server zapping: `/etc/X11/xorg.conf.d/90-qms-kiosk.conf`
   ```text
   Section "ServerFlags"
       Option "DontVTSwitch" "true"
       Option "DontZap" "true"
   EndSection
   ```
4. Browser as a systemd **user** service, `~kiosk/.config/systemd/user/qms-browser.service`:
   ```ini
   [Unit]
   Description=QMS kiosk browser
   [Service]
   ExecStart=/snap/bin/chromium --kiosk --kiosk-printing --noerrdialogs --disable-session-crashed-bubble --disable-pinch --overscroll-history-navigation=0 --no-first-run https://<site-host>/kiosk
   Restart=always
   RestartSec=5
   ```
5. `~kiosk/.config/openbox/autostart`:
   ```sh
   xset s off; xset s noblank; xset -dpms
   systemctl --user import-environment DISPLAY XAUTHORITY
   systemctl --user start qms-browser.service
   ```
6. Make the kiosk user's configuration read-only for that user (`chown -R root:root
   ~kiosk/.config/openbox ~kiosk/.config/systemd`), so it cannot be changed from the session.

### Screen blanking (displays)

* cage: no screen blanking is configured by default; do not install an idle daemon (`swayidle`).
* Openbox: the `xset` line in `autostart` above.
* Console: add `consoleblank=0` to `GRUB_CMDLINE_LINUX_DEFAULT` in `/etc/default/grub`, then `update-grub`.
* Disable suspend: `systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`.

### Printing (Ubuntu kiosk)

Install the vendor CUPS driver, then set the printer as default and its default media:
`lpadmin -d <printer>` and `lpoptions -p <printer> -o media=<80 mm roll size name from the driver>`.
Confirm the snap can print (`snap connections chromium` lists `cups-control` or `cups`).

### Updates (Ubuntu)

* `unattended-upgrades` for security updates, with `Unattended-Upgrade::Automatic-Reboot "true";`
  and `Unattended-Upgrade::Automatic-Reboot-Time` set after office hours.
* Snap browser: limit refreshes to the maintenance window, for example
  `snap set system refresh.timer=mon-fri,19:00-21:00`, or hold refreshes of `chromium` while a new
  version is tested on one device.

## Verification checklist

Record the results per device model in [hardware-compatibility.md](hardware-compatibility.md).

| # | Check | Expected | Kiosk | Display |
|---|---|---|---|---|
| 1 | Power on | Boots straight into the full-screen QMS page, no desktop, no sign-in prompt | ☐ | ☐ |
| 2 | Pull the power, restore it | Device powers on and returns to the page | ☐ | ☐ |
| 3 | Restart after enrollment | No setup screen (device key kept) | ☐ | ☐ |
| 4 | Kill the browser (or Alt+F4 with a keyboard attached) | Browser restarts on the page within about 10 s | ☐ | ☐ |
| 5 | Keyboard: Alt+Tab, Windows key, Ctrl+Alt+Del, Ctrl+Alt+F1–F7, Ctrl+N, Ctrl+T | No other application, desktop, console or new window is reachable; anything that closes the browser leads to check 4 | ☐ | ☐ |
| 6 | Touch: edge swipes, pinch, two-finger swipe back, long press | No OS panels, no zoom, no history navigation, no context menu | ☐ | ☐ |
| 7 | Open another URL (e.g. via a link or typed with a keyboard) | Blocked by policy | ☐ | ☐ |
| 8 | Insert a USB flash drive | Not mounted, not accessible | ☐ | ☐ |
| 9 | Boot from USB / open the boot menu | Not possible without the firmware password | ☐ | ☐ |
| 10 | Network: reach any host other than the site server (e.g. from a support shell) | Blocked | ☐ | ☐ |
| 11 | Certificate | Page loads over HTTPS with no warning | ☐ | ☐ |
| 12 | Issue a printed ticket | Prints silently on the thermal printer, 80 mm, cut | ☐ | – |
| 13 | Issue 20 tickets in a row | All print; no dialogs appear | ☐ | – |
| 14 | Leave idle 30 min (display: through a whole day) | Screen stays on; display keeps updating; kiosk returns to home after 20 s idle | ☐ | ☐ |
| 15 | Call a ticket from the Agent Console | Display shows it and plays the chime (voice if switched on) | – | ☐ |
| 16 | Unplug the network for 2 minutes, then reconnect | Page recovers by itself (kiosk shows "Connection problem" while offline) | ☐ | ☐ |
| 17 | Hold the top-left corner for 5 s and confirm | Setup screen appears; re-enter the key | ☐ | ☐ |
| 18 | Maintenance window | Updates install and the device restarts outside office hours, then passes check 1 | ☐ | ☐ |
