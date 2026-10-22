# Hardware Compatibility Matrix — TEMPLATE

**Status: no hardware has been tested.** The table lists the *software* requirements the
application places on each device class. The models columns must be filled in from actual
compatibility testing against the procured and existing equipment (open issues OI-02, OI-03, OI-15).

| Device class | Software requirement | Interface | Models tested | Result | Notes |
|---|---|---|---|---|---|
| Site server (112 units, or VM) | 64-bit Linux, Docker Engine 24+ with Compose v2 (or JRE 21 + PostgreSQL 17 + nginx without Docker), 4 vCPU / 8 GB RAM / 100 GB SSD **proposed minimum, unvalidated** | LAN, static IP, TLS certificate | – | Not tested | Sizing must be confirmed by a load test (OI-01). |
| Interactive kiosk (122 units) | Chromium/Chrome 120+ or Edge 120+, touchscreen as pointer input, auto-start in `--kiosk --kiosk-printing` mode | LAN to the site server | – | Not tested | Lock-down procedure: [kiosk-lockdown.md](kiosk-lockdown.md) (not yet validated on PhilHealth hardware). |
| Thermal printer (80 mm) | OS printer driver that supports 80 mm roll paper and auto-cut, set as the default printer of the kiosk OS account | USB / LAN | – | Not tested | Printed through the browser print dialog. ESC/POS direct printing is not implemented (OI-15). |
| Display TV / LED / monitor | Any screen driven by a small PC or smart-TV browser able to run Chromium 120+ full screen. 1920×1080 recommended. Audio output for the chime/voice | HDMI from a mini-PC, LAN | – | Not tested | Smart-TV built-in browsers vary and must be tested individually. |
| Agent PC | Current Chrome/Edge/Firefox, 1366×768 or larger | LAN | – | Not tested | – |
| Network | HTTPS to the site server on the LAN. Site server → central over the WAN (HTTPS 443 outbound) | – | – | Not tested | Bandwidth: sync traffic is small (on the order of 1–2 KB per ticket event). |
| Virtualization | Any hypervisor that runs the Linux guest above (VMware, Hyper-V, Proxmox/KVM) | – | – | Not tested | – |

Printer setup procedure (browser printing):

1. Install the vendor driver.
2. Create a paper size of 80 × 200 mm or use the "Receipt" preset, and enable cut after each job.
3. Make it the default printer.
4. Start Chrome with `--kiosk --kiosk-printing https://<site-server>/kiosk`.
