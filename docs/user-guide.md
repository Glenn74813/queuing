# User Guide

For Servicing Agents, Supervisors, LHIO administrators and Reporting officers using the staff web
application at `https://<site-host>/`. It describes the screens as implemented in the v0.1 frontend
(`frontend/src`); button names are quoted exactly as they appear. Kiosk and display set-up is
covered in [kiosk-lockdown.md](kiosk-lockdown.md) and [operations.md](operations.md).

## What you see depends on your role

The left menu shows only the pages your role allows. After signing in you are taken to the first
page in the menu.

| Menu group | Page | Shown with permission | Typical roles |
|---|---|---|---|
| Queue | Dashboard | QUEUE_VIEW | All site staff, reporting officers |
| Queue | Agent Console | QUEUE_SERVE | Servicing Agent, Supervisor |
| Queue | Queue Monitor | QUEUE_VIEW or QUEUE_SUPERVISE | Supervisor, administrators |
| Administration | Regions & Sites | SITE_MANAGE or REGION_MANAGE | Central / Regional administrators |
| Administration | Users | USER_VIEW or USER_MANAGE | Administrators (incl. LHIO) |
| Administration | Service Catalog | CATALOG_MANAGE | Central administrators |
| Administration | Site Services | SITE_SERVICE_MANAGE | LHIO / Regional administrators |
| Administration | Priority Categories | PRIORITY_MANAGE | Central administrators |
| Administration | Counters | COUNTER_MANAGE | LHIO / Regional / System administrators |
| Administration | Devices | DEVICE_MANAGE | Administrators, Technical support |
| Content & Reports | Content | CONTENT_EDIT or CONTENT_APPROVE | Supervisor, administrators |
| Content & Reports | Reports | REPORT_VIEW | Supervisor, administrators, Reporting officer |
| System | Audit Log | AUDIT_VIEW | Administrators, Reporting officer |
| System | System & Sync | SYSTEM_MONITOR or SYNC_MANAGE | System administrator, Technical support |

The full permission matrix is in [database.md](database.md#application-roles-v2). If you see "Your
account has no permissions assigned yet. Contact your administrator.", ask an administrator to
assign you a role.

**Site selector.** Site-based pages (Dashboard, Agent Console, Queue Monitor, Site Services,
Counters, Devices) work on the site chosen in **Site** in the top bar. If no site is listed, you
have not been assigned to one.

## Signing in

1. Open `https://<site-host>/`, enter **Username** and **Password**, and select **Sign in**.
2. If an administrator created your account or reset your password, the **Change password** screen
   appears with "You must set a new password before continuing." Enter **Current password** (the
   temporary one), **New password** and **Confirm new password**, then select **Change password**.
   The new password needs at least 12 characters, with upper- and lowercase letters and a digit,
   and must not contain your username. Until you change it you can only **Sign out**.
3. To change your password later, use **Change password** in the top bar. **Sign out** is next to it.

After 5 wrong passwords the account is locked for 15 minutes ("Account temporarily locked"). There
is no self-service "forgot password": an administrator must reset it (Users › **Reset password**).

For security the portal signs you out after 30 minutes without keyboard, mouse or touch activity
(the period is set per server). One minute before, "Are you still there?" appears: select
**Stay signed in** to continue. The sign-in page then says "You were signed out after a period of
inactivity".

## Servicing Agent: Agent Console

### Start a session

Open **Agent Console**. Under **Start a counter session**, choose your counter in **Counter** and
select **Start session**. Counters already in use show "— in use by <name>" and cannot be chosen.
You can have only one open session, and a counter only one agent.

The header then shows your counter and status (**Available** or **On break**) and a live indicator.
The page updates automatically when the queue changes.

### Serving clients

| Button | When available | What it does |
|---|---|---|
| **Call next** | You are available and not already serving a called ticket | Calls the next waiting ticket for the services your counter handles: priority lanes first, then in order of arrival. The number appears under **Current ticket** and on the display. "No tickets waiting for this counter." means the queue is empty. |
| **Recall** | A ticket is called | Calls the same number again; the display announces it again and the recall count goes up. |
| **Complete** | A ticket is called | Ends the service ("Ticket … completed."). |
| **Hold** | A ticket is called | Asks for a **Reason**; the client steps aside (for example to complete documents). The ticket moves to **Held tickets** and your counter is free. |
| **Transfer** | A ticket is called or held | Choose **Transfer to service** and enter a **Reason**. The ticket returns to the waiting line of the other service. |
| **Remove** | A ticket is called or held | Enter a **Reason**. For no-shows or clients who left; this ends the ticket. |
| **Void** | Shown only with TICKET_VOID (supervisors, administrators) | Enter a **Reason**. Cancels a ticket issued in error; audited. |

Every dialog that asks for a reason requires one (up to 500 characters) before its button is
enabled; **Cancel** closes it without changes.

**Held tickets** lists tickets on hold at your counter, each with **Resume** (calls it back to your
counter; only when no other ticket is called there), **Transfer** and **Remove**.

### Breaks and ending the session

* **Start break** (reason required) is available when no ticket is called. While on break you
  cannot call tickets; select **End break** to return.
* **End session** ends your session at the counter after confirmation. It is disabled while a
  ticket is called or tickets are on hold: complete, transfer or remove them first.

Not available in the console: calling a specific ticket out of order, and a separate button to move
a multi-stage ticket to its next stage (the stage number is shown; use **Transfer**).

**Next stage.** If the current ticket's service has a next stage configured (multi-stage flow), a
**Next stage: <service>** button sends the client to that service's queue in one click. The stage
number increases and the move is recorded in the ticket history. **Transfer** is still available for
any other destination.

**Supervisors: Close counter.** In **Queue Monitor**, a staffed counter has a **Close counter**
button (QUEUE_SUPERVISE). Enter a reason and confirm. The agent is signed out of that counter, and
any ticket being served or on hold there goes back to its queue at its original place. The action
is audited.

## Supervisor

### Dashboard

Today's figures for the selected site: **Issued today**, **Completed today**, **Waiting now**,
**Avg wait today (min)**, a **Waiting by service** table with estimated waits, and how many
counters are staffed, being served and on hold.

### Queue Monitor

Live lists for the selected site: **Waiting**, **Being served**, **On hold**, and **Counters**
(agent, status, **Availability** and ticket being served). Availability shows **Scheduled open, no agent** when a counter should be open but nobody is signed in, **Staffed outside hours**, **Closed (schedule)** or **Open**. Select a ticket number to open its details: status,
service and stage, priority, channel, counter/agent, issue time, and a **Timeline** of every
event with the person, counter, service and reason.

With QUEUE_SUPERVISE the details window also offers **Transfer** and **Remove** (and **Void** with
TICKET_VOID), each asking for a reason. Supervisors who also serve clients use the Agent Console
like agents.

## LHIO administrator: Administration pages

All lists open with an action button at the top right; rows have **Edit** (and other actions).
Forms save with **Save** unless noted. Nothing in these pages can be deleted; use the **Active**
check box (or user **Status**) instead.

| Page | What you can do |
|---|---|
| **Users** | Search by username, name or email. **New user**: username, full name, email, **Initial password** (must be changed at first login) and **Role assignments** (**Add assignment**; site roles need a site, regional roles a region); **Create user**. Per user: **Edit** (name, email), **Roles**, **Status** (Active / Suspended / Deactivated, with a reason), **Reset password** (sets a temporary password the user must change), **Sessions** (where the user is signed in: browser, address, sign-in time; **Sign out** ends one, **Sign out everywhere** ends all, effective immediately). Locked accounts show **LOCKED**. |
| **Site Services** | **Add service**: pick a **Catalog service**, set **Ticket prefix** (1–3 capital letters, unique at the site), **Est. duration (min)**, **Sort order**, **Opens**/**Closes** (blank = all day), **Next stage (multi-stage flow)** (another service of the site; loops are rejected) and **Active**. Kiosks offer a service only while it is active and open, and some counter eligible for it is open by its hours, has no hours set, or has an agent signed in (a service no counter can serve is not offered). **Add all catalog services (n)** adds every active catalog service the site does not offer yet, after a confirmation; each keeps its catalog prefix (or gets the next free letter) and estimated duration. |
| **Counters** | **New counter**: **Code**, **Name** (announced on the display, e.g. "Counter 3"), **Eligible services** (at least one), **Opening hours** and **Active**. Tick **Set opening hours**, then **Open** a weekday and set its times; **Add window** adds a second period the same day (for example 08:00–12:00 and 13:00–17:00 for a lunch break), and **Copy Monday to Tuesday–Friday** repeats Monday. Without hours a counter is always available. The list shows each counter's hours and the agent currently there. |
| **Devices** | **Register device**: **Code**, **Name**, **Type** (KIOSK or DISPLAY), then **Register**. The API key is shown once: use **Copy**, then **I have saved the key**. **Rotate key** issues a new key and stops the old one immediately. **Edit** changes the name or deactivates the device. **Last seen** shows when the device last contacted the server. |
| **Regions & Sites** | Central and regional administrators: **New region**, **New site** (code, name, type, region, area, time zone, and **Ticket numbers**: **Digits** 2–5, **Separator** hyphen or none, **Restart at 1** every day / Monday / month / year; the form shows an example such as A-007). A change applies to the next ticket and never repeats a number already issued that day. Do not create regions or sites on a synchronized site node ([sync-design.md](sync-design.md)). |
| **Service Catalog**, **Priority Categories** | Central administrators: national services (classification, client and service type, default prefix and duration, multi-stage, documents, eligibility) and priority lanes (**Rank**: lower is served first, 100 is the regular lane). |

## Content (announcements and media)

Open **Content**. Filter by **Site** and **Status**.

1. **New content** (CONTENT_EDIT): **Title**, **Type** (Announcement, Image or Video), **Audience**
   (national within your scope, one of your regions, or one of your sites), **Announcement text** or
   **Caption (optional)**, the file for images (PNG, JPEG, WebP, up to 10 MB) or videos (MP4, WebM,
   up to 200 MB), **Starts**, **Ends** and **Display seconds**. The item is saved as **Draft**.
2. **Submit** sends a draft (or a rejected item) for approval: status **Pending Approval**.
3. An approver (CONTENT_APPROVE) selects **Approve** (publishes it to the kiosks and displays in
   its audience) or **Reject** (with a reason, shown under the title). Authors cannot approve their
   own content.
4. **Archive** removes an item from kiosks and displays.

Only drafts and rejected items can be edited (**Edit**), and the media file cannot be replaced
after creation: create a new item instead. LHIO administrators and supervisors can create and
submit content but not approve it; approval is done by Central or Regional administrators. Content must be approved by someone other than its author; a Super Administrator may approve their own items, and each such self-approval is recorded in the audit log (`CONTENT_SELF_APPROVED`).

## Dashboard: national and site views

Users with several sites start on **All sites** ("All sites (national)" for national roles such as the
Super Administrator; "All my sites" for regional roles), which shows:
* today's totals across all of their sites;
* **Services availed** summed across sites by catalog service;
* a **Sites** table: issued, completed, waiting, average wait and counters staffed per site. Click a
  site to open its own Dashboard.

The site button next to it, or choosing a site in the header **Site** selector, shows a single site
(updated live). The all-sites view refreshes every 30 seconds.

## Dashboard: Services availed

The **Dashboard** shows **Services availed** for the selected site, for **Today**, **Last 7 days**
or **Last 30 days**, in three views:
* **Pie** (default): the share of clients per service. It shows the five most-availed services plus
  one grey **Other** slice for the rest, so it stays readable. The legend lists each slice's count
  and percentage, and hovering a slice shows how many were served, are still in the queue, or
  abandoned the queue.
* **Bars**: one bar per service offered at the site, including services nobody availed yet (at 0).
* **Table**: the same numbers as a table.

Clients are counted by the service they chose at the kiosk; tickets voided as errors are not counted.
The chart refreshes as tickets are issued and completed.

## Reporting officer: Reports

1. Open **Reports**, set the filters (**From**, **To (inclusive)**, **Region**, **Site**, **Group by**,
   **Client type**, **Service type**, **Classification**, **Channel**) and select **Run report**.
2. Tabs:
   * **Summary**: issued, served, served %, abandoned, voided, rerouted, average queue, wait and
     process minutes, feedback count and average rating, per group and in total.
   * **Trends**: a "Clients per day" chart (issued, served, abandoned) and an "Average waiting time
     per day (minutes)" chart. Hover a day, or focus the chart and use the arrow keys, to read the
     values; **Show as table** gives the same numbers as a table.
   * **Ticket details**: one row per ticket, 50 per page. With REPORT_EXPORT, **Export all rows
     (CSV)** downloads every ticket of the filter (up to 100,000).
   * **Data exceptions**: tickets with inconsistent or incomplete data.
3. **Metric definitions** (or "What do the columns mean?") explains each column.
4. With REPORT_EXPORT: **Export CSV**, **Export XLSX** and **Export PDF** download the **summary**
   for the current filters.

**Agent performance** (Reports tab): for each agent and site in the period, shows:
* transactions handled, completed and transferred, plus no-shows and recalls;
* average processing time and the share within the processing target;
* the average wait of the clients they called and the share within the waiting target;
* **SLA** status: MET, NOT MET or NO DATA, decided by processing compliance against the compliance target.

Targets come from **SLA Targets** (Administration menu, permission SLA_MANAGE). There, Central Office
administrators set the national default and any region's SLA; Regional administrators set their
own region's. **Set own SLA** gives a region its own targets, **Add service override** sets targets
for individual services, and **Use national default** reverts the region. With REPORT_EXPORT,
**Download Excel (all agents)** saves a workbook of every active agent and supervisor in scope, including
those with no activity (shown as NO DATA), with five sheets:
* Summary;
* Daily breakdown;
* Transactions: every client each agent handled, with wait and processing times against the targets;
* SLA targets;
* Definitions.

**Download CSV** and **Download PDF** save the summary table.

**Scheduled Reports** (menu under Reports): with REPORT_EXPORT, **New schedule** creates a summary
report that is generated automatically:
* **Daily**: for the previous day.
* **Weekly**: for the previous Monday–Sunday.
* **Monthly**: for the previous month.

Each is generated at 01:00, grouped and formatted (PDF, XLSX or CSV) as chosen. **Scope** is one
site, one region or national, within your own rights. **Run now** generates the last completed
period immediately. **Files** lists generated files with a **Download** button. Files are kept for
the retention period (default 180 days). Email delivery is not available.

Not available: exporting the data-exceptions tab. Supervisors can view reports but not export them.

**Audit Log** (AUDIT_VIEW): filter by **From**, **To**, **Actor (username)** and **Action** (for
example `TICKET_VOID`) and select **Apply**. **Export CSV** downloads the filtered entries (up to
100,000); the export itself is recorded in the audit log.

## System administrator: System & Sync

* **Node status** (SYSTEM_MONITOR): node role and site code, version, central URL, outbox
  pending and failed counts, oldest pending entry, last successful push, last configuration pull
  and server time. **Refresh** reloads it.
* **Sync outbox** (SYNC_MANAGE): entries filtered by **Status** (Failed by default), with attempts,
  next attempt and **Last error**. **Retry** queues an entry again.
* **Sync reconciliation** (both permissions):
  * on a site node, **Send summaries now** queues the daily count summaries for the next push;
  * on the central node, a table compares each site's counts with central's for a date range
    (**Only differences** by default): MATCH, IN TRANSIT or MISMATCH, as explained in
    [sync-design.md](sync-design.md#reconciliation).

## Kiosk, display and feedback (for reference)

* **Kiosk** (`/kiosk`): the client chooses a service under "Please select a service" (with waiting
  count and estimated wait), then a priority lane or **Regular**, then **Print ticket** or **Show on screen only**
  ("Take a photo of your number"). The number is shown with **Print again** and
  **Done**, and a QR code: "Scan with your phone to follow your place in line and rate our service
  afterwards". The printed ticket carries the same QR code.
* **Phone ticket** (`/t/<code>`, opened from the QR code): the number, how many are ahead and the
  estimated wait, updated every 15 seconds; "Now calling! Please proceed to Counter 1" when called;
  after the transaction, "How was our service today?" (once per ticket). The link stops working two
  days later. Phones must be able to reach the site server (for example on the office guest Wi-Fi),
  or set `QMS_TICKET_LINK_BASE_URL` to an address they can reach.
* **Display** (`/display`): called numbers and counters. Each call (and each recall) plays a chime
  followed by a voice announcement, e.g. "Ticket number A, 0, 0, 4, please proceed to Counter 1."
  (English; a Philippine-English voice is used when the device has one). The voice is on by default;
  **Voice on/off** turns it off for that display, and **Fullscreen** toggles full screen. Browsers
  mute a page until someone interacts with it: if the display shows "Tap or click anywhere on this
  screen to turn on the call announcements", tap once. Display PCs set up per
  [kiosk-lockdown.md](kiosk-lockdown.md) (`--autoplay-policy=no-user-gesture-required`) do not need the tap.
* **Feedback at the kiosk**: **Rate our service** on the kiosk home screen. The client types the
  number on their ticket with the on-screen keys, then **Continue**; "How was our service today?"
  takes a 1–5 rating and an optional comment and returns to the home screen after "Thank you!". Only
  today's finished transactions that have not been rated are accepted.
